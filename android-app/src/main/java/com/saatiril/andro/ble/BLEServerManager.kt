package com.saatiril.andro.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * BLE GATT Server — runs on the Admin device (HP/Laptop).
 *
 * Acts as the BLE peripheral that the MC's HP connects to.
 *
 * Exposes 5 characteristics:
 *  - next_student (READ + NOTIFY): current/next student JSON
 *  - trigger (WRITE): MC sends {action, studentId}
 *  - status (NOTIFY): {phase, studentId}
 *  - queue_data (READ + NOTIFY): queue summary + next 10 students
 *  - project_info (READ): {projectName, mode, ratio}
 *
 * The server is managed as a singleton — call [start] to begin advertising
 * and serving, [stop] to shut down.
 */
class BLEServerManager(private val context: Context) {

    companion object {
        private const val TAG = "BLEServer"
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var isRunning = false

    // Connected devices (MC clients) — mutated from GATT binder threads,
    // iterated on the main thread, so it must be a concurrent set (a plain
    // mutableSetOf here risks ConcurrentModificationException).
    private val connectedDevices: MutableSet<BluetoothDevice> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    // Current state data (set by AdminViewModel)
    private var nextStudentJson: String = "{}"
    private var statusJson: String = """{"phase":"standby"}"""
    private var queueDataJson: String = """{"total":0,"pending":0,"done":0,"students":[]}"""
    private var projectInfoJson: String = """{"projectName":"Saatiril","mode":"single"}"""

    // Characteristics (for notifications)
    private var nextStudentChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var queueDataChar: BluetoothGattCharacteristic? = null

    // Callback for trigger actions from MC
    var onTriggerReceived: ((action: String, studentId: String?) -> Unit)? = null

    /** Fires when an MC client connects — the AdminViewModel re-pushes the queue
     *  data + next student so the freshly-connected MC gets the latest. */
    var onClientConnected: (() -> Unit)? = null

    // Callbacks for data pushed from Electron admin (via admin-ble.html)
    var onNextStudentReceived: ((json: String) -> Unit)? = null
    var onQueueDataReceived: ((json: String) -> Unit)? = null
    var onProjectInfoReceived: ((json: String) -> Unit)? = null

    /** Check if Bluetooth is supported and enabled. */
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter!!.isEnabled
    }

    /** Check if the GATT server is running. */
    fun isRunning(): Boolean = isRunning

    /** Check if this device supports BLE peripheral (advertising) mode. */
    fun isAdvertisingSupported(): Boolean {
        return bluetoothAdapter?.bluetoothLeAdvertiser != null
    }

    // ── Diagnostics state (exposed to UI) ──
    @Volatile var advertisingStatus: String = "idle" // idle | advertising | failed
        private set
    @Volatile var advertisingError: String = ""
        private set
    @Volatile var gattServerStatus: String = "idle" // idle | running | failed
        private set
    private var restartJob: kotlinx.coroutines.Job? = null

    /**
     * Start the BLE GATT server + advertising.
     * @return true if started successfully, false otherwise.
     */
    fun start(): Boolean {
        if (isRunning) {
            Log.i(TAG, "Server already running")
            return true
        }

        if (!isBluetoothAvailable()) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            advertisingError = "Bluetooth tidak aktif"
            return false
        }

        if (!isAdvertisingSupported()) {
            Log.e(TAG, "BLE peripheral mode not supported on this device")
            advertisingError = "HP ini tidak support BLE peripheral (bluetoothLeAdvertiser null). Gunakan mode WIFI / LAN."
            return false
        }

        return try {
            if (!startGattServer()) {
                // openGattServer() returned null (e.g. Bluetooth just turned
                // off / too many GATT servers) — advertising a connectable
                // service would be pointless, so do NOT report running.
                gattServerStatus = "failed"
                advertisingStatus = "failed"
                advertisingError = "GATT server gagal dibuka (openGattServer null) — tidak bisa menerima koneksi BLE"
                Log.e(TAG, "Failed to start BLE server: GATT server could not be opened")
                return false
            }
            gattServerStatus = "running"
            startAdvertising()
            isRunning = true
            Log.i(TAG, "BLE Server started — advertising service ${BLEProtocol.SERVICE_UUID}")
            // Start auto-restart job: restart advertising every 3 minutes
            // to prevent some Android devices from silently stopping it.
            startAutoRestart()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE server: ${e.message}", e)
            advertisingError = "Gagal start: ${e.message}"
            false
        }
    }

    /** Restart advertising only (keeps GATT server running). */
    fun restartAdvertising() {
        if (!isRunning) return
        stopAdvertising()
        startAdvertising()
        Log.i(TAG, "BLE advertising restarted manually")
    }

    private fun startAutoRestart() {
        restartJob?.cancel()
        restartJob = scope.launch {
            while (isRunning) {
                delay(180_000) // 3 minutes
                if (isRunning && connectedDevices.isEmpty()) {
                    Log.i(TAG, "Auto-restarting BLE advertising (prevent timeout)")
                    stopAdvertising()
                    delay(500)
                    startAdvertising()
                }
            }
        }
    }

    /** Stop the BLE GATT server + advertising. */
    fun stop() {
        if (!isRunning) return

        restartJob?.cancel()
        restartJob = null

        try {
            stopAdvertising()
            gattServer?.let { server ->
                connectedDevices.forEach { device ->
                    try { server.cancelConnection(device) } catch (_: Exception) {}
                }
                try { server.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping BLE server: ${e.message}")
        }

        gattServer = null
        connectedDevices.clear()
        isRunning = false
        advertisingStatus = "idle"
        gattServerStatus = "idle"
        Log.i(TAG, "BLE Server stopped")
    }

    // ── Data updaters (called by AdminViewModel) ──

    /** Update the next/current student data and notify MC. */
    fun updateNextStudent(studentJson: String) {
        nextStudentJson = studentJson
        notifyCharacteristic(nextStudentChar, studentJson)
    }

    /** Update the status phase and notify MC. */
    fun updateStatus(phase: String, studentId: String? = null) {
        val json = JSONObject().apply {
            put("phase", phase)
            studentId?.let { put("studentId", it) }
        }.toString()
        statusJson = json
        notifyCharacteristic(statusChar, json)
    }

    /**
     * Send a trigger action (PANGGIL/NEXT/RESET) to the connected Electron admin.
     * This writes the action JSON directly to the status characteristic and notifies
     * all subscribed clients. The JSON format is {"action":"PANGGIL","studentId":"..."}.
     *
     * IMPORTANT: Do NOT use updateStatus() for triggers — updateStatus wraps the data
     * in {"phase": ...} format which admin-ble.html cannot parse as a trigger.
     */
    fun sendTrigger(action: String, studentId: String? = null) {
        val json = JSONObject().apply {
            put("action", action)
            studentId?.let { put("studentId", it) }
        }.toString()
        Log.i(TAG, "Sending trigger to Electron: $json")
        statusJson = json
        notifyCharacteristic(statusChar, json)
    }

    /** Update the queue data and notify MC. */
    fun updateQueueData(queueJson: String) {
        queueDataJson = queueJson
        notifyCharacteristic(queueDataChar, queueJson)
    }

    /** Update project info. */
    fun updateProjectInfo(projectName: String, mode: String, ratio: String) {
        projectInfoJson = JSONObject().apply {
            put("projectName", projectName)
            put("mode", mode)
            put("ratio", ratio)
        }.toString()
    }

    // ── GATT Server setup ──

    /**
     * Open the GATT server and register the Saatiril service.
     * @return true if the server was opened successfully, false if
     *         openGattServer() returned null (GATT server NOT running).
     */
    private fun startGattServer(): Boolean {
        val server = bluetoothManager.adapter?.let { adapter ->
            bluetoothManager.openGattServer(context, gattServerCallback)
        }

        if (server == null) {
            Log.e(TAG, "Failed to open GATT server")
            return false
        }

        // Create service
        val service = android.bluetooth.BluetoothGattService(
            java.util.UUID.fromString(BLEProtocol.SERVICE_UUID),
            android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Characteristic: next_student (READ + WRITE + NOTIFY)
        // WRITE permission allows Electron admin-ble.html to push next student data
        // to MC via writeValue(). Without WRITE, the write fails silently.
        nextStudentChar = BluetoothGattCharacteristic(
            java.util.UUID.fromString(BLEProtocol.CHAR_NEXT_STUDENT),
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            value = nextStudentJson.toByteArray(StandardCharsets.UTF_8)
            addDescriptor(createCCCD())
        }

        // Characteristic: trigger (WRITE)
        val triggerChar = BluetoothGattCharacteristic(
            java.util.UUID.fromString(BLEProtocol.CHAR_TRIGGER),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // Characteristic: status (READ + WRITE + NOTIFY)
        statusChar = BluetoothGattCharacteristic(
            java.util.UUID.fromString(BLEProtocol.CHAR_STATUS),
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            value = statusJson.toByteArray(StandardCharsets.UTF_8)
            addDescriptor(createCCCD())
        }

        // Characteristic: queue_data (READ + WRITE + NOTIFY)
        queueDataChar = BluetoothGattCharacteristic(
            java.util.UUID.fromString(BLEProtocol.CHAR_QUEUE_DATA),
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            value = queueDataJson.toByteArray(StandardCharsets.UTF_8)
            addDescriptor(createCCCD())
        }

        // Characteristic: project_info (READ + WRITE)
        val projectInfoChar = BluetoothGattCharacteristic(
            java.util.UUID.fromString(BLEProtocol.CHAR_PROJECT_INFO),
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            value = projectInfoJson.toByteArray(StandardCharsets.UTF_8)
        }

        // Add characteristics to service
        service.addCharacteristic(nextStudentChar)
        service.addCharacteristic(triggerChar)
        service.addCharacteristic(statusChar)
        service.addCharacteristic(queueDataChar)
        service.addCharacteristic(projectInfoChar)

        server.addService(service)
        gattServer = server
        return true
    }

    private fun createCCCD(): BluetoothGattDescriptor {
        return BluetoothGattDescriptor(
            java.util.UUID.fromString(BLEProtocol.DESC_CCCD),
            BluetoothGattDescriptor.PERMISSION_READ or
                BluetoothGattDescriptor.PERMISSION_WRITE
        ).apply {
            value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
    }

    // ── Advertising ──

    // Assigned from the main thread, cleared from the advertising binder
    // thread (onStartFailure) — volatile for cross-thread visibility.
    @Volatile private var advertiseCallback: AdvertiseCallback? = null

    private fun startAdvertising() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BluetoothLeAdvertiser not available — this device does not support BLE peripheral mode")
            advertisingStatus = "failed"
            advertisingError = "HP tidak support BLE peripheral"
            return
        }

        // CRITICAL: Check BLUETOOTH_ADVERTISE permission (Android 12+/API 31+).
        // Without this permission, startAdvertising() throws SecurityException
        // silently — the callback never fires, advertising never starts.
        // This was the ROOT CAUSE of "No compatible devices found" on
        // Redmi Note 13 5G (Android 13) — the permission was missing from
        // the manifest AND not requested at runtime.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val permGranted = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!permGranted) {
                Log.e(TAG, "BLUETOOTH_ADVERTISE permission NOT granted — advertising will fail silently!")
                advertisingStatus = "failed"
                advertisingError = "Permission BLUETOOTH_ADVERTISE belum diberikan. Buka Settings → Apps → Saatiril MC → Permissions → Nearby devices → Allow."
                return
            }
        }

        // Also check Location permission (required for BLE on Android 6-12)
        val locationGranted = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!locationGranted) {
            Log.e(TAG, "ACCESS_FINE_LOCATION permission NOT granted — BLE advertising may fail!")
            advertisingStatus = "failed"
            advertisingError = "Permission Location belum diberikan. Buka Settings → Apps → Saatiril MC → Permissions → Location → Allow."
            return
        }

        Log.i(TAG, "All BLE permissions granted. Starting advertising...")

        // OPTIMIZATION: Use LOW_LATENCY for maximum discoverability.
        // This uses more battery but ensures the device is found within 1-2 seconds.
        // Combined with setTimeout(0) + auto-restart every 3 min, this is reliable.
        // Previous BALANCED mode was too slow for Web Bluetooth to discover on Windows.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0) // 0 = advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // CRITICAL: BLE advertisement packet is limited to 31 bytes.
        // A 128-bit service UUID takes ~16 bytes (with header).
        // Device name can be 10-20 bytes.
        // Including BOTH in the same packet causes DATA_TOO_LARGE error.
        //
        // Solution: put ONLY service UUID in primary advertisement,
        // put ONLY device name in scan response (another 31 bytes).
        //
        // Previous "belt & suspenders" approach (service UUID in BOTH ad + scan response)
        // caused DATA_TOO_LARGE on Redmi Note 13 5G because device name (17 bytes) +
        // txPower (2 bytes) + service UUID (16 bytes) > 31 bytes.
        //
        // Web Bluetooth filter `services: [UUID]` only needs the UUID in ONE of the
        // two packets — primary advertisement is sufficient.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(java.util.UUID.fromString(BLEProtocol.SERVICE_UUID)))
            .build()

        // Scan response: device name ONLY (no service UUID, no tx power).
        // This keeps the scan response under 31 bytes even with long device names.
        // The device name shows in the Web Bluetooth picker so user can identify MC HP.
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            // Do NOT add service UUID here — it would overflow with device name
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "BLE advertising started — service UUID in ad, device name in scan response")
                advertisingStatus = "advertising"
                advertisingError = ""
            }

            override fun onStartFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                    AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                    AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                    else -> "UNKNOWN($errorCode)"
                }
                Log.e(TAG, "BLE advertising FAILED: $reason")
                advertisingStatus = "failed"
                advertisingError = reason
                // Null the failed callback BEFORE scheduling the retry — it
                // was just assigned by startAdvertising(), so the retry's
                // `advertiseCallback == null` condition could never be true
                // without this (dead retry). A fresh startAdvertising() call
                // will assign a new callback.
                advertiseCallback = null
                // Auto-retry after 2 seconds
                scope.launch {
                    delay(2000)
                    if (isRunning && advertiseCallback == null) {
                        Log.i(TAG, "Retrying BLE advertising...")
                        startAdvertising()
                    }
                }
            }
        }

        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun stopAdvertising() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        advertiseCallback?.let { cb ->
            try { advertiser?.stopAdvertising(cb) } catch (_: Exception) {}
        }
        advertiseCallback = null
    }

    // ── GATT Server Callback ──

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices.add(device)
                    Log.i(TAG, "MC connected: ${device.address} (total=${connectedDevices.size})")
                    // Re-push the queue data + next student to the freshly-connected MC.
                    // The queue data was pushed at project-start time (before the MC
                    // connected → notify was a no-op). The MC reads on connect, but
                    // this re-push ensures it gets the LATEST data via notify too.
                    onClientConnected?.invoke()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device)
                    Log.i(TAG, "MC disconnected: ${device.address} (total=${connectedDevices.size})")
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val uuid = characteristic.uuid.toString()
            val data: String = when (uuid) {
                BLEProtocol.CHAR_NEXT_STUDENT -> nextStudentJson
                BLEProtocol.CHAR_STATUS -> statusJson
                BLEProtocol.CHAR_QUEUE_DATA -> queueDataJson
                BLEProtocol.CHAR_PROJECT_INFO -> projectInfoJson
                else -> "{}"
            }

            val bytes = data.toByteArray(StandardCharsets.UTF_8)
            // Handle offset for long reads
            val response = if (offset < bytes.size) bytes.copyOfRange(offset, bytes.size) else ByteArray(0)

            gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, 0, response
            )
            Log.d(TAG, "Characteristic read: $uuid (${response.size} bytes)")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val uuid = characteristic.uuid.toString()

            if (uuid == BLEProtocol.CHAR_TRIGGER) {
                val jsonStr = String(value, StandardCharsets.UTF_8)
                Log.i(TAG, "Trigger received: $jsonStr")

                try {
                    val json = JSONObject(jsonStr)
                    val action = json.optString("action", "")
                    val studentId = if (json.has("studentId")) json.getString("studentId") else null

                    onTriggerReceived?.invoke(action, studentId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse trigger JSON: ${e.message}")
                }
            } else if (uuid == BLEProtocol.CHAR_NEXT_STUDENT) {
                // Admin-ble.html pushes next student data to MC
                val jsonStr = String(value, StandardCharsets.UTF_8)
                Log.i(TAG, "Next student data received from admin: $jsonStr")
                nextStudentJson = jsonStr
                nextStudentChar?.value = value
                // Notify any subscribed MC UI components
                onNextStudentReceived?.invoke(jsonStr)
                // BUG #7 fix: also push the new value to ALL subscribed MC
                // clients (the admin-ble.html write only updated the local
                // cache — connected MC remotes that were already subscribed
                // never got the notification, so their queue/next-student
                // display stayed stale until they manually re-read).
                notifyCharacteristic(nextStudentChar, jsonStr)
            } else if (uuid == BLEProtocol.CHAR_QUEUE_DATA) {
                // Admin-ble.html pushes queue data to MC
                val jsonStr = String(value, StandardCharsets.UTF_8)
                Log.i(TAG, "Queue data received from admin: $jsonStr")
                queueDataJson = jsonStr
                queueDataChar?.value = value
                onQueueDataReceived?.invoke(jsonStr)
                // BUG #7 fix: notify subscribed MC clients (see CHAR_NEXT_STUDENT).
                notifyCharacteristic(queueDataChar, jsonStr)
            } else if (uuid == BLEProtocol.CHAR_PROJECT_INFO) {
                // Admin-ble.html pushes project info to MC
                val jsonStr = String(value, StandardCharsets.UTF_8)
                Log.i(TAG, "Project info received from admin: $jsonStr")
                projectInfoJson = jsonStr
                onProjectInfoReceived?.invoke(jsonStr)
                // BUG #7: project_info has no NOTIFY property (READ+WRITE only,
                // see startGattServer) so there is no characteristic to notify.
                // MC clients read this once on connect — no live update needed.
            } else if (uuid == BLEProtocol.CHAR_STATUS) {
                // Admin-ble.html pushes status update to MC
                val jsonStr = String(value, StandardCharsets.UTF_8)
                Log.i(TAG, "Status data received from admin: $jsonStr")
                statusJson = jsonStr
                statusChar?.value = value
                // BUG #7 fix: notify subscribed MC clients (see CHAR_NEXT_STUDENT).
                notifyCharacteristic(statusChar, jsonStr)
            }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value
                )
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid.toString() == BLEProtocol.DESC_CCCD) {
                // Client enabling/disabling notifications
                Log.i(TAG, "MC ${device.address} subscribed to notifications")
            }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value
                )
            }
        }
    }

    // ── Notification helper ──

    private fun notifyCharacteristic(characteristic: BluetoothGattCharacteristic?, value: String) {
        if (characteristic == null || gattServer == null) return

        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        characteristic.value = bytes

        connectedDevices.forEach { device ->
            try {
                gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to notify ${device.address}: ${e.message}")
            }
        }
    }

    /** Get count of connected MC clients. */
    fun getConnectedClientCount(): Int = connectedDevices.size
}
