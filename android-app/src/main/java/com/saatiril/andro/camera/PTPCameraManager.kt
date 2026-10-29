package com.saatiril.andro.camera

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.TextureView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * PTPCameraManager — third camera engine: direct DSLR control over USB.
 *
 * Implements PTP (Picture Transfer Protocol, ISO 15740 / PIMA 15740) on top of
 * android.hardware.usb — no native library needed. PTP is the same transport
 * every DSLR/mirrorless uses for "PC shooting" (Canon EOS Utility, Nikon WMAU…).
 *
 * Supported flow:
 *   detect PTP camera (USB class 6 subclass 1) → request UsbManager permission →
 *   GetDeviceInfo → OpenSession → [capture] InitiateCapture → ObjectAdded event →
 *   GetObjectInfo → GetObject (full-res JPEG) → downscale ≤2560px → base64 data URL
 *
 * Duck-types the same public API as Camera2Manager / UVCCameraManager so the
 * ViewModels/screens can treat it as "the third engine" (engine id "ptp").
 *
 * NOTE ON PREVIEW: PTP has no standard live-view — framing happens on the
 * camera's own screen. The TextureView shows a dark placeholder with the
 * camera model instead.
 */
class PTPCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "PTPCamera"

        /** Broadcast action for our USB permission request result. */
        private const val ACTION_PTP_PERMISSION = "com.saatiril.andro.PTP_PERMISSION"

        // ── PTP container types (USB transport) ──
        private const val CONTAINER_COMMAND = 1
        private const val CONTAINER_DATA = 2
        private const val CONTAINER_RESPONSE = 3
        private const val CONTAINER_EVENT = 4

        // ── PTP operation codes ──
        private const val OP_GET_DEVICE_INFO = 0x1001
        private const val OP_OPEN_SESSION = 0x1002
        private const val OP_CLOSE_SESSION = 0x1003
        private const val OP_GET_OBJECT_HANDLES = 0x1007
        private const val OP_GET_OBJECT_INFO = 0x1008
        private const val OP_GET_OBJECT = 0x1009
        private const val OP_INITIATE_CAPTURE = 0x100E

        // ── PTP response codes ──
        private const val RESP_OK = 0x2001
        private const val RESP_SESSION_NOT_OPEN = 0x2003
        private const val RESP_OPERATION_NOT_SUPPORTED = 0x2005
        private const val RESP_DEVICE_BUSY = 0x2019

        // ── PTP event codes ──
        private const val EV_OBJECT_ADDED = 0x4002
        private const val EV_CAPTURE_COMPLETE = 0x400D

        // ── PTP object format codes ──
        private const val FMT_UNDEFINED = 0x3000
        private const val FMT_EXIF_JPEG = 0x3001
        private const val FMT_JFIF = 0x3008

        // ── Transport tuning ──
        private const val IO_TIMEOUT_MS = 10_000      // per bulk read/write
        private const val BUSY_RETRIES = 40            // InitiateCapture Device_Busy retries
        private const val BUSY_RETRY_DELAY_MS = 250L
        private const val EVENT_WAIT_MS = 15_000L      // total wait for ObjectAdded
        private const val EVENT_POLL_TIMEOUT_MS = 250  // per interrupt-endpoint read
        private const val MAX_DATA_BYTES = 64 * 1024 * 1024  // 64MB safety cap
        private const val DECODE_MAX_EDGE = 2560       // downscale huge DSLR JPEGs
        private const val USB_POLL_INTERVAL_MS = 2000L // attach/detach polling fallback

        // ── External shutter watch (physical camera button sync) ──
        private const val EXTERNAL_WATCH_POLL_MS = 800L    // watch pass interval (fast mode)
        private const val EXTERNAL_SHOT_COOLDOWN_MS = 2500L // burst/bracket protection
        private const val EXTERNAL_RECENT_MS = 15_000L      // handle-dedupe window

        // ── USB safe mode (kernel-panic workaround, v2 "ultra safe") ──
        // Some phone USB host stacks (notably Unisoc / MediaTek chipsets) panic
        // and reboot the phone during PTP/MTP over USB. Safe mode now removes
        // EVERY known trigger pattern:
        //   1. no interrupt-endpoint reads (events via GetObjectHandles diff)
        //   2. claimInterface WITHOUT force first — force-unbinding an interface
        //      is the classic usbfs panic trigger; force only as a settle-delayed
        //      fallback when the polite claim is refused
        //   3. settle delays: after grant before openDevice, after claim, between
        //      handshake transactions
        //   4. 16KB bulk chunks (mainline MAX_USBFS_BUFFER_SIZE) + micro-pacing
        //      every ~256KB instead of large/continuous URB bursts
        //   5. the external-shutter watch slows from 800ms to 3s polling
        // Every step is persisted BEFORE it runs, so if the driver still panics
        // the phone, the step it died on survives the reboot (lastCrashStep).
        private const val SAFE_MODE_SETTLE_MS = 600L           // after claim, before 1st transaction
        private const val SAFE_MODE_PRE_OPEN_SETTLE_MS = 1000L  // grant → openDevice
        private const val SAFE_MODE_CLAIM_RETRY_SETTLE_MS = 400L // polite claim fail → force
        private const val SAFE_MODE_HANDSHAKE_GAP_MS = 300L     // GetDeviceInfo → OpenSession
        private const val SAFE_CHUNK_BYTES = 16 * 1024          // per-URB read size (safe mode)
        private const val FAST_CHUNK_BYTES = 64 * 1024          // per-URB read size (fast mode)
        private const val SAFE_PACE_EVERY_CHUNKS = 16           // pacing sleep every N chunks (~256KB)
        private const val SAFE_PACE_SLEEP_MS = 4L
        private const val SAFE_WATCH_POLL_MS = 3000L             // safe-mode watch interval
        private const val CRASH_HINT_WINDOW_MS = 30 * 60_000L   // reboot-forensics window
        private const val PREFS_NAME = "ptp_camera_prefs"
        private const val PREF_USB_SAFE_MODE = "usbSafeMode"
        private const val PREF_DIAG_STEP = "usbDiagStep"
        private const val PREF_DIAG_AT = "usbDiagAt"
        private const val PREF_DIAG_UPTIME = "usbDiagUptime"
        private const val PREF_USB_DISABLED = "usbDisabled"
        private const val PREF_CRASH_COUNT = "usbCrashCount"
        private const val PREF_CRASH_BLOCK_UNTIL = "usbCrashBlockUntil"

        /** After a detected USB-driver reboot: pause auto-connect for this
         *  long — the user must explicitly tap "Hubungkan" to retry sooner. */
        private const val CRASH_BLOCK_MS = 15 * 60_000L

        // Shared across manager instances (Operator/Admin each create one) —
        // a single static flow + one-time prefs read keeps every instance in
        // sync, so toggling on one screen also protects the other.
        private val _usbSafeMode = MutableStateFlow(true)
        @Volatile private var safeModePrefLoaded = false

        /** USB kill switch — when ON the PTP engine NEVER touches usbfs (no
         *  permission requests, no openDevice, no polling). For phones whose
         *  kernel panics on ANY PTP/MTP bulk access; photos then arrive via
         *  the FTP engine (WiFi hotspot or USB tethering) instead. */
        private val _usbDisabled = MutableStateFlow(false)
        @Volatile private var usbDisabledPrefLoaded = false

        // Camera VIDs that expose PTP on vendor-specific interfaces
        // (class 0xFF) on older bodies — used only as a detection fallback.
        private val CAMERA_VENDORS = setOf(
            0x04A9, // Canon
            0x04B0, // Nikon
            0x054C, // Sony
            0x04CB, // Fujifilm
            0x07B4, // Olympus
            0x04DA, // Panasonic
            0x05DA, // Ricoh / Pentax
            0x04E8  // Samsung
        )
    }

    // ══════════════════════════════════════════════════════════════
    // Public state — duck-typed to match Camera2Manager/UVCCameraManager
    // ══════════════════════════════════════════════════════════════

    /** True while a PTP session is open with a camera. */
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /** "none" when disconnected, "ptp" when connected. */
    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    /** PTP cameras are always USB cameras. */
    private val _isUSBCamera = MutableStateFlow(true)
    val isUSBCamera: StateFlow<Boolean> = _isUSBCamera.asStateFlow()

    /** Attached PTP cameras as (cameraId, label) — cameraId = "ptp:<usbDeviceId>". */
    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    private val _currentCameraId = MutableStateFlow("")
    val currentCameraIdFlow: StateFlow<String> = _currentCameraId.asStateFlow()

    /** "Manufacturer Model" from GetDeviceInfo (for the preview placeholder). */
    private val _cameraModel = MutableStateFlow("")
    val cameraModel: StateFlow<String> = _cameraModel.asStateFlow()

    /** Last human-readable error (surfaced for diagnostics). */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Photos taken with the camera's OWN physical shutter button while a PTP
     * session is open. The camera stores the shot on its card AND exposes the
     * new object over PTP (ObjectAdded event on the interrupt endpoint /
     * GetObjectHandles diff) — [externalShutterPhotos] watch downloads it and
     * emits the processed base64 data URL here, so a physical shutter press
     * behaves exactly like pressing the app's shutter button.
     */
    private val _externalShutterPhotos = MutableSharedFlow<String>(
        extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val externalShutterPhotos: SharedFlow<String> = _externalShutterPhotos.asSharedFlow()

    /**
     * USB safe mode — workaround for phones whose USB host driver panics and
     * reboots the device during PTP transfers (seen on some Unisoc/MediaTek
     * phones with Sony cameras). While ON (default): no interrupt reads,
     * polite-then-forced interface claim with settle delays, 16KB paced bulk
     * chunks and a 3s watch. See the companion-object block for the full list.
     * Persisted per device; shared by every PTPCameraManager instance.
     */
    val usbSafeMode: StateFlow<Boolean> get() = _usbSafeMode.asStateFlow()

    fun setUsbSafeMode(enabled: Boolean) {
        loadSafeModePref()
        _usbSafeMode.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_USB_SAFE_MODE, enabled).apply()
        Log.i(TAG, "USB safe mode: $enabled" +
                if (enabled) " — no interrupt reads, polite claim, 16KB paced chunks, 3s watch" else " — interrupt endpoint + fast path enabled")
    }

    /** USB kill switch (companion [_usbDisabled]). Turning it back OFF also
     *  clears the crash block + counter — explicit user intent to retry USB. */
    val usbDisabled: StateFlow<Boolean> get() = _usbDisabled.asStateFlow()

    /**
     * True while a PTP camera is attached but auto-connect is paused after a
     * detected USB-driver reboot (the crash-block window). The UI shows a
     * "Hubungkan" button that calls [userRequestedConnect].
     */
    private val _usbConnectBlocked = MutableStateFlow(false)
    val usbConnectBlocked: StateFlow<Boolean> = _usbConnectBlocked.asStateFlow()

    /** Wall-clock end of the crash block (ms epoch; mirrors the pref). */
    @Volatile private var crashBlockUntil = 0L

    fun setUsbDisabled(disabled: Boolean) {
        loadUsbDisabledPref()
        _usbDisabled.value = disabled
        crashBlockUntil = 0L
        _usbConnectBlocked.value = false
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_USB_DISABLED, disabled)
                .putInt(PREF_CRASH_COUNT, 0)
                .putLong(PREF_CRASH_BLOCK_UNTIL, 0L)
                .commit()
        } catch (_: Exception) {}
        Log.i(TAG, "USB kill switch: disabled=$disabled")
        // Re-enabled → look for the camera right away instead of waiting for
        // the next attach event / periodic scan.
        if (!disabled && initialized) {
            mainHandler.post {
                try { scanDeviceList() } catch (e: Exception) {
                    Log.e(TAG, "setUsbDisabled rescan: ${e.message}")
                }
            }
        }
    }

    /** Manual "Hubungkan USB" tap — clears the crash block and reconnects. */
    fun userRequestedConnect() {
        Log.i(TAG, "userRequestedConnect — clearing crash block")
        crashBlockUntil = 0L
        _usbConnectBlocked.value = false
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(PREF_CRASH_BLOCK_UNTIL, 0L)
                .putInt(PREF_CRASH_COUNT, 0)
                .apply()
        } catch (_: Exception) {}
        if (!initialized) return
        mainHandler.post {
            try { scanDeviceList() } catch (e: Exception) {
                Log.e(TAG, "userRequestedConnect: ${e.message}")
            }
        }
    }

    private fun usbDisabledActive(): Boolean {
        loadUsbDisabledPref()
        return _usbDisabled.value
    }

    private fun loadUsbDisabledPref() {
        if (usbDisabledPrefLoaded) return
        synchronized(CAMERA_VENDORS) {
            if (usbDisabledPrefLoaded) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _usbDisabled.value = prefs.getBoolean(PREF_USB_DISABLED, false)
            crashBlockUntil = prefs.getLong(PREF_CRASH_BLOCK_UNTIL, 0L)
            usbDisabledPrefLoaded = true
            Log.i(TAG, "USB kill-switch pref loaded: disabled=${_usbDisabled.value}")
        }
    }

    private fun crashBlocked(): Boolean {
        loadUsbDisabledPref()
        return crashBlockUntil > System.currentTimeMillis()
    }

    /**
     * Live human-readable progress of the USB connection ("membuka perangkat
     * USB…", "handshake OpenSession…", "siap — transfer USB aktif"). Recorded
     * BEFORE each risky kernel operation and persisted, so if the phone's USB
     * driver panics and reboots the phone, the step it died on survives the
     * reboot (surfaced via [lastCrashStep] on the next start).
     */
    private val _usbDiag = MutableStateFlow<String?>(null)
    val usbDiag: StateFlow<String?> = _usbDiag.asStateFlow()

    /**
     * Non-null right after startup when the previous run died in the middle of
     * a USB operation (persisted step + timestamp within the last
     * [CRASH_HINT_WINDOW_MS]) — i.e. the phone most likely REBOOTED during that
     * step. Surfaced in the UI so the user can report the exact failing op.
     */
    private val _lastCrashStep = MutableStateFlow<String?>(null)
    val lastCrashStep: StateFlow<String?> = _lastCrashStep.asStateFlow()

    /** Raw step id → user-facing Indonesian text. */
    private fun diagText(step: String): String = when (step) {
        "izin" -> "meminta izin akses USB"
        "buka" -> "membuka perangkat USB"
        "klaim" -> "mengklaim interface USB"
        "deviceinfo" -> "handshake GetDeviceInfo"
        "opensession" -> "handshake OpenSession"
        "unduh" -> "mengunduh foto"
        "capture" -> "capture dari aplikasi"
        "pemantauan" -> "memantau shutter kamera"
        "siap" -> "siap — transfer USB aktif"
        "locked" -> "USB nonaktif — nyalakan kembali untuk PTP"
        else -> "menunggu kabel USB"
    }

    /**
     * Record the USB pipeline step BEFORE performing it (a panic mid-op leaves
     * the step on disk). Persisted in SharedPreferences — survives the reboot,
     * read back by [detectPriorCrash] on the next start.
     */
    private fun setDiag(step: String) {
        _usbDiag.value = diagText(step)
        try {
            // commit() (synchronous): apply() can lose the very last write when
            // the kernel panics — exactly the write the forensics depends on.
            // Uptime is recorded alongside: if uptime went BACKWARDS on the
            // next start, the phone physically rebooted (deterministic check —
            // no false positives from the user merely restarting the app).
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_DIAG_STEP, step)
                .putLong(PREF_DIAG_AT, System.currentTimeMillis())
                .putLong(PREF_DIAG_UPTIME, SystemClock.elapsedRealtime())
                .commit()
        } catch (_: Exception) {}
    }

    /** After a reboot: if the last persisted step was mid-operation, the phone
     *  PHYSICALLY rebooted (uptime went backwards) and it all happened inside
     *  the forensics window — surface that step for the user to report and
     *  count it toward the USB auto-lock. A mere app restart (same boot) never
     *  counts: the persisted uptime is then <= the current one. */
    private fun detectPriorCrash() {
        try {
            loadUsbDisabledPref()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val step = prefs.getString(PREF_DIAG_STEP, "idle") ?: "idle"
            val at = prefs.getLong(PREF_DIAG_AT, 0L)
            val upAt = prefs.getLong(PREF_DIAG_UPTIME, 0L)
            // Uptime went backwards → the phone itself rebooted since the write.
            val phoneRebooted = upAt > SystemClock.elapsedRealtime()
            val midOp = step.isNotBlank() && step !in setOf("idle", "siap", "locked")
            if (midOp && phoneRebooted && System.currentTimeMillis() - at < CRASH_HINT_WINDOW_MS) {
                // Reboot during a USB op — count it; 2 crashes without a clean
                // shutdown in between auto-lock USB so the phone stays usable
                // (photos keep flowing via the WiFi/USB-tethering FTP engine).
                val count = prefs.getInt(PREF_CRASH_COUNT, 0) + 1
                var note = ""
                if (count >= 2 && !_usbDisabled.value) {
                    setUsbDisabled(true)
                    note = " — USB dimatikan otomatis agar ponsel stabil (pakai Transfer WiFi/FTP)"
                }
                crashBlockUntil = System.currentTimeMillis() + CRASH_BLOCK_MS
                prefs.edit()
                    .putInt(PREF_CRASH_COUNT, count)
                    .putLong(PREF_CRASH_BLOCK_UNTIL, crashBlockUntil)
                    .commit()
                _lastCrashStep.value = diagText(step) + note
                Log.w(TAG, "USB-driver reboot detected: died mid-op '$step' " +
                        "${(System.currentTimeMillis() - at) / 1000}s ago (crash #$count)")
            } else if (midOp && !phoneRebooted) {
                // App restarted normally while the phone stayed up — the mid-op
                // step is stale, not a crash. Clear it so a much later manual
                // reboot can't false-positive off it.
                prefs.edit().putString(PREF_DIAG_STEP, "idle").commit()
                prefs.edit().putInt(PREF_CRASH_COUNT, 0).apply()
            } else if (!midOp) {
                // Clean shutdown since the last crash — reset the counter.
                prefs.edit().putInt(PREF_CRASH_COUNT, 0).apply()
            }
        } catch (_: Exception) {}
    }

    private fun usbSafeModeActive(): Boolean {
        loadSafeModePref()
        return _usbSafeMode.value
    }

    private fun loadSafeModePref() {
        if (safeModePrefLoaded) return
        synchronized(CAMERA_VENDORS) {
            if (safeModePrefLoaded) return
            _usbSafeMode.value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_USB_SAFE_MODE, true)
            safeModePrefLoaded = true
            Log.i(TAG, "USB safe mode pref loaded: ${_usbSafeMode.value}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // USB plumbing
    // ══════════════════════════════════════════════════════════════

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** All PTP-capable devices currently attached (usbDeviceId → UsbDevice). */
    private val devices = ConcurrentHashMap<Int, UsbDevice>()

    /** The active device + its claimed interface / connection / endpoints. */
    @Volatile private var activeDevice: UsbDevice? = null
    @Volatile private var activeDeviceId: Int = -1
    private var connection: UsbDeviceConnection? = null
    private var ptpInterface: UsbInterface? = null
    private var bulkOutEp: UsbEndpoint? = null
    private var bulkInEp: UsbEndpoint? = null
    private var interruptEp: UsbEndpoint? = null

    @Volatile private var sessionOpen = false
    private var transactionId = 0
    private var sessionId = 0
    @Volatile private var supportedOperations = emptySet<Int>()
    private var deviceManufacturer = ""
    private var deviceModel = ""

    /** Response container consumed from an unknown-length data stream. */
    private var pendingResponse: Container? = null

    /** USB permission requests already shown (deviceId → true) — avoids re-popping the system dialog every poll. */
    private val permissionRequested = ConcurrentHashMap<Int, Boolean>()

    /** Reusable read buffer (IO thread only). */
    private val scratch = ByteArray(64 * 1024)

    @Volatile private var isCapturing = false
    @Volatile private var initialized = false

    // ── External shutter watch state (IO thread only) ──
    @Volatile private var externalWatchEnabled = false
    /** Baseline object-handle set for the diff path (IO thread only). */
    private var knownHandles: Set<Int>? = null
    /** Handles recently delivered/seen — dedupes event+diff paths and app captures. */
    private val recentExternalHandles = ArrayDeque<Pair<Int, Long>>()
    private var lastExternalPhotoAt = 0L

    /** Single serialized IO thread — PTP transactions are strictly sequential. */
    private val ioThread: HandlerThread = HandlerThread("PTP-IO").apply { start() }
    private val ioHandler = Handler(ioThread.looper)

    /** USB polling fallback (Android 14 registerReceiver quirks). */
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!initialized) return
            try {
                scanDeviceList()
            } catch (e: Exception) {
                Log.e(TAG, "USB poll failed: ${e.message}")
            }
            mainHandler.postDelayed(this, USB_POLL_INTERVAL_MS)
        }
    }

    private var textureView: TextureView? = null
    private var surfaceReady = false
    private var projectAspectRatio = 4f / 3f

    // ══════════════════════════════════════════════════════════════
    // Container model
    // ══════════════════════════════════════════════════════════════

    private class Container(
        val length: Int,   // total length incl. 12-byte header (may be -1 = unknown)
        val type: Int,      // CONTAINER_*
        val code: Int,     // operation / response / event code
        val txId: Int,
        val payload: ByteArray
    ) {
        val params: IntArray
            get() = if (payload.isEmpty()) IntArray(0)
            else IntArray(payload.size / 4) { i ->
                ByteBuffer.wrap(payload, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }
    }

    // ══════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════

    /**
     * Register USB receivers + enumerate already-attached cameras.
     * Idempotent — safe to call from every screen's init block.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun initCamera() {
        if (initialized) {
            Log.d(TAG, "initCamera: already initialized")
            return
        }
        initialized = true
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "initCamera: PTP engine (direct DSLR control over USB)")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        // PTP device attach/detach (system broadcast)
        val deviceFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(deviceReceiver, deviceFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(deviceReceiver, deviceFilter)
        }

        // USB permission results
        val permFilter = IntentFilter(ACTION_PTP_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(permissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, permFilter)
        }

        // Enumerate whatever is already plugged in
        scanDeviceList()
        // Reboot forensics: if the previous run died mid-USB-op, surface it.
        detectPriorCrash()
        if (devices.isEmpty()) _usbDiag.value = diagText("idle")
        mainHandler.postDelayed(pollRunnable, USB_POLL_INTERVAL_MS)
    }

    /**
     * Scan the current USB device list: add new PTP cameras, drop detached
     * ones, request permission for newcomers, auto-connect when idle.
     */
    private fun scanDeviceList() {
        // Kill switch: never touch usbfs at all — list nothing, connect nothing.
        if (usbDisabledActive()) {
            if (devices.isNotEmpty() || activeDeviceId != -1) {
                devices.clear()
                rebuildAvailableCameras()
                if (activeDeviceId != -1) disconnectCurrent()
            }
            _usbConnectBlocked.value = false
            _usbDiag.value = diagText("locked")
            return
        }
        val blocked = crashBlocked()
        val ptpDevices = usbManager.deviceList.values.filter { isPTPDevice(it) }
        val seenIds = HashSet<Int>()

        for (device in ptpDevices) {
            seenIds.add(device.deviceId)
            if (!devices.containsKey(device.deviceId)) {
                Log.i(TAG, "PTP device attached: ${device.deviceName} (vid=0x${"%04X".format(device.vendorId)})")
                devices[device.deviceId] = device
                rebuildAvailableCameras()
            }
            // Crash-block window: list the camera but hold off — the phone just
            // rebooted mid-USB-op; the user must explicitly tap "Hubungkan".
            if (blocked) continue
            // Ask for permission if we don't have it yet (only once per device)
            if (!usbManager.hasPermission(device) && permissionRequested.putIfAbsent(device.deviceId, true) == null) {
                requestPermission(device)
            } else if (activeDeviceId == -1) {
                connectDevice(device)
            }
        }
        _usbConnectBlocked.value = blocked && devices.isNotEmpty()

        // Drop devices that are no longer attached
        val removed = devices.keys.filter { it !in seenIds }
        for (id in removed) {
            val gone = devices.remove(id)
            permissionRequested.remove(id)
            Log.i(TAG, "PTP device detached: ${gone?.deviceName}")
            if (id == activeDeviceId) {
                disconnectCurrent()
                // Auto-connect the next remaining PTP camera, if any
                nextAttachedCamera()?.let { next ->
                    if (usbManager.hasPermission(next) && !crashBlocked() && !usbDisabledActive()) connectDevice(next)
                }
            }
            rebuildAvailableCameras()
        }
    }

    private fun nextAttachedCamera(): UsbDevice? =
        devices.entries.sortedBy { it.key }.firstOrNull()?.value

    /** Runtime USB device broadcast — attach/detach. */
    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    if (isPTPDevice(device) && !usbDisabledActive()) {
                        Log.i(TAG, "PTP device ATTACHED: ${device.deviceName}")
                        devices[device.deviceId] = device
                        rebuildAvailableCameras()
                        if (!usbManager.hasPermission(device)) {
                            if (permissionRequested.putIfAbsent(device.deviceId, true) == null) {
                                requestPermission(device)
                            }
                        } else if (activeDeviceId == -1 && !crashBlocked()) {
                            connectDevice(device)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    if (isPTPDevice(device)) {
                        Log.i(TAG, "PTP device DETACHED: ${device.deviceName}")
                        devices.remove(device.deviceId)
                        permissionRequested.remove(device.deviceId)
                        if (device.deviceId == activeDeviceId) {
                            disconnectCurrent()
                            nextAttachedCamera()?.let { next ->
                                if (usbManager.hasPermission(next) && !crashBlocked() && !usbDisabledActive()) connectDevice(next)
                            }
                        }
                        rebuildAvailableCameras()
                    }
                }
            }
        }
    }

    /** Result of our UsbManager.requestPermission() call. */
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_PTP_PERMISSION) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "USB permission for ${device.deviceName}: granted=$granted")
            if (granted && activeDeviceId == -1 && !usbDisabledActive() && !crashBlocked()) {
                connectDevice(device)
            }
        }
    }

    private fun requestPermission(device: UsbDevice) {
        try {
            val intent = Intent(ACTION_PTP_PERMISSION).apply {
                putExtra(UsbManager.EXTRA_DEVICE, device)
                setPackage(context.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
            setDiag("izin")
            usbManager.requestPermission(device, pi)
            Log.i(TAG, "Requested USB permission for ${device.deviceName}")
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed: ${e.message}")
            _lastError.value = "Gagal minta izin USB: ${e.message}"
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Device detection
    // ══════════════════════════════════════════════════════════════

    /**
     * A PTP-capable device: either a Still Image class interface
     * (class 6 / subclass 1 — "PIMA 15740"), or a known camera vendor
     * exposing a vendor-specific bulk interface.
     */
    private fun isPTPDevice(device: UsbDevice): Boolean =
        findPtpInterface(device) != null

    private fun findPtpInterface(device: UsbDevice): UsbInterface? {
        // 1) Standard Still Image / PTP class interface
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass == 6 && itf.interfaceSubclass == 1) return itf
        }
        // 2) Vendor-specific bulk interface from a known camera vendor
        if (device.vendorId in CAMERA_VENDORS) {
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                if (itf.interfaceClass == 0xFF && hasBulkInOut(itf)) return itf
            }
        }
        return null
    }

    private fun hasBulkInOut(itf: UsbInterface): Boolean {
        var hasIn = false
        var hasOut = false
        for (i in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true else hasOut = true
            }
        }
        return hasIn && hasOut
    }

    private fun rebuildAvailableCameras() {
        val list = ArrayList<Pair<String, String>>()
        for ((id, device) in devices.entries.sortedBy { it.key }) {
            val label = if (id == activeDeviceId && deviceModel.isNotBlank()) {
                "PTP: $deviceModel"
            } else {
                val product = try {
                    device.productName
                } catch (_: Exception) {
                    null
                }
                "PTP: ${product ?: "USB 0x${"%04X".format(device.vendorId)}"}"
            }
            list.add("ptp:$id" to label)
        }
        _availableCameras.value = list
    }

    // ══════════════════════════════════════════════════════════════
    // Connect / disconnect
    // ══════════════════════════════════════════════════════════════

    /** Open the device, claim its PTP interface, run GetDeviceInfo + OpenSession. */
    private fun connectDevice(device: UsbDevice) {
        val deviceId = device.deviceId
        if (activeDeviceId == deviceId && connection != null) return
        Log.i(TAG, "connectDevice: ${device.deviceName} (id=$deviceId)")
        ioHandler.post {
            try {
                disconnectInternal()

                // Safe mode: let the kernel finish settling the freshly granted
                // device (auto-grant can fire within ~1s of plug) before we
                // touch usbfs at all.
                if (usbSafeModeActive()) {
                    setDiag("buka")
                    Thread.sleep(SAFE_MODE_PRE_OPEN_SETTLE_MS)
                }
                val conn = usbManager.openDevice(device)
                    ?: throw IllegalStateException("openDevice failed")
                val itf = findPtpInterface(device)
                    ?: throw IllegalStateException("no PTP interface")
                if (usbSafeModeActive()) {
                    // Polite claim FIRST — force-unbinding an interface another
                    // client/driver may be mid-handshake with is the classic
                    // usbfs panic trigger. Force only as a settle-delayed
                    // fallback when the polite claim is refused.
                    setDiag("klaim")
                    if (!conn.claimInterface(itf, false)) {
                        Log.w(TAG, "Polite claimInterface refused — retrying with force after settle")
                        Thread.sleep(SAFE_MODE_CLAIM_RETRY_SETTLE_MS)
                        if (!conn.claimInterface(itf, true)) {
                            conn.close()
                            throw IllegalStateException("claimInterface failed")
                        }
                    }
                } else if (!conn.claimInterface(itf, true)) {
                    conn.close()
                    throw IllegalStateException("claimInterface failed")
                }

                var out: UsbEndpoint? = null
                var bulkIn: UsbEndpoint? = null
                var intr: UsbEndpoint? = null
                for (i in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(i)
                    when {
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT -> out = ep
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN -> bulkIn = ep
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN -> intr = ep
                    }
                }
                if (out == null || bulkIn == null) {
                    conn.releaseInterface(itf)
                    conn.close()
                    throw IllegalStateException("missing bulk endpoints")
                }

                connection = conn
                ptpInterface = itf
                bulkOutEp = out
                bulkInEp = bulkIn
                interruptEp = intr
                activeDevice = device
                activeDeviceId = deviceId
                pendingResponse = null
                sessionOpen = false
                transactionId = 0

                // Safe mode: give the kernel a moment after claimInterface —
                // some host stacks panic if a bulk transfer starts instantly.
                if (usbSafeModeActive()) {
                    Log.i(TAG, "Safe mode ON — settling ${SAFE_MODE_SETTLE_MS}ms before handshake")
                    Thread.sleep(SAFE_MODE_SETTLE_MS)
                }

                // Handshake: GetDeviceInfo (pre-session, txId 0) then OpenSession —
                // paced with gaps in safe mode, one step recorded at a time.
                setDiag("deviceinfo")
                val info = getDeviceInfo()
                    ?: throw IllegalStateException("GetDeviceInfo failed")
                deviceManufacturer = info.manufacturer
                deviceModel = info.model
                if (info.operations.isNotEmpty()) supportedOperations = info.operations

                if (usbSafeModeActive()) Thread.sleep(SAFE_MODE_HANDSHAKE_GAP_MS)
                setDiag("opensession")
                if (!openSession()) {
                    throw IllegalStateException("OpenSession failed")
                }
                setDiag("siap")

                Log.i(TAG, "PTP session OPEN: $deviceManufacturer $deviceModel " +
                        "(ops=${supportedOperations.size}, interruptEP=${interruptEp != null})")

                _isConnected.value = true
                _cameraType.value = "ptp"
                _currentCameraId.value = "ptp:$deviceId"
                _lastError.value = null
                rebuildAvailableCameras()
                drawPlaceholder()
            } catch (e: Exception) {
                Log.e(TAG, "connectDevice failed: ${e.message}", e)
                _lastError.value = "Koneksi PTP gagal: ${e.message}"
                disconnectInternal()
                // Try to fall back to another attached camera
                devices.entries.filter { it.key != deviceId }.minByOrNull { it.key }?.let { next ->
                    if (usbManager.hasPermission(next.value)) connectDevice(next.value)
                }
            }
        }
    }

    /** Close session + release the interface (device stays in the list). */
    fun closeCamera() {
        Log.i(TAG, "closeCamera")
        ioHandler.post { disconnectInternal() }
    }

    private fun disconnectCurrent() {
        ioHandler.post { disconnectInternal() }
    }

    /** Runs on the IO thread only. */
    private fun disconnectInternal() {
        val conn = connection
        val itf = ptpInterface
        connection = null
        ptpInterface = null
        bulkOutEp = null
        bulkInEp = null
        interruptEp = null
        sessionOpen = false
        pendingResponse = null
        if (conn != null) {
            try {
                activeDevice?.deviceName?.let { Log.i(TAG, "Closing PTP session for $it") }
            } catch (_: Exception) {}
            try {
                itf?.let { conn.releaseInterface(it) }
            } catch (_: Exception) {}
            try {
                conn.close()
            } catch (_: Exception) {}
            // Clean close — clear the persisted step so a later unrelated
            // reboot is not misreported as a USB crash.
            setDiag("idle")
        }
        activeDevice = null
        activeDeviceId = -1
        _isConnected.value = false
        _cameraType.value = "none"
        _currentCameraId.value = ""
        deviceModel = ""
        deviceManufacturer = ""
        rebuildAvailableCameras()
    }

    /** Full teardown — called from ViewModel.onCleared(). */
    fun destroy() {
        Log.i(TAG, "destroy")
        initialized = false
        externalWatchEnabled = false
        ioHandler.removeCallbacks(watchRunnable)
        mainHandler.removeCallbacks(pollRunnable)
        try { context.unregisterReceiver(deviceReceiver) } catch (_: Exception) {}
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        ioHandler.post {
            disconnectInternal()
            ioThread.quitSafely()
        }
        clearTextureView()
    }

    // ══════════════════════════════════════════════════════════════
    // PTP wire protocol
    // ══════════════════════════════════════════════════════════════

    private fun buildCommand(op: Int, txId: Int, params: IntArray): ByteArray {
        val len = 12 + 4 * params.size
        val bb = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(len)
        bb.putShort(CONTAINER_COMMAND.toShort())  // type: uint16
        bb.putShort(op.toShort())                   // code: uint16
        bb.putInt(txId)
        for (p in params) bb.putInt(p)
        return bb.array()
    }

    /** Per-URB read size — 16KB in safe mode (mainline kernel
     *  MAX_USBFS_BUFFER_SIZE; big URBs are a known trigger on buggy host
     *  drivers), 64KB in fast mode. */
    private fun chunkLimit(): Int =
        if (usbSafeModeActive()) SAFE_CHUNK_BYTES else FAST_CHUNK_BYTES

    /** Read exactly [length] bytes from the bulk IN endpoint into dst at [offset]. */
    private fun readBulk(dst: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val conn = connection ?: return -1
        val ep = bulkInEp ?: return -1
        var total = 0
        var chunks = 0
        while (total < length) {
            val want = minOf(chunkLimit(), length - total)
            val n = conn.bulkTransfer(ep, scratch, want, timeoutMs)
            if (n < 0) return if (total > 0) total else -1
            if (n == 0) break
            System.arraycopy(scratch, 0, dst, offset + total, n)
            total += n
            // Safe mode: micro-pause every ~256KB so buggy host controllers
            // never see an unbroken stream of back-to-back URBs.
            if (usbSafeModeActive() && ++chunks % SAFE_PACE_EVERY_CHUNKS == 0) {
                try { Thread.sleep(SAFE_PACE_SLEEP_MS) } catch (_: InterruptedException) {}
            }
        }
        return total
    }

    /**
     * Read one container. Handles the "unknown length" data containers
     * (length == 0xFFFFFFFF): streams until a Response container arrives on
     * the endpoint, then stashes it in [pendingResponse].
     */
    private fun readContainer(timeoutMs: Int = IO_TIMEOUT_MS): Container? {
        pendingResponse?.let {
            pendingResponse = null
            return it
        }
        val header = ByteArray(12)
        if (readBulk(header, 0, 12, timeoutMs) < 12) return null
        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val length = hb.int
        val type = hb.short.toInt() and 0xFFFF
        val code = hb.short.toInt() and 0xFFFF
        val txId = hb.int

        if (type == CONTAINER_DATA && length == -1) {
            // Unknown-length data phase — stream until the Response container.
            val data = ByteArrayOutputStream()
            val conn = connection ?: return null
            val ep = bulkInEp ?: return null
            var streamChunks = 0
            while (true) {
                val n = conn.bulkTransfer(ep, scratch, chunkLimit(), timeoutMs)
                if (n <= 0) break
                if (n >= 12) {
                    val pb = ByteBuffer.wrap(scratch, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
                    val rLen = pb.int
                    val rType = pb.short.toInt() and 0xFFFF
                    val rCode = pb.short.toInt() and 0xFFFF
                    if (rType == CONTAINER_RESPONSE && rLen in 12..64 && rCode >= 0x2000) {
                        // This chunk IS the response — data ends here.
                        pendingResponse = Container(rLen, rType, rCode, pb.int,
                            scratch.copyOfRange(12, n))
                        Log.d(TAG, "Unknown-length data phase finished: ${data.size()} bytes")
                        return Container(-1, CONTAINER_DATA, code, txId, data.toByteArray())
                    }
                }
                data.write(scratch, 0, n)
                if (usbSafeModeActive() && ++streamChunks % SAFE_PACE_EVERY_CHUNKS == 0) {
                    try { Thread.sleep(SAFE_PACE_SLEEP_MS) } catch (_: InterruptedException) {}
                }
            }
            // Timed out without a response container — return what we have
            return Container(-1, CONTAINER_DATA, code, txId, data.toByteArray())
        }

        if (length < 12 || length > MAX_DATA_BYTES) {
            Log.e(TAG, "Bad container length: $length (type=$type code=0x${"%04X".format(code)})")
            return null
        }
        val payload = if (length > 12) {
            val buf = ByteArray(length - 12)
            if (readBulk(buf, 0, buf.size, timeoutMs) < buf.size) return null
            buf
        } else ByteArray(0)
        return Container(length, type, code, txId, payload)
    }

    /**
     * One request/response transaction. Sends the command, then reads the
     * (optional) data phase followed by the response.
     */
    private fun transact(op: Int, vararg params: Int): Container? {
        val conn = connection ?: return null
        val ep = bulkOutEp ?: return null
        val txId = transactionId++
        val cmd = buildCommand(op, txId, params)
        val sent = conn.bulkTransfer(ep, cmd, cmd.size, IO_TIMEOUT_MS)
        if (sent != cmd.size) {
            Log.e(TAG, "transact 0x${"%04X".format(op)}: send failed ($sent)")
            return null
        }
        // First container: data (if the op returns any) or the response itself.
        val first = readContainer() ?: return null
        if (first.type == CONTAINER_DATA) {
            val resp = readContainer() ?: return null
            if (resp.type != CONTAINER_RESPONSE) {
                Log.e(TAG, "transact 0x${"%04X".format(op)}: expected response, got type=${resp.type}")
                return null
            }
            return resp
        }
        if (first.type == CONTAINER_RESPONSE) return first
        Log.e(TAG, "transact 0x${"%04X".format(op)}: unexpected container type=${first.type}")
        return null
    }

    /** Like [transact] but returns both the data phase and the response. */
    private fun transactWithData(op: Int, vararg params: Int): Pair<ByteArray, Container>? {
        val conn = connection ?: return null
        val ep = bulkOutEp ?: return null
        val txId = transactionId++
        val cmd = buildCommand(op, txId, params)
        if (conn.bulkTransfer(ep, cmd, cmd.size, IO_TIMEOUT_MS) != cmd.size) {
            Log.e(TAG, "transactWithData 0x${"%04X".format(op)}: send failed")
            return null
        }
        val data = readContainer() ?: return null
        if (data.type != CONTAINER_DATA) {
            // No data phase — treat as the response
            if (data.type == CONTAINER_RESPONSE) return Pair(ByteArray(0), data)
            return null
        }
        val resp = readContainer() ?: return null
        if (resp.type != CONTAINER_RESPONSE) return null
        return Pair(data.payload, resp)
    }

    /** Read one event from the interrupt IN endpoint (non-blocking-ish). */
    private fun readEvent(timeoutMs: Int): Container? {
        val conn = connection ?: return null
        val ep = interruptEp ?: return null
        val n = conn.bulkTransfer(ep, scratch, scratch.size, timeoutMs)
        if (n < 12) return null
        val hb = ByteBuffer.wrap(scratch, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val length = hb.int
        val type = hb.short.toInt() and 0xFFFF
        val code = hb.short.toInt() and 0xFFFF
        val txId = hb.int
        val payload = if (n > 12) scratch.copyOfRange(12, n) else ByteArray(0)
        return Container(length, type, code, txId, payload)
    }

    // ── PTP string parsing (length-prefixed UTF-16LE, trailing null) ──

    private fun readPtpString(bb: ByteBuffer): String {
        if (bb.remaining() < 1) return ""
        val numChars = bb.get().toInt() and 0xFF
        if (numChars == 0 || bb.remaining() < numChars * 2) return ""
        val chars = CharArray(numChars)
        for (i in 0 until numChars) chars[i] = bb.char
        // Skip the trailing null terminator (single byte) that real devices send
        if (bb.remaining() >= 1) bb.get()
        return String(chars)
    }

    private class DeviceInfo(val manufacturer: String, val model: String,
                              val operations: Set<Int>)

    private fun readUint16Array(bb: ByteBuffer): IntArray {
        if (bb.remaining() < 4) return IntArray(0)
        val count = bb.int
        if (count < 0 || count > 4096 || bb.remaining() < count * 2) return IntArray(0)
        val out = IntArray(count)
        for (i in 0 until count) out[i] = bb.short.toInt() and 0xFFFF
        return out
    }

    private fun getDeviceInfo(): DeviceInfo? {
        transactionId = 0
        val result = transactWithData(OP_GET_DEVICE_INFO) ?: return null
        val resp = result.second
        if (resp.code != RESP_OK) {
            Log.e(TAG, "GetDeviceInfo failed: 0x${"%04X".format(resp.code)}")
            return null
        }
        return try {
            val bb = ByteBuffer.wrap(result.first).order(ByteOrder.LITTLE_ENDIAN)
            bb.short                       // StandardVersion
            bb.int                         // VendorExtensionID
            bb.short                       // VendorExtensionVersion
            readPtpString(bb)              // VendorExtensionDesc
            bb.short                       // FunctionalMode
            val ops = readUint16Array(bb)  // OperationsSupported
            readUint16Array(bb)            // EventsSupported
            readUint16Array(bb)            // DevicePropsSupported
            readUint16Array(bb)            // CaptureFormats
            readUint16Array(bb)            // ImageFormats
            val manufacturer = readPtpString(bb)
            val model = readPtpString(bb)
            DeviceInfo(manufacturer, model, ops.toSet())
        } catch (e: Exception) {
            Log.e(TAG, "GetDeviceInfo parse failed: ${e.message}")
            DeviceInfo("", "", emptySet())
        }
    }

    private fun openSession(): Boolean {
        transactionId = 0
        val resp = transact(OP_OPEN_SESSION, 1)
        if (resp == null) return false
        if (resp.code == RESP_OK) {
            sessionId = 1
            sessionOpen = true
            return true
        }
        if (resp.code == RESP_SESSION_NOT_OPEN || resp.code == 0x2019) {
            // Already-open session on some cameras — close + retry once
            try { transact(OP_CLOSE_SESSION) } catch (_: Exception) {}
            transactionId = 0
            val retry = transact(OP_OPEN_SESSION, 1)
            if (retry != null && retry.code == RESP_OK) {
                sessionId = 1
                sessionOpen = true
                return true
            }
        }
        Log.e(TAG, "OpenSession failed: 0x${"%04X".format(resp.code)}")
        return false
    }

    // ══════════════════════════════════════════════════════════════
    // Capture
    // ══════════════════════════════════════════════════════════════

    /**
     * Trigger the DSLR shutter and download the resulting photo.
     * Callback receives a JPEG base64 data URL (≤2560px long edge, q95) or
     * null on failure. Runs entirely on the IO thread; callback on main.
     */
    fun capturePhoto(onResult: (String?) -> Unit) {
        if (isCapturing) {
            Log.w(TAG, "capturePhoto: already capturing — ignoring duplicate request")
            mainHandler.post { onResult(null) }
            return
        }
        if (connection == null || !sessionOpen) {
            Log.e(TAG, "capturePhoto: no open PTP session")
            _lastError.value = "Sesi PTP tidak aktif"
            mainHandler.post { onResult(null) }
            return
        }
        if (supportedOperations.isNotEmpty() && OP_INITIATE_CAPTURE !in supportedOperations) {
            Log.e(TAG, "capturePhoto: camera does not support InitiateCapture")
            _lastError.value = "Kamera tidak mendukung remote capture"
            mainHandler.post { onResult(null) }
            return
        }

        isCapturing = true
        ioHandler.post {
            val result = try {
                performCapture()
            } catch (e: Exception) {
                Log.e(TAG, "performCapture failed: ${e.message}", e)
                _lastError.value = "PTP capture gagal: ${e.message}"
                null
            }
            isCapturing = false
            if (connection != null && sessionOpen) setDiag("siap")
            mainHandler.post { onResult(result) }
        }
    }

    private fun performCapture(): String? {
        Log.i(TAG, "performCapture: start ($deviceManufacturer $deviceModel)")
        setDiag("capture")

        // 1) Snapshot existing object handles (for the no-interrupt-EP fallback)
        val preHandles = objectHandles() ?: emptySet()

        // 2) InitiateCapture — retry while the camera reports Device_Busy
        var resp: Container? = null
        var busy = false
        for (attempt in 0 until BUSY_RETRIES) {
            resp = transact(OP_INITIATE_CAPTURE, -0x1, -0x1) // storage=any, format=any
            if (resp == null) {
                _lastError.value = "Komunikasi USB terputus saat capture"
                return null
            }
            if (resp.code == RESP_OK) break
            if (resp.code == RESP_DEVICE_BUSY) {
                busy = true
                Log.w(TAG, "InitiateCapture: Device_Busy (attempt ${attempt + 1}) — retrying")
                Thread.sleep(BUSY_RETRY_DELAY_MS)
                continue
            }
            _lastError.value = "Kamera menolak capture (0x${"%04X".format(resp.code)})"
            Log.e(TAG, "InitiateCapture failed: 0x${"%04X".format(resp.code)}")
            return null
        }
        if (resp == null || (resp.code != RESP_OK && busy)) {
            _lastError.value = "Kamera sibuk (Device_Busy)"
            return null
        }

        // 3) Collect the new object handle(s)
        val addedHandles = ArrayList<Int>()

        // 3a) Some cameras report the RAM-capture object handle directly in
        //     the InitiateCapture response params (storageID, format, handle).
        if (resp.params.size >= 3 && resp.params[2] != 0 && resp.params[2] != -1) {
            Log.i(TAG, "InitiateCapture response carries object handle: ${resp.params[2]}")
            addedHandles.add(resp.params[2])
        }

        // 3b) Interrupt-endpoint events (the standard path — skipped in USB
        //     safe mode, where buggy host drivers can panic on interrupt reads)
        if (addedHandles.isEmpty() && interruptEp != null && !usbSafeModeActive()) {
            val deadline = System.currentTimeMillis() + EVENT_WAIT_MS
            var lastEventAt = 0L
            while (System.currentTimeMillis() < deadline) {
                val ev = readEvent(EVENT_POLL_TIMEOUT_MS)
                if (ev != null) {
                    lastEventAt = System.currentTimeMillis()
                    if (ev.code == EV_OBJECT_ADDED && ev.params.isNotEmpty()) {
                        addedHandles.add(ev.params[0])
                        Log.i(TAG, "Event ObjectAdded: handle=${ev.params[0]}")
                    }
                    if (ev.code == EV_CAPTURE_COMPLETE) {
                        Log.i(TAG, "Event CaptureComplete")
                        if (addedHandles.isNotEmpty()) break
                    }
                }
                // Some cameras never send CaptureComplete — stop after a
                // quiet period once we have at least one object.
                if (addedHandles.isNotEmpty() &&
                    lastEventAt > 0 && System.currentTimeMillis() - lastEventAt > 1500) break
            }
        }

        // 4) Fallback: diff the object handles (no interrupt EP / no events)
        if (addedHandles.isEmpty()) {
            Log.w(TAG, "No ObjectAdded events — falling back to GetObjectHandles diff")
            for (poll in 0 until 3) {
                Thread.sleep(1000)
                val now = objectHandles() ?: emptySet()
                val newOnes = now - preHandles
                if (newOnes.isNotEmpty()) {
                    addedHandles.addAll(newOnes)
                    break
                }
            }
        }
        if (addedHandles.isEmpty()) {
            _lastError.value = "Tidak ada foto baru dari kamera (timeout)"
            Log.e(TAG, "performCapture: no new object after capture")
            return null
        }

        // 5) Pick the best handle: prefer EXIF/JPEG object format
        var bestHandle: Int? = null
        var bestFormat = -1
        for (h in addedHandles) {
            val info = getObjectInfo(h) ?: continue
            if (info.format == FMT_EXIF_JPEG || info.format == FMT_JFIF) {
                if (bestHandle == null || bestFormat != FMT_EXIF_JPEG) {
                    bestHandle = h; bestFormat = info.format
                }
            } else if (bestHandle == null &&
                (info.format == FMT_UNDEFINED || info.format == 0x3800) &&
                info.filename.uppercase().endsWith(".JPG")) {
                bestHandle = h; bestFormat = info.format
            }
        }
        val handle = bestHandle ?: addedHandles.last()
        Log.i(TAG, "Selected object handle=$handle (${addedHandles.size} candidate(s))")

        // Register every candidate handle so the external shutter watch does
        // not re-deliver this same photo when it appears in the handle diff.
        addedHandles.forEach { rememberExternalHandle(it) }

        // 6) Download the photo
        setDiag("unduh")
        val jpeg = getObject(handle)
        if (jpeg == null || jpeg.size < 4 ||
            jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte() || jpeg[2] != 0xFF.toByte()
        ) {
            _lastError.value = "Objek yang diunduh bukan JPEG"
            Log.e(TAG, "getObject: not a JPEG (${jpeg?.size ?: 0} bytes)")
            return null
        }
        Log.i(TAG, "Photo downloaded: ${jpeg.size} bytes")

        // 7) Decode (downscaled) → base64 data URL
        val bitmap = decodeDownscaled(jpeg) ?: run {
            _lastError.value = "Gagal decode JPEG dari kamera"
            return null
        }
        return try {
            CameraCapture.bitmapToBase64(bitmap, 95)
        } finally {
            bitmap.recycle()
        }
    }

    private class ObjectInfo(val format: Int, val compressedSize: Long,
                             val filename: String, val width: Int, val height: Int)

    private fun getObjectInfo(handle: Int): ObjectInfo? {
        val result = transactWithData(OP_GET_OBJECT_INFO, handle) ?: return null
        if (result.second.code != RESP_OK) {
            Log.e(TAG, "GetObjectInfo($handle) failed: 0x${"%04X".format(result.second.code)}")
            return null
        }
        return try {
            val bb = ByteBuffer.wrap(result.first).order(ByteOrder.LITTLE_ENDIAN)
            bb.int                                  // StorageID
            val format = bb.short.toInt() and 0xFFFF // ObjectFormatCode
            bb.short                                // ProtectionStatus
            val size = bb.int.toLong() and 0xFFFFFFFFL // CompressedSize (unsigned)
            bb.short                                // ThumbFormat
            bb.int                                  // ThumbCompressedSize
            bb.int                                  // ThumbWidth
            bb.int                                  // ThumbHeight
            val width = bb.int                       // ImageWidth
            val height = bb.int                      // ImageHeight
            bb.int                                  // ImageBitDepth
            bb.int                                  // ParentObject
            bb.short                                // AssociationType
            bb.int                                  // AssociationDesc
            bb.int                                  // SequenceNumber
            val filename = readPtpString(bb)
            ObjectInfo(format, size, filename, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "GetObjectInfo($handle) parse failed: ${e.message}")
            null
        }
    }

    private fun getObject(handle: Int): ByteArray? {
        val result = transactWithData(OP_GET_OBJECT, handle) ?: return null
        if (result.second.code != RESP_OK) {
            Log.e(TAG, "GetObject($handle) failed: 0x${"%04X".format(result.second.code)}")
            return null
        }
        return result.first
    }

    private fun objectHandles(): Set<Int>? {
        val result = transactWithData(OP_GET_OBJECT_HANDLES, -0x1, 0, -0x1)
            ?: return null
        if (result.second.code != RESP_OK) return null
        val data = result.first
        if (data.size < 4) return emptySet()
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.int
        if (count < 0 || count > 100_000 || data.size < 4 + count * 4) return emptySet()
        val out = HashSet<Int>(count)
        for (i in 0 until count) out.add(bb.int)
        return out
    }

    /** Decode JPEG bytes with inSampleSize so the long edge is ≤ [DECODE_MAX_EDGE]. */
    private fun decodeDownscaled(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > DECODE_MAX_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ══════════════════════════════════════════════════════════
    // External shutter watch — physical camera button sync
    // ══════════════════════════════════════════════════════════

    /**
     * Start watching for photos taken with the camera's own physical shutter
     * button. Each detected shot is downloaded (≤2560px, q95) and emitted on
     * [externalShutterPhotos]. Idempotent.
     */
    fun startExternalShutterWatch() {
        if (externalWatchEnabled) return
        externalWatchEnabled = true
        knownHandles = null  // fresh baseline — never deliver pre-existing objects
        ioHandler.removeCallbacks(watchRunnable)
        ioHandler.post(watchRunnable)
        Log.i(TAG, "External shutter watch STARTED — camera's own shutter button now syncs photos")
    }

    /** Stop the physical-shutter watch. Idempotent. */
    fun stopExternalShutterWatch() {
        if (!externalWatchEnabled) return
        externalWatchEnabled = false
        ioHandler.removeCallbacks(watchRunnable)
        Log.i(TAG, "External shutter watch stopped")
    }

    /** Self-reposting poll on the IO thread — interleaves with transactions. */
    private val watchRunnable = object : Runnable {
        override fun run() {
            if (!externalWatchEnabled) return
            try {
                watchStep()
            } catch (e: Exception) {
                Log.e(TAG, "watchStep: ${e.message}")
            }
            if (externalWatchEnabled) {
                // Safe mode: 3s cadence instead of 800ms — the constant
                // background polling itself is a load the buggy host drivers
                // can choke on over hours of an event.
                ioHandler.postDelayed(this,
                    if (usbSafeModeActive()) SAFE_WATCH_POLL_MS else EXTERNAL_WATCH_POLL_MS)
            }
        }
    }

    /** One watch pass — runs on the IO thread between transactions. */
    private fun watchStep() {
        if (connection == null || !sessionOpen) {
            knownHandles = null
            return
        }
        if (isCapturing) return  // performCapture handles its own events

        // 1) Drain pending interrupt events (short timeouts, small budget).
        //    Skipped in USB safe mode — interrupt reads are the #1 trigger of
        //    USB host kernel panics on some phones; the diff path below covers
        //    event-less cameras just as well.
        if (interruptEp != null && !usbSafeModeActive()) {
            var drained = 0
            while (drained < 4) {
                val ev = readEvent(50) ?: break
                drained++
                if (ev.code == EV_OBJECT_ADDED && ev.params.isNotEmpty()) {
                    onExternalObject(ev.params[0])
                }
            }
        }

        // 2) GetObjectHandles diff — universal fallback (also catches cameras
        //    that don't emit events to an idle host). Persist the step BEFORE
        //    the bulk read: a kernel panic during this 3s poll previously left
        //    "siap" on disk and the crash was invisible to the forensics.
        setDiag("pemantauan")
        val nowHandles = objectHandles() ?: return
        val base = knownHandles
        if (base == null) {
            knownHandles = nowHandles
            return
        }
        val fresh = nowHandles - base
        knownHandles = nowHandles
        if (fresh.isEmpty()) {
            if (connection != null && sessionOpen) setDiag("siap")
            return
        }
        for (h in fresh) onExternalObject(h)
    }

    /**
     * A new object appeared that the app did not request — probably a shot
     * taken with the camera's physical shutter button. Download + emit it.
     */
    private fun onExternalObject(handle: Int) {
        val now = System.currentTimeMillis()

        // Dedupe: this handle was already processed recently (the event path
        // and the diff path can both report the same object)
        if (recentExternalHandles.any { it.first == handle }) return

        // Only JPEG-ish objects (ignore RAW/MP4 siblings of the same shot)
        val info = try { getObjectInfo(handle) } catch (e: Exception) { null }
        if (info == null) { rememberExternalHandle(handle); return }
        val isJpeg = info.format == FMT_EXIF_JPEG || info.format == FMT_JFIF ||
            info.filename.uppercase().endsWith(".JPG") ||
            info.filename.uppercase().endsWith(".JPEG")
        if (!isJpeg) { rememberExternalHandle(handle); return }

        // Cooldown — bursts/brackets produce several objects; deliver the first only
        if (now - lastExternalPhotoAt < EXTERNAL_SHOT_COOLDOWN_MS) {
            rememberExternalHandle(handle)
            Log.d(TAG, "External object $handle skipped (cooldown)")
            return
        }
        rememberExternalHandle(handle)
        lastExternalPhotoAt = now

        Log.i(TAG, "External shutter shot: handle=$handle file=${info.filename} " +
                "(${info.compressedSize} B, fmt=0x${"%04X".format(info.format)})")

        // Download — the file may still be flushing to the card, so retry briefly
        setDiag("unduh")
        var jpeg: ByteArray? = null
        for (attempt in 0 until 3) {
            jpeg = try { getObject(handle) } catch (e: Exception) { null }
            if (jpeg != null && jpeg.size > 4 &&
                jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte() && jpeg[2] == 0xFF.toByte()) break
            Thread.sleep(400)
        }
        if (jpeg == null || jpeg.size < 4 ||
            jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte() || jpeg[2] != 0xFF.toByte()) {
            Log.w(TAG, "External object $handle: not a JPEG (${jpeg?.size ?: 0} bytes) — skipped")
            if (connection != null && sessionOpen) setDiag("siap")
            return
        }

        val bitmap = decodeDownscaled(jpeg) ?: run {
            Log.w(TAG, "External object $handle: decode failed — skipped")
            if (connection != null && sessionOpen) setDiag("siap")
            return
        }
        val dataUrl = try { CameraCapture.bitmapToBase64(bitmap, 95) } finally { bitmap.recycle() }
        Log.i(TAG, "External shutter photo delivered (${dataUrl.length} chars)")
        _externalShutterPhotos.tryEmit(dataUrl)
        if (connection != null && sessionOpen) setDiag("siap")
    }

    /** Record a handle as recently-seen (IO thread only) — prune + bounded size. */
    private fun rememberExternalHandle(handle: Int) {
        val now = System.currentTimeMillis()
        while (recentExternalHandles.isNotEmpty() &&
            (now - recentExternalHandles.first().second > EXTERNAL_RECENT_MS ||
                recentExternalHandles.size > 32)) {
            recentExternalHandles.removeFirst()
        }
        recentExternalHandles.add(handle to now)
    }

    // ══════════════════════════════════════════════════════════
    // Preview placeholder (PTP has no live view)
    // ══════════════════════════════════════════════════════════

    fun setTextureView(tv: TextureView?) {
        textureView = tv
        if (tv != null && tv.isAvailable) {
            surfaceReady = true
            drawPlaceholder()
        }
    }

    fun clearTextureView() {
        textureView = null
        surfaceReady = false
    }

    fun onSurfaceTextureAvailable(width: Int, height: Int) {
        surfaceReady = true
        drawPlaceholder()
    }

    fun onSurfaceTextureSizeChanged(width: Int, height: Int) {
        drawPlaceholder()
    }

    fun onSurfaceTextureDestroyed(): Boolean {
        surfaceReady = false
        return true
    }

    /** Draw the dark "PTP: model" panel onto the preview surface. */
    private fun drawPlaceholder() {
        val tv = textureView ?: return
        if (!tv.isAvailable) return
        try {
            val canvas: Canvas = tv.lockCanvas(null) ?: return
            try {
                val w = canvas.width.toFloat()
                val h = canvas.height.toFloat()
                canvas.drawColor(Color.parseColor("#101014"))

                val model = _cameraModel.value
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                }
                // Camera glyph-ish "PTP" title
                paint.color = Color.parseColor("#D4AF37")
                paint.textSize = minOf(w, h) * 0.14f
                paint.isFakeBoldText = true
                canvas.drawText("PTP", w / 2, h / 2 - minOf(w, h) * 0.10f, paint)

                // Model name
                paint.color = Color.WHITE
                paint.textSize = minOf(w, h) * 0.055f
                paint.isFakeBoldText = false
                canvas.drawText(
                    if (model.isBlank()) "DSLR via USB" else model,
                    w / 2, h / 2 + minOf(w, h) * 0.08f, paint
                )

                // Note
                paint.color = Color.parseColor("#9AA0A6")
                paint.textSize = minOf(w, h) * 0.038f
                canvas.drawText(
                    "Pratinjau tidak tersedia — bidik lewat layar kamera",
                    w / 2, h / 2 + minOf(w, h) * 0.20f, paint
                )
            } finally {
                try { tv.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.d(TAG, "drawPlaceholder: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Camera switching / compat API (duck-typed with the other engines)
    // ══════════════════════════════════════════════════════════════

    /** Switch to a specific PTP camera by id ("ptp:<usbDeviceId>"). */
    fun switchCamera(cameraId: String) {
        if (usbDisabledActive()) {
            _lastError.value = "USB dinonaktifkan — matikan switch \"Nonaktifkan USB\" untuk memakai PTP"
            Log.w(TAG, "switchCamera: blocked — USB kill switch is ON")
            return
        }
        // Explicit user action = consent: clear any crash block first.
        if (crashBlocked()) userRequestedConnect()
        val idPart = cameraId.removePrefix("ptp:").toIntOrNull() ?: run {
            Log.w(TAG, "switchCamera: bad id '$cameraId'")
            return
        }
        val device = devices[idPart] ?: run {
            Log.w(TAG, "switchCamera: device $cameraId not attached")
            return
        }
        if (idPart == activeDeviceId && connection != null) return
        if (usbManager.hasPermission(device)) {
            connectDevice(device)
        } else {
            requestPermission(device)
        }
    }

    fun switchToCamera(cameraId: String) = switchCamera(cameraId)

    /** Re-scan USB (the "Pindai Ulang USB" button). */
    fun forceRescan() {
        Log.i(TAG, "forceRescan")
        permissionRequested.clear()
        scanDeviceList()
    }

    fun getPreviewBitmap(): Bitmap? = null

    fun setProjectAspectRatio(ratio: Float) {
        projectAspectRatio = ratio
        // PTP photos come at the camera's native resolution; the pipeline
        // (CameraCapture.processFrame) crops to the project ratio anyway.
    }

    fun showPreview() {
        drawPlaceholder()
    }

    fun hidePreview() {
        // Nothing to stop — PTP has no continuous preview.
    }

    fun hasCameraPermission(): Boolean = true

    fun refreshCameraList(): List<Pair<String, String>> {
        scanDeviceList()
        return _availableCameras.value
    }

    fun forceSwitchToUSB() {
        forceRescan()
    }

    fun setFilter(preset: String) {
        Log.d(TAG, "setFilter: $preset (filters are applied in post-processing)")
    }

    fun setFrameOverlay(base64: String?) {
        Log.d(TAG, "setFrameOverlay: ${if (base64 != null) "${base64.length} chars" else "null"} (applied in post-processing)")
    }

    fun updateConfig(aspectRatio: Double, filterPreset: String) {
        setFilter(filterPreset)
    }
}
