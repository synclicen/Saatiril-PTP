package com.saatiril.andro.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * SQLite-backed upload queue for Google Drive backup.
 *
 * Tracks the upload status of every photo that needs to be backed up to
 * Google Drive. Photos are enqueued immediately after being saved to the
 * local folder. A background worker ([DriveUploadWorker]) dequeues items
 * and uploads them.
 *
 * If the upload fails (e.g. no internet, Google Drive not synced), the item
 * stays in the queue with an incremented retry count. The worker will try
 * again later.
 *
 * States:
 *  - PENDING: queued, waiting for upload
 *  - UPLOADING: currently being uploaded
 *  - DONE: uploaded successfully (removed from queue)
 *  - FAILED: exceeded max retries (stays in queue for manual retry)
 */
class UploadQueueStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "UploadQueueStore"
        private const val DB_NAME = "saatiril_upload_queue.db"
        // BUG #6: bumped 1 → 2 to add the tree_uri column (per-project Drive
        // backup target). The onUpgrade path ALTERs the existing table so
        // existing installations keep their queue.
        private const val DB_VERSION = 2

        private const val TABLE = "upload_queue"
        private const val COL_ID = "id"
        private const val COL_FILENAME = "filename"
        private const val COL_LOCAL_URI = "local_uri"
        private const val COL_PROJECT_NAME = "project_name"
        private const val COL_STATUS = "status"
        private const val COL_RETRY_COUNT = "retry_count"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPLOADED_AT = "uploaded_at"
        private const val COL_ERROR = "error"
        // BUG #6: per-project Drive tree URI. Null/empty → use the global
        // backup folder (legacy behavior). Non-null → upload to this
        // project's specific Drive subfolder.
        private const val COL_TREE_URI = "tree_uri"

        const val STATUS_PENDING = "pending"
        const val STATUS_UPLOADING = "uploading"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"

        private const val MAX_RETRIES = 5
    }

    init {
        // Create the table on first access
        readableDatabase
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_FILENAME TEXT NOT NULL,
                $COL_LOCAL_URI TEXT NOT NULL,
                $COL_PROJECT_NAME TEXT NOT NULL,
                $COL_STATUS TEXT NOT NULL DEFAULT '$STATUS_PENDING',
                $COL_RETRY_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPLOADED_AT INTEGER,
                $COL_ERROR TEXT,
                $COL_TREE_URI TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_status ON $TABLE ($COL_STATUS)")
        db.execSQL("CREATE INDEX idx_project ON $TABLE ($COL_PROJECT_NAME)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // BUG #6: migrate v1 → v2 by adding the tree_uri column. ALTER TABLE
        // ADD COLUMN preserves existing rows (their tree_uri defaults to NULL
        // → upload via the global backup folder, same as before).
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_TREE_URI TEXT")
                Log.i(TAG, "BUG#6: added $COL_TREE_URI column (v1 → v2)")
            } catch (e: Exception) {
                Log.w(TAG, "BUG#6: ALTER TABLE failed (column may already exist): ${e.message}")
            }
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // BUG #6: no-op — keep the existing table. New columns added by a
        // newer app version are harmless for an older app version (ignored).
        Log.i(TAG, "onDowngrade: $oldVersion → $newVersion (keeping existing table)")
    }

    /**
     * Add a photo to the upload queue.
     * Called immediately after [com.saatiril.andro.util.PhotoSaver.savePhoto] succeeds.
     *
     * BUG #6: [treeUri] is the per-project Drive folder (SAF tree URI string).
     * Null/empty → upload via the global backup folder (legacy behavior).
     * Non-null → upload to this project's specific Drive subfolder. The
     * DriveUploadWorker reads this column and routes the upload accordingly.
     */
    fun enqueue(filename: String, localUri: String, projectName: String, treeUri: String? = null): Long {
        val values = ContentValues().apply {
            put(COL_FILENAME, filename)
            put(COL_LOCAL_URI, localUri)
            put(COL_PROJECT_NAME, projectName)
            put(COL_STATUS, STATUS_PENDING)
            put(COL_RETRY_COUNT, 0)
            put(COL_CREATED_AT, System.currentTimeMillis())
            // BUG #6: store the per-project tree URI (may be null → global folder).
            put(COL_TREE_URI, treeUri)
        }
        val id = writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Log.i(TAG, "Enqueued upload: $filename (id=$id, treeUri=${if (treeUri.isNullOrEmpty()) "global" else "per-project"})")
        return id
    }

    /**
     * Get the next pending item to upload (FIFO order).
     * Returns null if queue is empty.
     */
    fun dequeue(): UploadItem? {
        val cursor = readableDatabase.query(
            TABLE, null,
            "$COL_STATUS = ?",
            arrayOf(STATUS_PENDING),
            null, null,
            "$COL_ID ASC",
            "1"
        )
        return cursor.use { c ->
            if (c.moveToFirst()) cursorToItem(c) else null
        }
    }

    /**
     * ISSUE 1 fix: reset all UPLOADING items back to PENDING.
     * Called on worker start to recover from stuck items — if the app was
     * killed (Android power management, crash) while an item was UPLOADING,
     * that item would stay in UPLOADING status forever (dequeue only returns
     * PENDING items) → remaining items never processed → "only 1 photo
     * uploaded" bug.
     */
    fun resetStuckItems() {
        val count = writableDatabase.update(
            TABLE,
            ContentValues().apply { put(COL_STATUS, STATUS_PENDING) },
            "$COL_STATUS = ?",
            arrayOf(STATUS_UPLOADING)
        )
        if (count > 0) {
            Log.i(TAG, "resetStuckItems: reset $count stuck UPLOADING items → PENDING")
        }
    }

    /** Mark an item as currently uploading. */
    fun markUploading(id: Long) {
        val values = ContentValues().apply { put(COL_STATUS, STATUS_UPLOADING) }
        writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /** Mark an item as successfully uploaded — removes from queue. */
    fun markDone(id: Long) {
        val values = ContentValues().apply {
            put(COL_STATUS, STATUS_DONE)
            put(COL_UPLOADED_AT, System.currentTimeMillis())
        }
        writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
        Log.i(TAG, "Upload done: id=$id")
    }

    /** Mark an item as failed, increment retry count. If max retries exceeded, mark as FAILED. */
    fun markFailed(id: Long, error: String) {
        val cursor = readableDatabase.query(
            TABLE, arrayOf(COL_RETRY_COUNT),
            "$COL_ID = ?", arrayOf(id.toString()),
            null, null, null
        )
        val retryCount = cursor.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        if (retryCount + 1 >= MAX_RETRIES) {
            val values = ContentValues().apply {
                put(COL_STATUS, STATUS_FAILED)
                put(COL_RETRY_COUNT, retryCount + 1)
                put(COL_ERROR, error)
            }
            writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
            Log.w(TAG, "Upload FAILED permanently (max retries): id=$id, error=$error")
        } else {
            val values = ContentValues().apply {
                put(COL_STATUS, STATUS_PENDING)
                put(COL_RETRY_COUNT, retryCount + 1)
                put(COL_ERROR, error)
            }
            writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
            Log.w(TAG, "Upload failed (retry ${retryCount + 1}/$MAX_RETRIES): id=$id, error=$error")
        }
    }

    /** Get queue statistics for UI display. Never throws — returns zeros on error. */
    fun getStats(): UploadStats {
        return try {
            val pending = countByStatus(STATUS_PENDING)
            val uploading = countByStatus(STATUS_UPLOADING)
            val failed = countByStatus(STATUS_FAILED)
            val totalUploaded = getTotalUploaded()
            UploadStats(pending = pending, uploading = uploading, failed = failed, totalUploaded = totalUploaded)
        } catch (e: Exception) {
            Log.w(TAG, "getStats error: ${e.message}")
            UploadStats(pending = 0, uploading = 0, failed = 0, totalUploaded = 0)
        }
    }

    private fun countByStatus(status: String): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $COL_STATUS = ?",
            arrayOf(status)
        )
        return cursor.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun getTotalUploaded(): Int {
        // The upload_counter table is created lazily in incrementUploaded().
        // If it doesn't exist yet (no uploads have happened), return 0.
        return try {
            val cursor = readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM upload_counter WHERE type = 'uploaded'", null
            )
            cursor.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } catch (e: Exception) {
            // Table doesn't exist yet — no uploads done
            0
        }
    }

    /** Increment the uploaded counter (called when an item is marked done). */
    fun incrementUploaded() {
        try {
            writableDatabase.execSQL("""
                CREATE TABLE IF NOT EXISTS upload_counter (
                    type TEXT PRIMARY KEY,
                    count INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            writableDatabase.execSQL(
                "INSERT OR REPLACE INTO upload_counter (type, count) VALUES ('uploaded', COALESCE((SELECT count FROM upload_counter WHERE type='uploaded'), 0) + 1)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "incrementUploaded error: ${e.message}")
        }
    }

    /** Reset the uploaded counter (e.g. when starting a new project). */
    fun resetCounter() {
        try {
            writableDatabase.execSQL("DROP TABLE IF EXISTS upload_counter")
        } catch (_: Exception) {}
    }

    /** Retry all FAILED items. */
    fun retryAllFailed() {
        val values = ContentValues().apply {
            put(COL_STATUS, STATUS_PENDING)
            put(COL_RETRY_COUNT, 0)
            put(COL_ERROR, null as String?)
        }
        val count = writableDatabase.update(TABLE, values, "$COL_STATUS = ?", arrayOf(STATUS_FAILED))
        Log.i(TAG, "Retried $count failed uploads")
    }

    /**
     * Recover rows stuck in UPLOADING. If the app dies mid-upload (process
     * death, OOM kill), those rows are never re-dequeued by the worker (it
     * only picks status='pending'). Called once at app startup to reset them
     * to PENDING so they get retried.
     * @return number of rows recovered
     */
    fun recoverStuckUploads(): Int {
        return try {
            val values = ContentValues().apply { put(COL_STATUS, STATUS_PENDING) }
            val count = writableDatabase.update(
                TABLE, values, "$COL_STATUS = ?", arrayOf(STATUS_UPLOADING)
            )
            if (count > 0) Log.i(TAG, "Recovered $count stuck 'uploading' rows → pending")
            count
        } catch (e: Exception) {
            Log.w(TAG, "recoverStuckUploads error: ${e.message}")
            0
        }
    }

    /** Clear all items for a specific project. */
    fun clearProject(projectName: String) {
        val count = writableDatabase.delete(TABLE, "$COL_PROJECT_NAME = ?", arrayOf(projectName))
        Log.i(TAG, "Cleared $count items for project '$projectName'")
    }

    private fun cursorToItem(c: android.database.Cursor): UploadItem {
        return UploadItem(
            id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
            filename = c.getString(c.getColumnIndexOrThrow(COL_FILENAME)),
            localUri = c.getString(c.getColumnIndexOrThrow(COL_LOCAL_URI)),
            projectName = c.getString(c.getColumnIndexOrThrow(COL_PROJECT_NAME)),
            status = c.getString(c.getColumnIndexOrThrow(COL_STATUS)),
            retryCount = c.getInt(c.getColumnIndexOrThrow(COL_RETRY_COUNT)),
            createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT)),
            // BUG #6: read tree_uri. Use getColumnIndex (returns -1 if column
            // is missing — possible on a v1 DB that wasn't yet upgraded) and
            // fall back to null.
            treeUri = c.getColumnIndex(COL_TREE_URI).let { idx ->
                if (idx < 0 || c.isNull(idx)) null else c.getString(idx)
            }
        )
    }
}

data class UploadItem(
    val id: Long,
    val filename: String,
    val localUri: String,
    val projectName: String,
    val status: String,
    val retryCount: Int,
    val createdAt: Long,
    /** BUG #6: per-project Drive folder (SAF tree URI). Null → use global backup folder. */
    val treeUri: String? = null
)

data class UploadStats(
    val pending: Int,
    val uploading: Int,
    val failed: Int,
    val totalUploaded: Int
) {
    val total: Int get() = pending + uploading + failed + totalUploaded
}
