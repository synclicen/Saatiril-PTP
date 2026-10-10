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
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.TextureView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        mainHandler.postDelayed(pollRunnable, USB_POLL_INTERVAL_MS)
    }

    /**
     * Scan the current USB device list: add new PTP cameras, drop detached
     * ones, request permission for newcomers, auto-connect when idle.
     */
    private fun scanDeviceList() {
        val ptpDevices = usbManager.deviceList.values.filter { isPTPDevice(it) }
        val seenIds = HashSet<Int>()

        for (device in ptpDevices) {
            seenIds.add(device.deviceId)
            if (!devices.containsKey(device.deviceId)) {
                Log.i(TAG, "PTP device attached: ${device.deviceName} (vid=0x${"%04X".format(device.vendorId)})")
                devices[device.deviceId] = device
                rebuildAvailableCameras()
            }
            // Ask for permission if we don't have it yet (only once per device)
            if (!usbManager.hasPermission(device) && permissionRequested.putIfAbsent(device.deviceId, true) == null) {
                requestPermission(device)
            } else if (activeDeviceId == -1) {
                connectDevice(device)
            }
        }

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
                    if (usbManager.hasPermission(next)) connectDevice(next)
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
                    if (isPTPDevice(device)) {
                        Log.i(TAG, "PTP device ATTACHED: ${device.deviceName}")
                        devices[device.deviceId] = device
                        rebuildAvailableCameras()
                        if (!usbManager.hasPermission(device)) {
                            if (permissionRequested.putIfAbsent(device.deviceId, true) == null) {
                                requestPermission(device)
                            }
                        } else if (activeDeviceId == -1) {
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
                                if (usbManager.hasPermission(next)) connectDevice(next)
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
            if (granted && activeDeviceId == -1) {
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

                val conn = usbManager.openDevice(device)
                    ?: throw IllegalStateException("openDevice failed")
                val itf = findPtpInterface(device)
                    ?: throw IllegalStateException("no PTP interface")
                if (!conn.claimInterface(itf, true)) {
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

                // Handshake: GetDeviceInfo (pre-session, txId 0) then OpenSession
                val info = getDeviceInfo()
                    ?: throw IllegalStateException("GetDeviceInfo failed")
                deviceManufacturer = info.manufacturer
                deviceModel = info.model
                if (info.operations.isNotEmpty()) supportedOperations = info.operations

                if (!openSession()) {
                    throw IllegalStateException("OpenSession failed")
                }

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

    /** Read exactly [length] bytes from the bulk IN endpoint into dst at [offset]. */
    private fun readBulk(dst: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val conn = connection ?: return -1
        val ep = bulkInEp ?: return -1
        var total = 0
        while (total < length) {
            val want = minOf(scratch.size, length - total)
            val n = conn.bulkTransfer(ep, scratch, want, timeoutMs)
            if (n < 0) return if (total > 0) total else -1
            if (n == 0) break
            System.arraycopy(scratch, 0, dst, offset + total, n)
            total += n
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
            while (true) {
                val n = conn.bulkTransfer(ep, scratch, scratch.size, timeoutMs)
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
            mainHandler.post { onResult(result) }
        }
    }

    private fun performCapture(): String? {
        Log.i(TAG, "performCapture: start ($deviceManufacturer $deviceModel)")

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

        // 3b) Interrupt-endpoint events (the standard path)
        if (addedHandles.isEmpty() && interruptEp != null) {
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

        // 6) Download the photo
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

    // ══════════════════════════════════════════════════════════════
    // Preview placeholder (PTP has no live view)
    // ══════════════════════════════════════════════════════════════

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
