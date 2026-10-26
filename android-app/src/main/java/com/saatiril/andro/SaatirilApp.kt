package com.saatiril.andro

import android.app.Application
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Application class with global crash protection.
 *
 * CRITICAL: The uncaught exception handler prevents silent crashes.
 * Many Android crashes (NoSuchMethodError, NoClassDefFoundError, etc.)
 * can be caught here and turned into user-visible error messages
 * instead of killing the app process.
 */
class SaatirilApp : Application() {

    companion object {
        private const val TAG = "SaatirilApp"

        /**
         * BUG #17 fix: classify which throwables are UNRECOVERABLE — these
         * indicate the app's runtime state is corrupted and continuing to
         * swallow + run will only cause cascading failures (every subsequent
         * operation hits the same broken classpath / exhausted heap / etc.).
         * For these we delegate to the platform's default handler so the
         * process restarts cleanly. Everything else (IOException,
         * NetworkOnMainThreadException, RuntimeException from a one-off bad
         * input, …) is recoverable — log + swallow so a transient hiccup
         * doesn't kill the ceremony mid-event.
         */
        private fun isUnrecoverable(t: Throwable): Boolean {
            // JVM fatal errors — the spec requires ThreadDeath to propagate.
            if (t is ThreadDeath) return true
            if (t is VirtualMachineError) return true  // OutOfMemoryError, StackOverflowError, InternalError
            // Missing-class errors — the classpath is broken; every future
            // call site that touches the same class will fail the same way.
            if (t is NoClassDefFoundError) return true
            if (t is NoSuchMethodError || t is NoSuchFieldError) return true
            // LinkageError (verifier failures, etc.) — same rationale.
            if (t is LinkageError) return true
            return false
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Set up global uncaught exception handler to prevent silent crashes.
        // For non-fatal errors on background threads (coroutine exceptions from
        // ktor, etc.), we log but DON'T kill the app — only main-thread errors
        // are passed to the default handler (which shows the crash dialog).
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "=== UNCAUGHT EXCEPTION on thread: ${thread.name} ===")
            Log.e(TAG, "Type: ${throwable.javaClass.simpleName}")
            Log.e(TAG, "Message: ${throwable.message}")
            Log.e(TAG, "Full stacktrace:", throwable)

            val isMainThread = thread == Looper.getMainLooper().thread
            // BUG #17 fix: distinguish recoverable vs unrecoverable throwables.
            // Previously the handler swallowed ALL background-thread exceptions,
            // including VirtualMachineError (OOM, StackOverflow) and
            // NoClassDefFoundError / NoSuchMethodError. Swallowing an OOM
            // means the app limps along in a half-broken state until the next
            // OOM kills it — worse UX than a clean restart. Swallowing a
            // missing-class error means the user keeps tapping buttons that
            // all silently fail. Now we delegate fatal errors to the platform
            // default handler (clean process restart) and only swallow
            // genuinely recoverable errors (transient IO/network/runtime
            // exceptions from background coroutines).
            val unrecoverable = isUnrecoverable(throwable)

            if (isMainThread || unrecoverable) {
                // Main thread errors OR unrecoverable background errors are
                // fatal — show toast + pass to default handler (which kills
                // the process so it can restart cleanly).
                try {
                    Toast.makeText(
                        this,
                        "Saatiril error: ${throwable.javaClass.simpleName}: ${throwable.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) { }
                defaultHandler?.uncaughtException(thread, throwable)
            } else {
                // Background thread, recoverable exception (ktor coroutine,
                // IO, …) — log and swallow. Killing the app from a
                // background-thread exception is worse than continuing (the
                // server might still work, the user can retry).
                Log.w(TAG, "Background exception swallowed (app kept alive): ${throwable.message}")
            }
        }

        // Recover upload-queue rows stuck in 'uploading' (e.g. the process was
        // killed mid-upload — the worker only re-dequeues 'pending' rows).
        // Runs once at startup, off the main thread, and is idempotent.
        Thread {
            val store = com.saatiril.andro.backup.UploadQueueStore(this)
            try {
                store.recoverStuckUploads()
            } catch (e: Exception) {
                Log.w(TAG, "Upload queue recovery failed: ${e.message}")
            } finally {
                try { store.close() } catch (_: Exception) {}
            }
        }.start()
    }
}
