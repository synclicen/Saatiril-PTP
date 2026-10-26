package com.saatiril.andro.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * BLE GATT Client — runs on the MC's HP.
 *
 * Scans for and connects to the Admin's BLE GATT Server.
 *
 * Flow:
 *  1. [startScan] — scan for devices advertising the Saatiril service
 *  2. [connect] — connect to the found device
 *  3. Discover services + subscribe to notifications
 *  4. Receive next_student, status, queue_data notifications
 *  5. [sendTrigger] — write trigger commands (PANGGIL, NEXT, RESET, etc.)
 */
class BLEClientManager(private val context: Context) {

    companion object {
        private const val TAG = "BLEClient"
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var isConnected = false
    private var isConnecting = false

    // BUG #5 fix: auto-reconnect state. When the GATT connection drops
    // unexpectedly (out of range, BLE stack hiccup, …) we remember the last
    // device + schedule a reconnect with exponential backoff. The retry
    // stops as soon as close() is called (user explicitly tearing down) —
    // disconnect() alone does NOT stop retries (the user expects auto-recovery
    // after a manual re-connect attempt from the UI).
    @Volatile private var lastConnectedDevice: BluetoothDevice? = null
    @Volatile private var isClosing = false
    @Volatile private var reconnectAttempts = 0
    // BUG #5 fix: reconnectHandler + reconnectRunnable moved to AFTER gattCallback
    // declaration (see below) to avoid Kotlin "Type checking has run into a recursive
    // problem" error — reconnectRunnable references gattCallback which was declared
    // after it.

    /**
     * BUG #5 helper: schedule a reconnect attempt with exponential backoff
     * (1s, 2s, 4s, 8s, capped at 15s). No-op if close() was called.
     */
    private fun scheduleReconnect() {
        if (isClosing) return
        val delayMs = (1L shl reconnectAttempts.coerceAtMost(4)) * 1000L  // 1,2,4,8,16s
        val actualDelay = delayMs.coerceAtMost(15_000L)
        reconnectHandler.removeCallbacks(reconnectRunnable)
        reconnectHandler.postDelayed(reconnectRunnable, actualDelay)
        Log.i(TAG, "BUG#5: scheduled reconnect in ${actualDelay}ms (attempt #${reconnectAttempts + 1})")
    }

    // Serialized GATT setup operations. Android's BLE stack allows only ONE
    // in-flight GATT operation — issuing writeDescriptor/readCharacteristic
    // calls back-to-back makes many stacks (notably pre-Android-13) silently
    // drop all but the first, so notifications/initial data never arrived.
    // Each queued op runs only after the previous one's callback fires.
    // (All access happens on the GATT callback binder thread.)
    private val pendingGattOps = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var gattOpInFlight = false

    // Characteristics (for reading/writing after discovery)
    private var nextStudentChar: BluetoothGattCharacteristic? = null
    private var triggerChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var queueDataChar: BluetoothGattCharacteristic? = null
    private var projectInfoChar: BluetoothGattCharacteristic? = null

    // Callbacks for UI
    var onConnectionStateChanged: ((connected: Boolean) -> Unit)? = null
    var onNextStudentReceived: ((json: String) -> Unit)? = null
    var onStatusReceived: ((json: String) -> Unit)? = null
    var onQueueDataReceived: ((json: String) -> Unit)? = null
    var onProjectInfoReceived: ((json: String) -> Unit)? = null
    var onDeviceFound: ((device: BluetoothDevice) -> Unit)? = null

    /** Check if Bluetooth is available and enabled. */
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter!!.isEnabled
    }

    /** Check if connected to Admin. */
    fun isConnected(): Boolean = isConnected

    // ── Scanning ──

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.i(TAG, "Found Saatiril device: ${device.name} (${device.address})")
            onDeviceFound?.invoke(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
        }
    }

    /**
     * Start scanning for devices advertising the Saatiril BLE service.
     * Results are delivered via [onDeviceFound].
     */
    fun startScan(): Boolean {
        if (isScanning) return true
        if (!isBluetoothAvailable()) {
            Log.e(TAG, "Bluetooth not available")
            return false
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner not available")
            return false
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(java.util.UUID.fromString(BLEProtocol.SERVICE_UUID)))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            Log.i(TAG, "BLE scan started")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan: ${e.message}")
            return false
        }
    }

    /** Stop scanning. */
    fun stopScan() {
        if (!isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        isScanning = false
        Log.i(TAG, "BLE scan stopped")
    }

    // ── Connection ──

    /**
     * Connect to a discovered device.
     * @param device The BluetoothDevice to connect to.
     */
    fun connect(device: BluetoothDevice): Boolean {
        if (!isBluetoothAvailable()) return false

        // Guard double-connect: if we're already connected (or mid-connect) to
        // this same device, don't stack a second GATT client on top of it.
        if ((isConnected || isConnecting) && bluetoothGatt?.device?.address == device.address) {
            Log.i(TAG, "Already connected/connecting to ${device.address} — ignoring duplicate connect")
            return true
        }

        stopScan()

        // Close any previous GATT client before opening a new one — a fresh
        // connectGatt() without closing the old instance leaks it and slowly
        // exhausts Android's limited BLE connection slots.
        try { bluetoothGatt?.close() } catch (_: Exception) {}
        bluetoothGatt = null

        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            Log.e(TAG, "connectGatt returned null for ${device.address}")
            return false
        }
        bluetoothGatt = gatt
        isConnecting = true
        // BUG #5: remember this device for auto-reconnect. Reset retry counter
        // — this is a fresh user-initiated connect. Clear the closing flag
        // (the user explicitly (re)connected, so auto-reconnect is wanted).
        lastConnectedDevice = device
        isClosing = false
        reconnectAttempts = 0
        reconnectHandler.removeCallbacks(reconnectRunnable)
        Log.i(TAG, "Connecting to ${device.address}...")
        return true
    }

    /** Disconnect from the Admin device. */
    fun disconnect() {
        // BUG #5: disconnect() does NOT stop auto-reconnect — the user may be
        // initiating a manual reconnect flow that expects the auto-retry to
        // keep working if the manual attempt fails. Only close() stops the
        // retry loop (explicit teardown).
        bluetoothGatt?.let { gatt ->
            try { gatt.disconnect() } catch (_: Exception) {}
        }
    }

    /** Close the GATT client completely. */
    fun close() {
        // BUG #5: stop the auto-reconnect loop — this is an explicit teardown.
        isClosing = true
        reconnectHandler.removeCallbacks(reconnectRunnable)
        lastConnectedDevice = null
        reconnectAttempts = 0
        disconnect()
        try { bluetoothGatt?.close() } catch (_: Exception) {}
        bluetoothGatt = null
        isConnected = false
        isConnecting = false
        pendingGattOps.clear()
        gattOpInFlight = false
    }

    // ── GATT Callback ──

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    isConnecting = false
                    // BUG #5: connection (re)established — reset retry counter
                    // so the next drop starts at 1s backoff again.
                    reconnectAttempts = 0
                    reconnectHandler.removeCallbacks(reconnectRunnable)
                    Log.i(TAG, "Connected to Admin — requesting larger MTU...")
                    // Request a larger MTU (517) BEFORE service discovery + reads.
                    // The queue data JSON (~650-850B for 10 students) exceeds the
                    // default 23-byte MTU (20B payload) → read truncates → JSON
                    // parse fails → the queue list stays empty. With MTU 517, the
                    // reads get the full payload (or long-read in 2 chunks).
                    val ok = gatt.requestMtu(517)
                    if (!ok) {
                        Log.w(TAG, "requestMtu(517) failed — falling back to discoverServices with default MTU")
                        gatt.discoverServices()
                    }
                    // If requestMtu succeeds, onMtuChanged fires → discoverServices there.
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    isConnecting = false
                    // Drop any queued GATT setup ops — they belong to the dead
                    // connection and would stall/fail on a fresh one.
                    pendingGattOps.clear()
                    gattOpInFlight = false
                    connectedDevicesClear()
                    Log.i(TAG, "Disconnected from Admin")
                    onConnectionStateChanged?.invoke(false)
                    // BUG #5: schedule an auto-reconnect (exponential backoff)
                    // unless close() was the cause of this disconnect. close()
                    // sets isClosing=true BEFORE calling disconnect() — that
                    // flag is the explicit-teardown signal.
                    if (!isClosing && lastConnectedDevice != null) {
                        try { gatt.close() } catch (_: Exception) {}  // release GATT resources for the retry
                        bluetoothGatt = null
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu (status=$status) — discovering services...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: status=$status")
                return
            }

            val service = gatt.getService(java.util.UUID.fromString(BLEProtocol.SERVICE_UUID))
            if (service == null) {
                Log.e(TAG, "Saatiril service not found")
                return
            }

            // Get characteristics
            nextStudentChar = service.getCharacteristic(java.util.UUID.fromString(BLEProtocol.CHAR_NEXT_STUDENT))
            triggerChar = service.getCharacteristic(java.util.UUID.fromString(BLEProtocol.CHAR_TRIGGER))
            statusChar = service.getCharacteristic(java.util.UUID.fromString(BLEProtocol.CHAR_STATUS))
            queueDataChar = service.getCharacteristic(java.util.UUID.fromString(BLEProtocol.CHAR_QUEUE_DATA))
            projectInfoChar = service.getCharacteristic(java.util.UUID.fromString(BLEProtocol.CHAR_PROJECT_INFO))

            // Queue ALL setup operations and run them STRICTLY one at a time:
            // 3 CCCD subscribes, then the initial reads. Firing them back-to-
            // back makes many Android stacks (notably pre-Android-13) drop all
            // but the first operation silently. Each op runs only after the
            // previous one's onDescriptorWrite / onCharacteristicRead callback
            // fires — see [runNextGattOp] / [onGattOpFinished].
            pendingGattOps.clear()
            gattOpInFlight = false
            enqueueSubscribeOp(nextStudentChar)
            enqueueSubscribeOp(statusChar)
            enqueueSubscribeOp(queueDataChar)
            enqueueReadOp(projectInfoChar)
            enqueueReadOp(queueDataChar)
            enqueueReadOp(nextStudentChar)
            runNextGattOp(gatt)

            Log.i(TAG, "Services discovered — queued notification subscribes + initial reads")
            onConnectionStateChanged?.invoke(true)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Descriptor write failed for ${descriptor.uuid}: status=$status")
            }
            // Advance the serialized setup queue (a failed write must not stall it).
            onGattOpFinished(gatt)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // Advance the serialized setup queue FIRST — a failed/null read
            // must not stall the remaining queued operations.
            onGattOpFinished(gatt)

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Read failed for ${characteristic.uuid}: status=$status")
                return
            }

            // characteristic.value is platform-typed (ByteArray!) and CAN be
            // null — guard before String(...) or this throws an NPE.
            val bytes = characteristic.value
            if (bytes == null) {
                Log.w(TAG, "Read returned null value for ${characteristic.uuid}")
                return
            }
            val value = String(bytes, StandardCharsets.UTF_8)

            val uuid = characteristic.uuid.toString()

            when (uuid) {
                BLEProtocol.CHAR_NEXT_STUDENT -> onNextStudentReceived?.invoke(value)
                BLEProtocol.CHAR_STATUS -> onStatusReceived?.invoke(value)
                BLEProtocol.CHAR_QUEUE_DATA -> onQueueDataReceived?.invoke(value)
                BLEProtocol.CHAR_PROJECT_INFO -> onProjectInfoReceived?.invoke(value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // characteristic.value can be null — guard against NPE.
            val bytes = characteristic.value ?: return
            val uuid = characteristic.uuid.toString()
            val value = String(bytes, StandardCharsets.UTF_8)

            when (uuid) {
                BLEProtocol.CHAR_NEXT_STUDENT -> {
                    Log.d(TAG, "Next student update: $value")
                    onNextStudentReceived?.invoke(value)
                }
                BLEProtocol.CHAR_STATUS -> {
                    Log.d(TAG, "Status update: $value")
                    onStatusReceived?.invoke(value)
                }
                BLEProtocol.CHAR_QUEUE_DATA -> {
                    Log.d(TAG, "Queue data update: $value")
                    onQueueDataReceived?.invoke(value)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Trigger sent successfully")
            } else {
                Log.e(TAG, "Trigger write failed: status=$status")
            }
            // BUG #11 fix: advance the GATT op queue so the next queued op
            // (e.g. another trigger, or a queued read/subscribe) can run.
            // Without this, a failed trigger write would stall the queue
            // forever and all subsequent triggers would be silently dropped.
            onGattOpFinished(gatt)
        }
    }

    // BUG #5 fix: reconnectHandler + reconnectRunnable declared AFTER gattCallback
    // to avoid Kotlin recursive type checking error (reconnectRunnable references
    // gattCallback in connectGatt call).
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        val device = lastConnectedDevice ?: return@Runnable
        if (isClosing || isConnected || isConnecting) return@Runnable
        Log.i(TAG, "BUG#5: auto-reconnect attempt #${reconnectAttempts + 1} (device=${device.address})")
        reconnectAttempts++
        try {
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt != null) {
                try { bluetoothGatt?.close() } catch (_: Exception) {}
                bluetoothGatt = gatt
                isConnecting = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "BUG#5: reconnect connectGatt failed: ${e.message}")
        }
    }

    // ── Write trigger ──

    /**
     * Send a trigger command to the Admin.
     * @param action One of [BLEProtocol.Action] constants (PANGGIL, NEXT, RESET, etc.)
     * @param studentId Optional student ID for the action.
     * @return true if the write was initiated, false if not connected.
     */
    fun sendTrigger(action: String, studentId: String? = null): Boolean {
        if (!isConnected || triggerChar == null) {
            Log.w(TAG, "Cannot send trigger — not connected")
            return false
        }

        val json = JSONObject().apply {
            put("action", action)
            studentId?.let { put("studentId", it) }
        }.toString()
        val bytes = json.toByteArray(StandardCharsets.UTF_8)

        // BUG #11 fix: route through the pendingGattOps queue. Android's BLE
        // stack allows only ONE in-flight GATT operation at a time — calling
        // writeCharacteristic() directly here while another op (descriptor
        // write, characteristic read, …) is in flight silently drops the
        // trigger write on many stacks (notably pre-Android-13). The setup
        // subscribes/reads already use this queue; triggers were the lone
        // exception. Now they line up behind any in-flight op too.
        val char = triggerChar!!
        char.value = bytes
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val gatt = bluetoothGatt
        if (gatt == null) {
            Log.w(TAG, "sendTrigger: bluetoothGatt is null — cannot queue")
            return false
        }
        pendingGattOps.addLast { g -> g.writeCharacteristic(char) }
        // If nothing is in flight, kick the queue now. If an op is in flight,
        // onGattOpFinished will pick this one up when the current op completes.
        runNextGattOp(gatt)
        Log.i(TAG, "Sending trigger (queued): $json")
        return true
    }

    // ── Helpers ──

    /** Enqueue a CCCD-notification subscribe for [char] (no-op if missing). */
    private fun enqueueSubscribeOp(char: BluetoothGattCharacteristic?) {
        if (char == null) return
        pendingGattOps.addLast { gatt -> subscribeToCharacteristic(gatt, char) }
    }

    /** Enqueue an initial read of [char] (no-op if missing). */
    private fun enqueueReadOp(char: BluetoothGattCharacteristic?) {
        if (char == null) return
        pendingGattOps.addLast { gatt -> gatt.readCharacteristic(char) }
    }

    /** Run the next queued GATT operation (if none is in flight). */
    private fun runNextGattOp(gatt: BluetoothGatt) {
        if (gattOpInFlight) return
        val op = pendingGattOps.removeFirstOrNull() ?: return
        gattOpInFlight = op(gatt)
        if (!gattOpInFlight) {
            // Operation had nothing in flight (e.g. missing CCCD) — move on
            // to the next queued operation immediately.
            runNextGattOp(gatt)
        }
    }

    /** Called from onDescriptorWrite / onCharacteristicRead to advance the queue. */
    private fun onGattOpFinished(gatt: BluetoothGatt) {
        gattOpInFlight = false
        runNextGattOp(gatt)
    }

    /**
     * Enable local notifications + write the CCCD descriptor.
     * @return true if the descriptor write was actually initiated.
     */
    private fun subscribeToCharacteristic(gatt: BluetoothGatt, char: BluetoothGattCharacteristic?): Boolean {
        if (char == null) return false
        gatt.setCharacteristicNotification(char, true)

        val cccd = char.getDescriptor(java.util.UUID.fromString(BLEProtocol.DESC_CCCD))
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            return gatt.writeDescriptor(cccd)
        }
        return false // no CCCD on this characteristic — nothing was put in flight
    }

    private fun connectedDevicesClear() {
        nextStudentChar = null
        triggerChar = null
        statusChar = null
        queueDataChar = null
        projectInfoChar = null
    }
}
