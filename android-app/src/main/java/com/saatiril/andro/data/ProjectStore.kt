package com.saatiril.andro.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Persists the project list (JSON) to internal storage so the Project Hub
 * can list/resume projects across app restarts.
 *
 * Each [Project] is stored as a single JSON file under
 * `context.filesDir/projects/<projectId>.json`. Files are tiny (a few KB
 * per project) so we keep one file per project for simple atomic
 * create / update / delete semantics.
 *
 * #5 CRASH-SAFE WRITES: USB-driver kernel panics power-cycle the phone with
 * zero warning — anything still in the page cache is LOST (this is exactly
 * how users lost saved projects during the USB reboot storms). Every save
 * therefore does a full fsync pipeline: write tmp → fsync → rotate the old
 * file to `<id>.json.bak` → rename → fsync the directory. If a panic ever
 * corrupts the main file anyway, [loadFromFile] falls back to the `.bak`
 * copy and restores it automatically.
 *
 * Uses Gson (already a dependency — see `app/build.gradle.kts`). No
 * additional AndroidX or third-party dependencies required.
 */
class ProjectStore(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dir: File = File(context.filesDir, DIR_NAME)

    /**
     * Save (create or update) a [project].
     *
     * If the project has a blank `id` the call is a no-op (we cannot
     * construct a filename without an id).
     */
    fun save(project: Project) {
        if (project.id.isBlank()) {
            Log.w(TAG, "save: project.id is blank — skipping")
            return
        }
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "save: could not create projects dir at ${dir.absolutePath}")
                return
            }
            val file = File(dir, "${project.id}.json")
            // Write to a temp file then rename for atomic-ish update.
            val tmp = File(dir, "${project.id}.json.tmp")
            // #4: NEVER persist photo base64 payloads — each photoHistory entry
            // is saved with its photos stripped (student/channel metadata kept).
            // Full multi-MB base64 strings made project files huge and turned
            // every capture into an O(n²) disk rewrite. The in-memory project
            // (gallery, MC per-channel badges) keeps the photos; the photo files
            // themselves live in the output folder. Mirrors the SYNC_DB
            // stripping in AdminViewModel.pushSyncDb().
            val persisted = project.copy(
                photoHistory = project.photoHistory.map { it.copy(photos = emptyList()) }
            )
            // #5 crash-safe pipeline: fsync the tmp file BEFORE renaming, then
            // keep the previous good copy as `<id>.json.bak`, then fsync the
            // directory so the rename itself survives a sudden power loss.
            FileOutputStream(tmp).use { fos ->
                fos.write(gson.toJson(persisted).toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            val bak = File(dir, "${project.id}.json.bak")
            if (file.exists()) {
                if (!file.renameTo(bak) && !bak.delete()) {
                    Log.w(TAG, "save: could not rotate .bak for ${project.id}")
                }
            }
            if (!tmp.renameTo(file)) {
                // Fallback: copy then delete tmp.
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            fsyncDir()
            Log.d(TAG, "Saved project ${project.id} (${project.name}) → ${file.name}")
        } catch (e: IOException) {
            Log.e(TAG, "save failed for ${project.id} — ${e.message}")
        }
    }

    /**
     * Load all saved projects, sorted **newest first** by file modification time.
     * Returns an empty list if the projects directory does not exist or is empty.
     */
    fun list(): List<Project> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        // Heal orphaned backups first: a panic in the microsecond window
        // between "rename main → .bak" and "rename tmp → main" leaves only
        // the .bak behind — restore those projects before listing.
        recoverOrphanedBackups()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file -> loadFromFile(file) }
    }

    /**
     * Load a single project by [projectId]. Returns `null` if the project
     * file does not exist or fails to deserialize.
     */
    fun load(projectId: String): Project? {
        if (projectId.isBlank()) return null
        val file = File(dir, "$projectId.json")
        if (!file.exists()) {
            // Panic may have eaten the main file — try the .bak copy.
            return try { restoreFromBak(file) } catch (_: Exception) { null }
        }
        return loadFromFile(file)
    }

    /** Delete a project by [projectId]. No-op if the project doesn't exist. */
    fun delete(projectId: String) {
        if (projectId.isBlank()) return
        val file = File(dir, "$projectId.json")
        if (file.exists()) {
            val ok = file.delete()
            if (!ok) Log.w(TAG, "delete: File.delete() returned false for ${file.absolutePath}")
        }
        File(dir, "$projectId.json.bak").delete()
    }

    /** Delete all saved projects (the projects directory itself is left in place). */
    fun clear() {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { f ->
            if (f.isFile) f.delete()
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Internals
    // ──────────────────────────────────────────────────────────────────

    private fun loadFromFile(file: File): Project? {
        return try {
            val json = file.readText(Charsets.UTF_8)
            gson.fromJson(json, Project::class.java)
        } catch (e: IOException) {
            Log.e(TAG, "loadFromFile: read failed for ${file.name} — ${e.message}")
            restoreFromBak(file)
        } catch (e: com.google.gson.JsonSyntaxException) {
            // Truncated/corrupt main file (e.g. a kernel panic hit mid-write
            // before #5 landed) — fall back to the last good .bak copy.
            Log.e(TAG, "loadFromFile: JSON parse failed for ${file.name} — ${e.message}")
            restoreFromBak(file)
        }
    }

    /** Try the `.bak` sibling of a corrupt/missing [file]; when it parses,
     * copy it back over the main file so the project is healed on disk. */
    private fun restoreFromBak(file: File): Project? {
        return try {
            val bak = File(file.parentFile, file.nameWithoutExtension + ".json.bak")
            if (!bak.exists()) return null
            val project = gson.fromJson(bak.readText(Charsets.UTF_8), Project::class.java)
            try {
                bak.copyTo(file, overwrite = true)
                Log.w(TAG, "restoreFromBak: recovered ${file.name} from ${bak.name}")
            } catch (e: IOException) {
                Log.w(TAG, "restoreFromBak: recovered ${file.name} in memory only — ${e.message}")
            }
            project
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBak: no usable backup for ${file.name} — ${e.message}")
            null
        }
    }

    /** fsync the projects directory so renames/deletes survive a power loss. */
    private fun fsyncDir() {
        try {
            java.io.FileInputStream(dir).use { it.fd.sync() }
        } catch (_: Exception) {
            // Directory fd sync is best-effort — not all kernels support it.
        }
    }

    /** Copy every orphaned `<id>.json.bak` (main file missing) back to
     *  `<id>.json` so the project reappears in the hub. */
    private fun recoverOrphanedBackups() {
        try {
            val baks = dir.listFiles { f -> f.isFile && f.name.endsWith(".json.bak") } ?: return
            for (bak in baks) {
                val mainName = bak.name.removeSuffix(".bak")
                val main = File(dir, mainName)
                if (!main.exists()) {
                    if (bak.renameTo(File(dir, mainName + ".restore"))) {
                        val restored = File(dir, mainName + ".restore")
                        if (restored.renameTo(main)) {
                            Log.w(TAG, "recoverOrphanedBackups: restored $mainName")
                        } else {
                            restored.renameTo(bak)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "ProjectStore"
        private const val DIR_NAME = "projects"
    }
}
