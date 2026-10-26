package com.saatiril.andro.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages Google Drive backup via Android's Storage Access Framework (SAF).
 *
 * HOW IT WORKS:
 * The admin picks a folder using [Intent.ACTION_OPEN_DOCUMENT_TREE]. If they
 * pick a folder inside Google Drive, Android returns a SAF tree URI that
 * points to the Google Drive folder. We can then use [DocumentsContract] to
 * create files inside that folder — Google Drive's document provider handles
 * the actual cloud upload (async, with built-in retry and sync).
 *
 * ADVANTAGES (vs. OAuth REST API):
 *  - No Google Cloud Project setup needed
 *  - No OAuth client ID / API key needed
 *  - No google-api-services-drive dependency (saves ~2MB APK size)
 *  - Upload happens via Google Drive app's sync mechanism (battle-tested)
 *  - Works offline (files saved to Drive app's local cache, synced when online)
 *  - Admin can pick ANY cloud provider (Drive, Dropbox, OneDrive) — not just Drive
 *
 * FLOW:
 *  1. Admin taps "Pilih Folder Google Drive" → SAF folder picker opens
 *  2. Admin navigates to Google Drive → picks/creates a folder
 *  3. We persist the URI permission (survives reboots)
 *  4. For each photo in the upload queue:
 *     a. Open input stream from local photo URI
 *     b. Create document in Drive folder via DocumentsContract.createDocument
 *     c. Open output stream, copy bytes
 *     d. Google Drive app syncs the file to cloud (async)
 */
class DriveBackupManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val resolver: ContentResolver = context.contentResolver
    private val queueStore = UploadQueueStore(context)

    /**
     * Set the Google Drive (or any cloud) backup folder from SAF tree URI.
     * Persists URI permission so the folder remains accessible after reboot.
     */
    fun setBackupFolder(treeUri: Uri) {
        try {
            resolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not take persistable URI permission: ${e.message}")
        }
        prefs.edit().putString(KEY_DRIVE_URI, treeUri.toString()).apply()
        Log.i(TAG, "Backup folder set: $treeUri")
    }

    /** Returns true if a backup folder has been configured. */
    fun hasBackupFolder(): Boolean = getBackupFolder() != null

    /** ISSUE 1 fix: reset stuck UPLOADING items back to PENDING. */
    fun resetStuckUploads() {
        queueStore.resetStuckItems()
    }

    /** Returns the configured backup folder tree URI, or null if not set. */
    fun getBackupFolder(): Uri? {
        val s = prefs.getString(KEY_DRIVE_URI, null) ?: return null
        return try { Uri.parse(s) } catch (_: Exception) { null }
    }

    /** Clear the backup folder (disable backup). */
    fun clearBackupFolder() {
        getBackupFolder()?.let { uri ->
            try {
                resolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not release URI permission: ${e.message}")
            }
        }
        prefs.edit().remove(KEY_DRIVE_URI).apply()
        Log.i(TAG, "Backup folder cleared")
    }

    /**
     * Upload a single photo to the Google Drive backup folder (GLOBAL backup).
     *
     * Now mirrors the LOCAL folder structure: uploads to root/projectName/filename
     * instead of a flat root/filename dump. The projectName is sanitized the same
     * way as local save (strip non-alphanumeric, spaces → underscores) so the
     * folder name matches local exactly.
     *
     * @param localUri The content URI of the locally-saved photo (from PhotoSaver)
     * @param filename The filename to use in the Drive folder
     * @param projectName The project name — used to create a subfolder (mirrors local)
     * @return true if upload succeeded, false otherwise
     */
    fun uploadPhoto(localUri: String, filename: String, projectName: String? = null): Boolean {
        Log.i(TAG, "uploadPhoto (global): filename=$filename, project='$projectName'")
        val treeUri = getBackupFolder() ?: run {
            Log.e(TAG, "uploadPhoto FAILED: no backup folder set")
            return false
        }
        // Delegate to uploadPhotoToTreeUri (uses DocumentFile — reliable across
        // SAF providers including Google Drive). This ensures the global backup
        // path also creates project subfolders + uses the same reliable code.
        return uploadPhotoToTreeUri(localUri, filename, treeUri, projectName)
    }

    /**
     * Upload a single photo to a SPECIFIC project's backup folder (PER-PROJECT).
     * @param treeUriString the project's driveFolder (SAF tree Uri string from
     *   ProjectConfig.driveFolder). null/empty → skip (no backup for this
     *   project — NOT auto-connected).
     *
     * BUG #6 fix: route through [UploadQueueStore] so per-project uploads get
     * the same retry / persistence / worker-drain guarantees as global uploads.
     * The previous implementation did a direct synchronous copy on the
     * caller's thread — if it failed (Drive app not yet synced, transient
     * I/O error, …) the photo was silently NOT backed up and there was no
     * retry path. Now the photo is enqueued with the per-project tree URI;
     * [DriveUploadWorker] picks it up via [processNext] and retries on
     * failure with the same exponential-backoff path the global folder uses.
     *
     * @return true always (the enqueue itself is best-effort and retried by
     *   the worker). False only if the tree URI is null/empty (no backup
     *   configured for this project).
     */
    fun uploadPhotoForProject(localUri: String, filename: String, treeUriString: String?, projectName: String? = null): Boolean {
        // Determine the actual project name — use the explicit parameter,
        // fall back to _currentProjectNameForQueue (set via setCurrentProjectName).
        val actualProjectName = if (!projectName.isNullOrBlank()) projectName else _currentProjectNameForQueue
        Log.i(TAG, "uploadPhotoForProject: filename=$filename, driveFolder=$treeUriString, projectName='$actualProjectName'")

        if (treeUriString.isNullOrEmpty()) {
            // No per-project drive folder — fall back to GLOBAL backup folder
            // (if set). This ensures photos are still uploaded to Drive even
            // when the user only configured a global backup folder (not a
            // per-project one). The projectName is still passed so the subfolder
            // structure is created in the global folder too.
            val globalTreeUri = getBackupFolder()
            if (globalTreeUri == null) {
                Log.i(TAG, "uploadPhotoForProject: no driveFolder AND no global backup folder — skipping (local save only)")
                return false
            }
            Log.i(TAG, "uploadPhotoForProject: no per-project driveFolder — using GLOBAL backup folder with project subfolder")
            queueStore.enqueue(filename, localUri, actualProjectName, null)  // treeUri=null → global path
            Log.i(TAG, "uploadPhotoForProject: enqueued GLOBAL upload: $filename (project=$actualProjectName)")
            return true
        }
        // Validate the tree URI parses before enqueueing (cheap sanity check).
        val treeUri = try { Uri.parse(treeUriString) } catch (_: Exception) { return false }
        queueStore.enqueue(filename, localUri, actualProjectName, treeUri.toString())
        Log.i(TAG, "uploadPhotoForProject: enqueued per-project upload: $filename → tree=$treeUri (project=$actualProjectName)")
        return true
    }

    /**
     * BUG #6 helper: the worker reads [UploadItem.treeUri] and dispatches to
     * either the global [uploadPhoto] (treeUri == null) or the per-project
     * [uploadPhotoToTreeUri] (treeUri != null). Exposed so [processNext] can
     * route correctly.
     */
    private fun uploadItem(item: UploadItem): Boolean {
        Log.i(TAG, "uploadItem: filename=${item.filename}, project='${item.projectName}', treeUri=${if (item.treeUri.isNullOrEmpty()) "GLOBAL" else "per-project"}")
        return if (item.treeUri.isNullOrEmpty()) {
            // Global backup path — pass projectName so it creates a subfolder too.
            uploadPhoto(item.localUri, item.filename, item.projectName)
        } else {
            // Per-project backup path — pass projectName for subfolder creation.
            uploadPhotoToTreeUri(item.localUri, item.filename, Uri.parse(item.treeUri), item.projectName)
        }
    }

    /** BUG #6: per-project name tag used in queue rows (best-effort).
     *  Updated via setCurrentProjectName() when a project is loaded.
     *  Used as fallback when callers don't pass projectName explicitly. */
    @Volatile private var _currentProjectNameForQueue: String = "default"

    /**
     * Set the current project name for the Drive backup queue. Called when a
     * project is loaded/started so that even if uploadPhotoForProject is called
     * without an explicit projectName, the correct subfolder is created.
     */
    fun setCurrentProjectName(name: String?) {
        _currentProjectNameForQueue = if (!name.isNullOrBlank()) name else "default"
        Log.i(TAG, "setCurrentProjectName: $_currentProjectNameForQueue")
    }

    // Note: projectSubfolderCache was REMOVED. Caching caused the "only 1 photo
    // uploaded" bug — DocumentFile.fromSingleUri (used to re-create from cached
    // URI) returns a non-writable DocumentFile that doesn't support createFile.
    // Now ensureProjectSubfolder re-queries from root every time (reliable).

    /**
     * Ensure a project subfolder exists in the Drive backup tree + return its Uri.
     * Mirrors the LOCAL folder structure (root/projectName/) so the Drive backup
     * has the SAME layout as the local save: Drive:/<projectName>/<photo>.jpg
     * instead of a flat Drive:/<photo>.jpg dump.
     *
     * Idempotent: queries for existing folder first, creates if missing, caches.
     */
    private fun ensureProjectSubfolder(treeUri: Uri, projectName: String): DocumentFile? {
        if (projectName.isBlank()) {
            Log.w(TAG, "ensureProjectSubfolder: projectName is blank — returning null")
            return null
        }

        val sanitizedName = projectName.replace(Regex("[^a-zA-Z0-9_\\-\\s]"), "").replace(Regex("\\s+"), "_")
        if (sanitizedName.isBlank()) {
            Log.w(TAG, "ensureProjectSubfolder: sanitized name is blank (original='$projectName') — returning null")
            return null
        }

        Log.i(TAG, "ensureProjectSubfolder: treeUri=$treeUri, sanitizedName='$sanitizedName'")

        // CRITICAL: do NOT cache the DocumentFile. DocumentFile.fromSingleUri
        // returns a non-writable DocumentFile (doesn't support createFile).
        // Only TreeDocumentFile (from fromTreeUri + findFile/createDirectory)
        // supports createFile. If we cache + re-create via fromSingleUri, the
        // 2nd+ photo uploads would fail silently (createFile returns null).
        // Instead, re-query from root every time — slightly slower but reliable.
        return try {
            val rootDocFile = DocumentFile.fromTreeUri(context, treeUri)
            if (rootDocFile == null) {
                Log.e(TAG, "ensureProjectSubfolder: DocumentFile.fromTreeUri returned null for $treeUri")
                return null
            }
            if (!rootDocFile.canWrite()) {
                Log.e(TAG, "ensureProjectSubfolder: root folder is NOT writable — check permissions")
                return null
            }

            // Check if folder already exists.
            val existing = rootDocFile.findFile(sanitizedName)
            if (existing != null && existing.isDirectory) {
                Log.i(TAG, "ensureProjectSubfolder: existing folder found → ${existing.uri}")
                return existing
            }
            if (existing != null && !existing.isDirectory) {
                Log.e(TAG, "ensureProjectSubfolder: a FILE named '$sanitizedName' already exists (not a directory)")
                return null
            }

            // Folder doesn't exist — create it.
            Log.i(TAG, "ensureProjectSubfolder: creating directory '$sanitizedName' in $treeUri")
            val newFolder = rootDocFile.createDirectory(sanitizedName)
            if (newFolder != null) {
                Log.i(TAG, "ensureProjectSubfolder: CREATED → ${newFolder.uri}")
                newFolder
            } else {
                Log.e(TAG, "ensureProjectSubfolder: createDirectory returned null for '$sanitizedName'")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureProjectSubfolder EXCEPTION for '$sanitizedName' — ${e.message}", e)
            null
        }
    }

    /** Core upload logic — shared by uploadPhoto (global) + uploadPhotoForProject (per-project).
     *  Now mirrors the LOCAL folder structure: uploads to root/projectName/filename
     *  instead of a flat root/filename dump. */
    private fun uploadPhotoToTreeUri(localUri: String, filename: String, treeUri: Uri, projectName: String? = null): Boolean {
        Log.i(TAG, "uploadPhotoToTreeUri: filename=$filename, project='$projectName', treeUri=$treeUri")
        return try {
            val rootDocFile = DocumentFile.fromTreeUri(context, treeUri)
            if (rootDocFile == null) {
                Log.e(TAG, "uploadPhotoToTreeUri: DocumentFile.fromTreeUri returned null for $treeUri")
                return false
            }

            // Determine the parent folder: project subfolder if projectName is set,
            // otherwise root. ensureProjectSubfolder now returns a DocumentFile
            // directly (not a Uri) — avoids the fromTreeUri/fromSingleUri mismatch
            // that caused the previous flat-upload bug.
            val parentFolder = if (!projectName.isNullOrBlank()) {
                val subfolder = ensureProjectSubfolder(treeUri, projectName)
                if (subfolder != null) {
                    Log.i(TAG, "uploadPhotoToTreeUri: using project subfolder → ${subfolder.uri}")
                    subfolder
                } else {
                    Log.w(TAG, "uploadPhotoToTreeUri: subfolder creation FAILED for '$projectName' — uploading to ROOT (flat)")
                    rootDocFile
                }
            } else {
                Log.i(TAG, "uploadPhotoToTreeUri: no projectName — uploading to ROOT (flat)")
                rootDocFile
            }

            // Create the photo file in the parent folder.
            val photoFile = parentFolder.createFile(MIME_JPEG, filename)
            if (photoFile == null) {
                Log.e(TAG, "uploadPhotoToTreeUri FAILED: createFile returned null for '$filename'")
                return false
            }
            val docUri = photoFile.uri

            val input: InputStream = resolver.openInputStream(Uri.parse(localUri))
                ?: run {
                    Log.e(TAG, "uploadPhotoToTreeUri FAILED: cannot open input stream for $localUri")
                    try { photoFile.delete() } catch (_: Exception) {}
                    return false
                }

            val output: OutputStream = resolver.openOutputStream(docUri)
                ?: run {
                    Log.e(TAG, "uploadPhotoToTreeUri FAILED: cannot open output stream for $docUri")
                    input.close()
                    try { photoFile.delete() } catch (_: Exception) {}
                    return false
                }

            input.use { inp ->
                output.use { out ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (inp.read(buffer).also { read = it } > 0) {
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
            }

            Log.i(TAG, "uploadPhotoToTreeUri SUCCESS: $filename → $docUri (project=$projectName)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "uploadPhotoToTreeUri EXCEPTION for $filename — ${e.message}", e)
            false
        }
    }

    /**
     * Set the Google Drive backup folder for a SPECIFIC project (per-project).
     * Takes the persistable URI permission (Android global, but the Uri string
     * is stored per-project in the caller's ProjectConfig.driveFolder). The
     * caller (AdminDashboardScreen) saves the returned Uri string to the
     * current project's config.
     * @return the treeUri string to store in ProjectConfig.driveFolder, or null on failure.
     */
    fun takeProjectBackupPermission(treeUri: Uri): String? {
        return try {
            resolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            treeUri.toString()
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not take persistable URI permission: ${e.message}")
            null
        }
    }

    /**
     * Enqueue a photo for upload. Called after PhotoSaver.savePhoto() succeeds.
     * The actual upload is done by [DriveUploadWorker] in the background.
     */
    fun enqueueUpload(filename: String, localUri: String, projectName: String) {
        if (!hasBackupFolder()) {
            Log.i(TAG, "enqueueUpload: no backup folder set — skipping (local save only)")
            return
        }
        queueStore.enqueue(filename, localUri, projectName)
    }

    /**
     * Process the next item in the upload queue. Called by [DriveUploadWorker].
     * @return true if an item was processed (uploaded or failed), false if queue empty
     */
    fun processNext(): Boolean {
        val item = queueStore.dequeue() ?: return false

        queueStore.markUploading(item.id)

        // BUG #6: route via uploadItem which dispatches to the per-project
        // tree URI (if set) or the global backup folder (if not).
        val success = uploadItem(item)

        if (success) {
            queueStore.markDone(item.id)
            queueStore.incrementUploaded()
        } else {
            queueStore.markFailed(item.id, "Upload failed (see logs)")
        }

        return true
    }

    /** Get current upload queue statistics. */
    fun getStats(): UploadStats = queueStore.getStats()

    /** Retry all failed uploads. */
    fun retryAllFailed() = queueStore.retryAllFailed()

    /**
     * Normalize a tree URI to a document URI.
     * Same logic as PhotoSaver.normalizeToDocumentUri — needed because
     * DocumentsContract.createDocument requires a document URI.
     */
    private fun normalizeToDocumentUri(uri: Uri): Uri {
        val path = uri.path ?: return uri
        return if (path.contains("/document/")) {
            uri
        } else {
            try {
                val rootDocId = DocumentsContract.getTreeDocumentId(uri)
                DocumentsContract.buildDocumentUriUsingTree(uri, rootDocId)
            } catch (e: Exception) {
                Log.e(TAG, "normalizeToDocumentUri failed: ${e.message}")
                uri
            }
        }
    }

    companion object {
        private const val TAG = "DriveBackupMgr"
        private const val PREFS_NAME = "drive_backup_prefs"
        private const val KEY_DRIVE_URI = "backup_folder_uri"
        private const val MIME_JPEG = "image/jpeg"
    }
}
