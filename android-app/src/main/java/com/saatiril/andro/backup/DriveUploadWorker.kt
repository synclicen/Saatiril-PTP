package com.saatiril.andro.backup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Background worker that processes the Google Drive upload queue.
 *
 * Runs a coroutine loop that:
 *  1. Checks if there's internet (via DriveBackupManager.processNext)
 *  2. If queue has items, uploads them one by one
 *  3. If queue empty, waits 5 seconds and checks again
 *  4. If upload fails, item stays in queue with retry count (exponential backoff)
 *
 * The worker runs in a background [Dispatchers.IO] coroutine and survives
 * as long as the [DriveBackupManager] is alive (app lifecycle).
 */
class DriveUploadWorker(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var workerJob: Job? = null
    private val driveBackupManager = DriveBackupManager(context)

    companion object {
        private const val TAG = "DriveUploadWorker"
        private const val POLL_INTERVAL_MS = 5000L  // 5 seconds
        private const val ERROR_BACKOFF_MS = 10000L  // 10 seconds after error

        @Volatile
        private var instance: DriveUploadWorker? = null

        /** Get the singleton instance (starts the worker if not running). */
        fun getInstance(context: Context): DriveUploadWorker {
            return instance ?: synchronized(this) {
                instance ?: DriveUploadWorker(context.applicationContext).also {
                    instance = it
                    it.start()
                }
            }
        }
    }

    /** Start the background upload loop. */
    fun start() {
        if (workerJob?.isActive == true) {
            Log.d(TAG, "Worker already running")
            return
        }
        // ISSUE 1 fix: reset stuck UPLOADING items on start. If the app was
        // killed while an item was UPLOADING, it would stay stuck forever
        // (dequeue only returns PENDING). This recovers all stuck items.
        driveBackupManager.resetStuckUploads()
        workerJob = scope.launch {
            Log.i(TAG, "Drive upload worker started")
            while (true) {
                try {
                    // BUG #6: don't gate on hasBackupFolder() — per-project
                    // uploads (item.treeUri != null) work even when the global
                    // folder is not configured. processNext() routes via
                    // uploadItem() which dispatches to the right method.
                    if (!driveBackupManager.hasBackupFolder()) {
                        // No global backup folder — but the queue may still
                        // have per-project items. Process them; if the queue
                        // is empty, sleep longer.
                        val processed = driveBackupManager.processNext()
                        if (!processed) {
                            delay(POLL_INTERVAL_MS * 2)
                        } else {
                            delay(500)
                        }
                        continue
                    }

                    val processed = driveBackupManager.processNext()

                    if (!processed) {
                        // Queue empty — wait before checking again
                        delay(POLL_INTERVAL_MS)
                    } else {
                        // Processed an item — short delay before next
                        delay(500)
                    }
                } catch (e: CancellationException) {
                    // BUG #10 fix: rethrow CancellationException BEFORE the
                    // generic catch. The generic catch below swallowed it,
                    // which meant `workerJob.cancel()` (called from stop())
                    // never actually cancelled the loop — the worker would
                    // catch the cancellation, sleep ERROR_BACKOFF_MS, and
                    // resume, busy-looping forever. Rethrowing lets the
                    // coroutine machinery properly terminate the job.
                    Log.i(TAG, "Worker cancelled — exiting loop")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Worker error: ${e.message}", e)
                    delay(ERROR_BACKOFF_MS)
                }
            }
        }
    }

    /** Stop the background upload loop. */
    fun stop() {
        workerJob?.cancel()
        workerJob = null
        Log.i(TAG, "Drive upload worker stopped")
    }

    /**
     * Check if internet is currently available.
     * Used to decide whether to attempt upload or wait.
     */
    private fun isInternetAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }
}
