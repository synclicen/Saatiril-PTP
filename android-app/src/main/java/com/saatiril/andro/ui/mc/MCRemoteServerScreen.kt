package com.saatiril.andro.ui.mc

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import com.saatiril.andro.ble.BLEClientManager
import com.saatiril.andro.ble.BLEServerManager
import com.saatiril.andro.ble.BLEProtocol
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.SocketManager
import org.json.JSONObject

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val GREEN = Color(0xFF4ade80)
private val RED = Color(0xFFef4444)
private val CYAN = Color(0xFF06b6d4)
private val AMBER = Color(0xFFfbbf24)
private val BLUE = Color(0xFF3b82f6)

/**
 * MC Remote Server Screen — MC HP acts as BLE GATT Server.
 *
 * Used when Admin = Electron (Laptop). The Electron app connects
 * to this MC HP via Web Bluetooth API (as BLE Client/Central).
 *
 * Flow:
 *  1. MC HP advertises "Saatiril MC" BLE service
 *  2. Electron Admin scans + connects via Web Bluetooth
 *  3. Electron sends queue_data + next_student → MC displays
 *  4. MC presses PANGGIL → BLE notify Electron
 *  5. Electron receives trigger → callStudent → photo → notify MC "done"
 *
 * This is the REVERSED architecture from MCRemoteScreen:
 *  - MCRemoteScreen: MC = Client, Admin APK = Server
 *  - MCRemoteServerScreen: MC = Server, Electron = Client
 */
@Composable
fun MCRemoteServerScreen(adminViewModel: AdminViewModel) {
    val context = LocalContext.current
    val bleServer = remember { BLEServerManager(context) }

    // WiFi socket.io fallback for trigger delivery.
    // BLE notifications are unreliable on many Android devices — the trigger
    // from MC to Electron may not arrive. Socket.io over WiFi is 100% reliable.
    // MC connects to the Electron server (same IP that admin-ble uses) and
    // sends triggers via socket.io 'lan-message' event.
    val socketManager = remember { SocketManager() }
    var socketConnected by remember { mutableStateOf(false) }
    var serverIp by remember { mutableStateOf("") }

    // Show IP input dialog for WiFi connection
    var showIpDialog by remember { mutableStateOf(false) }
    var ipInput by remember { mutableStateOf("") }

    var isAdvertising by remember { mutableStateOf(false) }
    var connectedClientCount by remember { mutableStateOf(0) }
    var nextStudent by remember { mutableStateOf<JSONObject?>(null) }
    var statusPhase by remember { mutableStateOf("standby") }
    var queueData by remember { mutableStateOf<JSONObject?>(null) }
    var projectInfo by remember { mutableStateOf<JSONObject?>(null) }
    // Diagnostics state
    var adStatus by remember { mutableStateOf("idle") }
    var adError by remember { mutableStateOf("") }
    var bleSupported by remember { mutableStateOf(true) }

    // Bluetooth permission
    var hasBtPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> hasBtPermission = results.values.all { it } }

    LaunchedEffect(Unit) {
        if (!hasBtPermission) {
            btPermissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        }
    }

    // Set up BLE server callbacks
    DisposableEffect(Unit) {
        // BUG #16 fix: Android allows only ONE GATT server per process. If
        // AdminViewModel.bleServerManager (the admin-side BLE server, started
        // when the admin creates a project for MC remotes to connect to) is
        // still running, opening a SECOND BLEServerManager here will fail
        // silently (openGattServer returns null) — the MC's "Start
        // advertising" button shows "failed" with no useful error. Stop the
        // AdminViewModel's server now (it'll be restarted by
        // retryBleAdvertising() / startBLEServer() when the user navigates
        // back to the Admin dashboard and creates/resumes a project).
        var adminBleWasRunning = false
        try {
            if (adminViewModel.bleServerManager.isRunning()) {
                adminBleWasRunning = true
                adminViewModel.bleServerManager.stop()
                android.util.Log.i("MCRemoteServer", "BUG#16: stopped AdminViewModel.bleServerManager to free GATT server slot")
            }
        } catch (e: Exception) {
            android.util.Log.w("MCRemoteServer", "BUG#16: failed to stop AdminViewModel.bleServerManager: ${e.message}")
        }

        // CRITICAL: Receive project data pushed from Electron admin via BLE.
        // Without these callbacks, MC shows "Tidak ada mahasiswa" because
        // the data written by admin-ble.html is received but never processed.
        // IMPORTANT: BLE callbacks run on a BACKGROUND THREAD. Compose state
        // updates must happen on the MAIN THREAD to trigger recomposition.
        // Without Dispatchers.Main, the state is set but UI never updates.
        bleServer.onProjectInfoReceived = { json ->
            try {
                val obj = JSONObject(json)
                android.os.Handler(android.os.Looper.getMainLooper()).post { projectInfo = obj }
            } catch (_: Exception) {}
        }
        bleServer.onQueueDataReceived = { json ->
            try {
                val obj = JSONObject(json)
                android.os.Handler(android.os.Looper.getMainLooper()).post { queueData = obj }
            } catch (_: Exception) {}
        }
        bleServer.onNextStudentReceived = { json ->
            try {
                val obj = JSONObject(json)
                android.os.Handler(android.os.Looper.getMainLooper()).post { nextStudent = if (obj.has("id")) obj else null }
            } catch (_: Exception) {}
        }
        bleServer.onTriggerReceived = { action, studentId ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (action == "STATUS_DONE") {
                    statusPhase = BLEProtocol.Phase.DONE
                } else if (action == "STATUS_READY") {
                    statusPhase = BLEProtocol.Phase.READY
                }
            }
        }

        onDispose {
            bleServer.stop()
            // BUG #16: if we stopped AdminViewModel's BLE server on entry,
            // restart it on exit so the admin-side MC-remote flow still works
            // when the user navigates back to the Admin dashboard. The
            // restart is best-effort — if the project is gone (user left the
            // Admin role entirely), retryBleAdvertising() is a no-op.
            if (adminBleWasRunning) {
                try {
                    adminViewModel.retryBleAdvertising()
                } catch (e: Exception) {
                    android.util.Log.w("MCRemoteServer", "BUG#16: failed to restart AdminViewModel.bleServerManager: ${e.message}")
                }
            }
            // Tear down the WiFi fallback socket too — otherwise its reconnect
            // loop keeps firing forever after leaving this screen. Both
            // disconnect() and destroy() are idempotent.
            socketManager.onConnected = null
            socketManager.onDisconnected = null
            socketManager.disconnect()
            socketManager.destroy()
        }
    }

    // Start advertising automatically when permission granted
    LaunchedEffect(hasBtPermission) {
        if (hasBtPermission && !isAdvertising && bleServer.isBluetoothAvailable()) {
            bleSupported = bleServer.isAdvertisingSupported()
            val started = bleServer.start()
            isAdvertising = started
            if (!started) {
                // Surface WHY start() failed (e.g. GATT server could not be
                // opened) — without this the failure was silently ignored.
                adStatus = bleServer.advertisingStatus
                adError = bleServer.advertisingError
            }
        }
    }

    // Poll connected clients count + diagnostics
    LaunchedEffect(isAdvertising) {
        while (isAdvertising) {
            connectedClientCount = bleServer.getConnectedClientCount()
            adStatus = bleServer.advertisingStatus
            adError = bleServer.advertisingError
            kotlinx.coroutines.delay(2000)
        }
    }

    // WiFi socket.io connection for reliable trigger delivery
    fun connectWifiSocket(ip: String) {
        if (ip.isBlank()) return
        val url = "http://$ip:3003"
        socketManager.connect(url, "mc-ble-server", 1, null)
        socketManager.onConnected = {
            socketConnected = true
            serverIp = ip
        }
        socketManager.onDisconnected = {
            socketConnected = false
        }
    }

    // Send trigger via WiFi socket.io (guaranteed delivery, bypass BLE)
    fun sendTriggerViaWifi(action: String, studentId: String?) {
        if (socketConnected) {
            try {
                val json = JSONObject().apply {
                    put("action", action)
                    studentId?.let { put("studentId", it) }
                }
                socketManager.emitLanMessage("BLE_TRIGGER", json)
                android.util.Log.i("MCWifi", "Trigger sent via WiFi: $action ($studentId)")
            } catch (e: Exception) {
                android.util.Log.e("MCWifi", "WiFi trigger failed: ${e.message}")
            }
        } else {
            android.util.Log.w("MCWifi", "WiFi socket not connected — trigger only via BLE")
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(BG)
    ) {
        // ── Header ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val connColor = if (connectedClientCount > 0) GREEN else if (isAdvertising) AMBER else RED
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(connColor))
            Text("MC BLE SERVER", style = TextStyle(color = BLUE, fontSize = 14.sp, fontWeight = FontWeight.Black))
            Spacer(Modifier.weight(1f))
            if (connectedClientCount > 0) {
                Text("$connectedClientCount terhubung", style = TextStyle(color = GREEN, fontSize = 10.sp))
            }
            Card(
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                    .clickable {
                        bleServer.stop()
                        adminViewModel.backToRoleSelect()
                    },
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(8.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup", tint = RED, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (!hasBtPermission) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null, tint = GOLD, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("Izin Bluetooth Diperlukan", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(8.dp))
                Text(
                    "4 izin diperlukan untuk BLE SERVER:\n" +
                    "• Bluetooth SCAN — mencari perangkat\n" +
                    "• Bluetooth CONNECT — koneksi GATT\n" +
                    "• Bluetooth ADVERTISE — advertising (WAJIB!)\n" +
                    "• Location — untuk BLE di Android",
                    style = TextStyle(color = MUTED, fontSize = 11.sp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        btPermissionLauncher.launch(arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Berikan Izin Bluetooth", color = BG, fontWeight = FontWeight.Bold) }
            }
            return
        }

        if (!bleServer.isBluetoothAvailable()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.BluetoothDisabled, contentDescription = null, tint = RED, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("Bluetooth tidak aktif", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(8.dp))
                Text("Aktifkan Bluetooth di pengaturan HP", style = TextStyle(color = MUTED, fontSize = 12.sp))
            }
            return
        }

        // ── Status card ──
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = if (connectedClientCount > 0) CARD.copy(alpha = 0.7f) else PANEL),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (connectedClientCount > 0) GREEN.copy(alpha = 0.7f) else BORDER)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (connectedClientCount == 0) {
                    Icon(Icons.Default.BluetoothSearching, contentDescription = null, tint = AMBER, modifier = Modifier.size(32.dp).align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(8.dp))
                    Text("Menunggu Electron Admin connect…", style = TextStyle(color = AMBER, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(4.dp))
                    Text("Di laptop Admin: buka tab Admin → panel 'Koneksi MC via Bluetooth' → klik CONNECT", style = TextStyle(color = MUTED, fontSize = 10.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    Icon(Icons.Default.BluetoothConnected, contentDescription = null, tint = GREEN, modifier = Modifier.size(32.dp).align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(8.dp))
                    Text("✓ Electron Admin Terhubung", style = TextStyle(color = GREEN, fontSize = 14.sp, fontWeight = FontWeight.Bold), modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }

        // ── Diagnostics card (shown when waiting for connection, or when start failed) ──
        if (connectedClientCount == 0 && (isAdvertising || adStatus == "failed")) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                colors = CardDefaults.cardColors(containerColor = PANEL.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Diagnostik BLE:", style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(6.dp))
                    // BLE support
                    val supportColor = if (bleSupported) GREEN else RED
                    val supportText = if (bleSupported) "✓ BLE Peripheral didukung" else "✗ BLE Peripheral TIDAK didukung"
                    Text(supportText, style = TextStyle(color = supportColor, fontSize = 10.sp))
                    // Advertising status
                    val adColor = when (adStatus) {
                        "advertising" -> GREEN
                        "failed" -> RED
                        else -> MUTED
                    }
                    val adText = when (adStatus) {
                        "advertising" -> "✓ Advertising aktif (menunggu scan dari laptop)"
                        "failed" -> "✗ Advertising gagal: $adError"
                        else -> "... Advertising idle"
                    }
                    Text(adText, style = TextStyle(color = adColor, fontSize = 10.sp))
                    // Service UUID (so user can verify)
                    Text("Service UUID:", style = TextStyle(color = MUTED, fontSize = 9.sp))
                    Text(BLEProtocol.SERVICE_UUID, style = TextStyle(color = MUTED.copy(alpha = 0.7f), fontSize = 8.sp, fontFamily = FontFamily.Monospace))
                    // Restart button
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            bleServer.restartAdvertising()
                            adStatus = "idle"
                            adError = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = BORDER),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = GOLD, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    Text("Restart Advertising", color = GOLD, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    // WiFi socket status (for trigger delivery)
                    Spacer(Modifier.height(8.dp))
                    Text("WiFi Trigger (pasti sampai):", style = TextStyle(color = GOLD, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                    val wifiColor = if (socketConnected) GREEN else RED
                    val wifiText = if (socketConnected) "✓ WiFi socket terhubung ($serverIp)" else "✗ WiFi socket belum connect"
                    Text(wifiText, style = TextStyle(color = wifiColor, fontSize = 10.sp))
                    if (!socketConnected) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = ipInput,
                            onValueChange = { ipInput = it },
                            label = { Text("IP Laptop Admin", style = TextStyle(color = MUTED, fontSize = 10.sp)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { connectWifiSocket(ipInput.trim()) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = CYAN),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.Wifi, contentDescription = null, tint = BG, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("CONNECT WIFI", color = BG, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("WiFi digunakan untuk kirim trigger PANGGIL/NEXT/RESET ke Electron. BLE tidak reliable untuk ini.",
                            style = TextStyle(color = MUTED.copy(alpha = 0.7f), fontSize = 8.sp))
                    }
                }
            }
        }

        // ── Not supported warning ──
        if (!bleSupported && hasBtPermission) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, RED)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("⚠️ HP ini tidak support BLE Peripheral", style = TextStyle(color = RED, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(4.dp))
                    Text("Beberapa HP tidak bisa menjadi BLE Server.\nGunakan mode WIFI / LAN sebagai gantinya.", style = TextStyle(color = MUTED, fontSize = 10.sp))
                }
            }
        }

        // ── Next Student + Trigger (only when connected) ──
        if (connectedClientCount > 0) {
            val scrollState = androidx.compose.foundation.rememberScrollState()
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(8.dp)
            ) {
                // Student name card
                val studentName = nextStudent?.optString("nama") ?: ""
                val studentNim = nextStudent?.optString("nim") ?: ""

                val phaseColor = when (statusPhase) {
                    BLEProtocol.Phase.READY -> GOLD
                    BLEProtocol.Phase.CAPTURING -> CYAN
                    BLEProtocol.Phase.DONE -> GREEN
                    else -> MUTED
                }

                // HIGHLIGHT: active student → strong gold tint + 2dp gold border
                // (MOST prominent). Next (standby w/ student) → button-like gold.
                val domCardBg = when {
                    statusPhase != "standby" -> GOLD.copy(alpha = 0.2f)
                    studentName.isNotBlank() -> GOLD.copy(alpha = 0.1f)
                    else -> PANEL
                }
                val domBorderWidth = if (statusPhase != "standby" || studentName.isNotBlank()) 2.dp else 1.dp
                val domBorderColor = when {
                    statusPhase != "standby" -> GOLD
                    studentName.isNotBlank() -> GOLD.copy(alpha = 0.7f)
                    else -> BORDER.copy(alpha = 0.4f)
                }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    colors = CardDefaults.cardColors(containerColor = domCardBg),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(domBorderWidth, domBorderColor)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        val phaseText = when (statusPhase) {
                            BLEProtocol.Phase.STANDBY -> "Menunggu"
                            BLEProtocol.Phase.READY -> "◆ Sedang Dipanggil"
                            BLEProtocol.Phase.CAPTURING -> "📸 Sedang Foto"
                            BLEProtocol.Phase.DONE -> "✓ Selesai"
                            else -> "Standby"
                        }
                        Text(phaseText, style = TextStyle(color = phaseColor, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(8.dp))
                        if (studentName.isNotBlank()) {
                            Text(studentName, style = TextStyle(color = if (statusPhase == "standby") Color.White else GOLD, fontSize = 22.sp, fontWeight = FontWeight.Black, lineHeight = 24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (studentNim.isNotBlank()) {
                                Text(studentNim, style = TextStyle(color = MUTED, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                            }
                        } else {
                            Text("Tidak ada mahasiswa", style = TextStyle(color = MUTED, fontSize = 16.sp))
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // PANGGIL button
                val canPanggil = statusPhase == "standby" || statusPhase == "done"
                // optString("id") returns "" when the student JSON has no id —
                // only pass a studentId when a REAL one exists, otherwise the
                // admin targets a non-existent (empty) student.
                val panggilStudentId = nextStudent?.optString("id")?.takeIf { it.isNotBlank() }
                Button(
                    onClick = {
                        // Send trigger via BLE (best effort) + WiFi socket.io (guaranteed)
                        bleServer.sendTrigger(BLEProtocol.Action.PANGGIL, panggilStudentId)
                        sendTriggerViaWifi("PANGGIL", panggilStudentId)
                        statusPhase = BLEProtocol.Phase.READY
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (canPanggil) GREEN else BORDER),
                    shape = RoundedCornerShape(12.dp),
                    enabled = canPanggil
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp), tint = BG)
                    Spacer(Modifier.width(8.dp))
                    Text("PANGGIL", color = BG, fontSize = 18.sp, fontWeight = FontWeight.Black)
                }

                Spacer(Modifier.height(6.dp))

                // Secondary buttons
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            bleServer.sendTrigger(BLEProtocol.Action.NEXT)
                            sendTriggerViaWifi("NEXT", null)
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CYAN),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CYAN)
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("NEXT", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            bleServer.sendTrigger(BLEProtocol.Action.RESET)
                            sendTriggerViaWifi("RESET", null)
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AMBER),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AMBER)
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("RESET", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Queue stats
                Spacer(Modifier.height(8.dp))
                if (queueData != null) {
                    val total = queueData!!.optInt("total", 0)
                    val pending = queueData!!.optInt("pending", 0)
                    val done = queueData!!.optInt("done", 0)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Menunggu: $pending", style = TextStyle(color = MUTED, fontSize = 11.sp))
                        Text("Selesai: $done", style = TextStyle(color = GREEN, fontSize = 11.sp))
                        Text("Total: $total", style = TextStyle(color = MUTED, fontSize = 11.sp))
                    }

                    // Queue list
                    Spacer(Modifier.height(8.dp))
                    val students = queueData!!.optJSONArray("students")
                    if (students != null && students.length() > 0) {
                        Text("Antrean Berikutnya:", style = TextStyle(color = MUTED, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(4.dp))
                        for (i in 0 until students.length()) {
                            val s = students.optJSONObject(i)
                            val name = s?.optString("nama") ?: ""
                            val status = s?.optString("status") ?: "pending"
                            val isActive = status.startsWith("active")
                            val isDone = status == "done"
                            // HIGHLIGHT: first pending row (next to call) → button-like gold.
                            val isFirstPending = i == 0 && status == "pending"
                            val rowBg = when { isActive -> GOLD.copy(alpha = 0.18f); isFirstPending -> GOLD.copy(alpha = 0.1f); isDone -> PANEL.copy(alpha = 0.3f); else -> PANEL }
                            val dotColor = when { isActive -> GOLD; isDone -> GREEN.copy(alpha = 0.5f); else -> MUTED.copy(alpha = 0.3f) }
                            val rowBorderWidth = if (isActive || isFirstPending) 1.5.dp else 1.dp
                            val rowBorderColor = when { isActive -> GOLD; isFirstPending -> GOLD.copy(alpha = 0.7f); else -> BORDER.copy(alpha = 0.2f) }

                            Card(colors = CardDefaults.cardColors(containerColor = rowBg), shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(rowBorderWidth, rowBorderColor),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${i+1}", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.width(14.dp))
                                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(dotColor))
                                    Text(name, style = TextStyle(color = if (isActive || isFirstPending) GOLD else if (isDone) MUTED.copy(alpha = 0.4f) else Color.White, fontSize = 11.sp, fontWeight = if (isActive) FontWeight.Bold else if (isFirstPending) FontWeight.SemiBold else FontWeight.Normal), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (isActive) Text("◆", style = TextStyle(color = GOLD, fontSize = 8.sp))
                                    else if (isFirstPending) Text("▶", style = TextStyle(color = GOLD, fontSize = 8.sp))
                                    if (isDone) Text("✓", style = TextStyle(color = GREEN, fontSize = 9.sp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
