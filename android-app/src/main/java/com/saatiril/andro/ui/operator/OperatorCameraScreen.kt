package com.saatiril.andro.ui.operator

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saatiril.andro.camera.HandTriggerDetector
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.CameraModes
import com.saatiril.andro.data.CapturePhase
import com.saatiril.andro.data.ConnectionState
import com.saatiril.andro.data.OperatorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val CYAN = Color(0xFF06b6d4)
private val GREEN = Color(0xFF4ade80)
private val RED = Color(0xFFef4444)
private val AMBER = Color(0xFFfbbf24)

/**
 * Operator Camera Screen — the main camera UI for an operator phone.
 *
 * This screen is shown after the operator connects to the admin's server
 * and is authenticated. It shows:
 *  - The live camera preview (Camera2 built-in or UVC USB capture card)
 *  - The current target student (received via MC_CALL from the MC)
 *  - Shutter button + timer/hand trigger modes
 *  - Connection status + disconnect button
 *
 * Photos captured here are sent via socket to the admin server, which saves
 * them to the project folder. They are NOT saved locally on the operator phone.
 */
@Composable
fun OperatorCameraScreen(
    adminViewModel: AdminViewModel,
    opViewModel: OperatorViewModel = viewModel()
) {
    val context = LocalContext.current

    val project by opViewModel.project.collectAsState()
    val connectionState by opViewModel.connectionState.collectAsState()
    val currentTarget by opViewModel.currentTarget.collectAsState()
    val capturePhase by opViewModel.capturePhase.collectAsState()
    val capturedPhotos by opViewModel.capturedPhotos.collectAsState()
    val isSending by opViewModel.isSending.collectAsState()
    val shutterMode by opViewModel.shutterMode.collectAsState()
    val timerCountdown by opViewModel.timerCountdown.collectAsState()
    val handState by opViewModel.handState.collectAsState()
    val frameBitmap by opViewModel.frameBitmap.collectAsState()
    val availableCameras by opViewModel.availableCameras.collectAsState()
    val activeEngine by opViewModel.activeCameraEngine.collectAsState()
    val cameraSource by opViewModel.cameraSource.collectAsState()
    val physicalShutterSync by opViewModel.physicalShutterSync.collectAsState()
    val usbSafeMode by opViewModel.usbSafeMode.collectAsState()
    val usbDisabled by opViewModel.usbDisabled.collectAsState()
    val usbConnectBlocked by opViewModel.usbConnectBlocked.collectAsState()
    val usbDiag by opViewModel.usbDiag.collectAsState()
    val lastUsbCrashStep by opViewModel.lastUsbCrashStep.collectAsState()
    val currentCameraId by opViewModel.currentCameraId.collectAsState()
    val gridlineSettings by opViewModel.gridlineSettings.collectAsState()
    val scope = rememberCoroutineScope()

    // Camera permission
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    // VPN permission launcher for Ceremony Mode (auto-activates when connected)
    val ceremonyActive by com.saatiril.andro.vpn.CeremonyModeManager.isActive.observeAsState(false)
    val vpnPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        com.saatiril.andro.vpn.CeremonyModeManager.onPermissionResult(context, result.resultCode)
    }

    // Lock screen — prevent accidental exit via back button (after ceremonyActive is declared)
    com.saatiril.andro.ui.util.LockScreenHandler {
        if (ceremonyActive) {
            com.saatiril.andro.vpn.CeremonyModeManager.disable(context)
        }
        opViewModel.disconnect()
        adminViewModel.backToRoleSelect()
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Auto-activate Ceremony Mode when operator connects (blocks internet distractions)
    LaunchedEffect(connectionState) {
        if ((connectionState == ConnectionState.AUTHENTICATED ||
             connectionState == ConnectionState.WAITING_FOR_DATA) &&
            !ceremonyActive
        ) {
            // Auto-enable VPN — operator won't be disturbed by WhatsApp/Play Store during ceremony
            com.saatiril.andro.vpn.CeremonyModeManager.enable(context, vpnPermissionLauncher)
        }
    }

    // Auto-disconnect handling: if connection drops, go back to connect screen
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.DISCONNECTED) {
            // Disable VPN when disconnecting
            if (ceremonyActive) {
                com.saatiril.andro.vpn.CeremonyModeManager.disable(context)
            }
            delay(500)
            adminViewModel.operatorDisconnected()
        }
    }

    val textureView = remember { TextureView(context) }
    val uvcConnected by opViewModel.cameraUVCManager.isConnected.collectAsState()
    val c2Connected by opViewModel.camera2Manager.isConnected.collectAsState()
    val ptpConnected by opViewModel.ptpCameraManager.isConnected.collectAsState()
    // FTP (WiFi transfer) engine — the camera uploads photos to this phone;
    // the connected-dot + the passive status card below read off the manager.
    val ftpClientConnected by opViewModel.ftpManager.clientConnected.collectAsState()
    val ftpServerAddress by opViewModel.ftpManager.serverAddress.collectAsState()
    val ftpTetherAddress by opViewModel.ftpManager.tetherAddress.collectAsState()
    val ftpLastFile by opViewModel.ftpManager.lastFile.collectAsState()
    val ftpLastError by opViewModel.ftpManager.lastError.collectAsState()
    val cameraConnected = when (activeEngine) { "uvc" -> uvcConnected; "camera2" -> c2Connected; "ftp" -> ftpClientConnected; else -> ptpConnected }

    var showCameraPicker by remember { mutableStateOf(false) }
    var showQueuePanel by remember { mutableStateOf(false) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showFlash by remember { mutableStateOf(false) }

    // Hand trigger setup
    var handDetectionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val mode = project?.config?.mode ?: CameraModes.SINGLE
    val isPhotoshoot = CameraModes.isPhotoshootMode(mode)

    // Init camera when permission granted
    DisposableEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            val projRatio = project?.config?.ratio ?: "4:3"
            val parts = projRatio.split(":")
            val ar = if (parts.size == 2) (parts[0].toFloatOrNull() ?: 4f) / (parts[1].toFloatOrNull() ?: 3f) else 4f / 3f
            opViewModel.camera2Manager.setProjectAspectRatio(ar)
            opViewModel.camera2Manager.setTextureView(textureView)
            opViewModel.cameraUVCManager.setTextureView(textureView)
            opViewModel.ptpCameraManager.setTextureView(textureView)

            // BUG FIX: TextureView keeps only ONE surfaceTextureListener — the
            // second setTextureView call above replaced Camera2's, so Camera2
            // never received surface events (intermittent permanent black
            // preview). Install ONE shared listener LAST that fans the events
            // out to ALL managers.
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    opViewModel.camera2Manager.onSurfaceTextureAvailable(width, height)
                    opViewModel.cameraUVCManager.onSurfaceTextureAvailable(surface, width, height)
                    opViewModel.ptpCameraManager.onSurfaceTextureAvailable(width, height)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    opViewModel.camera2Manager.onSurfaceTextureSizeChanged(width, height)
                    opViewModel.cameraUVCManager.onSurfaceTextureSizeChanged(surface, width, height)
                    opViewModel.ptpCameraManager.onSurfaceTextureSizeChanged(width, height)
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    val c2Handled = opViewModel.camera2Manager.onSurfaceTextureDestroyed()
                    val uvcHandled = opViewModel.cameraUVCManager.onSurfaceTextureDestroyed()
                    val ptpHandled = opViewModel.ptpCameraManager.onSurfaceTextureDestroyed()
                    return c2Handled && uvcHandled && ptpHandled
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    // Called on every frame — no-op (all managers no-op here)
                }
            }

            opViewModel.camera2Manager.enumerateCameras()
            opViewModel.cameraUVCManager.initCamera()
            opViewModel.ptpCameraManager.initCamera()
            opViewModel.camera2Manager.openCamera()
        }
        onDispose {
            try { opViewModel.camera2Manager.closeCamera() } catch (_: Exception) {}
            try { opViewModel.cameraUVCManager.hidePreview() } catch (_: Exception) {}
            try { opViewModel.ptpCameraManager.closeCamera() } catch (_: Exception) {}
            try { opViewModel.ftpManager.stop() } catch (_: Exception) {}
            try { HandTriggerDetector.stop() } catch (_: Exception) {}
        }
    }

    // Hand trigger detection
    LaunchedEffect(shutterMode) {
        if (shutterMode == "hand") {
            val initialized = HandTriggerDetector.initialize(context)
            if (initialized) {
                HandTriggerDetector.start()
                handDetectionJob = scope.launch(Dispatchers.IO) {
                    while (shutterMode == "hand" && HandTriggerDetector.isDetecting()) {
                        try {
                            val bitmap = withContext(Dispatchers.Main) { textureView.bitmap }
                            if (bitmap != null) {
                                HandTriggerDetector.processFrame(bitmap)
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                        delay(200)
                    }
                }
            } else {
                opViewModel.setShutterMode("manual")
            }
        } else {
            HandTriggerDetector.stop()
            handDetectionJob?.cancel()
            handDetectionJob = null
        }
    }

    // Hand trigger callback
    DisposableEffect(shutterMode) {
        if (shutterMode == "hand") {
            HandTriggerDetector.onHandLeft = {
                scope.launch(Dispatchers.Main) {
                    opViewModel.triggerCapture()
                }
            }
        }
        onDispose {
            HandTriggerDetector.onHandLeft = null
        }
    }

    // No camera permission
    if (!hasCameraPermission) {
        Column(
            modifier = Modifier.fillMaxSize().background(BG).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = GOLD, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("Izin Kamera Diperlukan", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Berikan Izin Kamera", color = BG, fontWeight = FontWeight.Bold) }
        }
        return
    }

    val ratio = project?.config?.ratio ?: "4:3"
    val aspectRatio = when (ratio) {
        "4:3" -> 4f / 3f; "3:4" -> 3f / 4f; "16:9" -> 16f / 9f; "9:16" -> 9f / 16f
        "2:3" -> 2f / 3f; "4:6" -> 4f / 6f; "1:1" -> 1f; else -> 4f / 3f
    }
    val isPortrait = aspectRatio < 1f

    Column(
        modifier = Modifier.fillMaxSize().background(BG),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // ── Top bar: connection + target info ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Connection status
            val connColor = when (connectionState) {
                ConnectionState.AUTHENTICATED, ConnectionState.WAITING_FOR_DATA -> GREEN
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING, ConnectionState.AUTHENTICATING -> GOLD
                else -> RED
            }
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(connColor))
            Text(
                "Ch.${opViewModel.myChannel.value} • ${cameraSource}",
                style = TextStyle(color = connColor, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
            // Ceremony Mode shield indicator
            if (ceremonyActive) {
                Icon(Icons.Default.Shield, contentDescription = "Mode Prosesi", tint = GREEN, modifier = Modifier.size(14.dp))
            }
            // Disconnect button
            Card(
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                    .clickable {
                        // Disable VPN before disconnecting
                        if (ceremonyActive) {
                            com.saatiril.andro.vpn.CeremonyModeManager.disable(context)
                        }
                        opViewModel.disconnect()
                        adminViewModel.backToRoleSelect()
                    },
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(6.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Logout, contentDescription = "Disconnect", tint = RED, modifier = Modifier.size(16.dp))
                }
            }
        }

        // Target info bar
        currentTarget?.let { student ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = GOLD, modifier = Modifier.size(16.dp))
                Text(
                    student.nama.take(25).ifBlank { student.nim.take(25) },
                    style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Text(student.nim.take(15), style = TextStyle(color = MUTED, fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                Card(colors = CardDefaults.cardColors(containerColor = GOLD), shape = RoundedCornerShape(3.dp)) {
                    Text("Ch.${opViewModel.myChannel.value}", Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = TextStyle(color = BG, fontWeight = FontWeight.Bold, fontSize = 8.sp))
                }
            }
        } ?: run {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MUTED, modifier = Modifier.size(14.dp))
                Text(" Menunggu MC memanggil mahasiswa…", style = TextStyle(color = MUTED, fontSize = 11.sp))
            }
        }

        // ── Camera preview (weight=1f, never shrinks) ──
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))
                .background(Color.Black).border(1.dp, BORDER, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.then(
                    if (isPortrait) Modifier.fillMaxHeight().aspectRatio(aspectRatio)
                    else Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                ).clip(RoundedCornerShape(4.dp)).background(Color.Black)
            ) {
                AndroidView(factory = { textureView }, modifier = Modifier.fillMaxSize())

                // Gridline overlay
                if (gridlineSettings.enabled) {
                    OperatorGridlineCanvas(
                        type = gridlineSettings.type.name.lowercase(),
                        thickness = gridlineSettings.thickness.ordinal + 1,
                        colorName = gridlineSettings.color.name.lowercase(),
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Frame overlay
                frameBitmap?.let { fb ->
                    Image(
                        bitmap = fb.asImageBitmap(),
                        contentDescription = "Frame",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }

                // No camera signal (the FTP/WiFi engine gets its own waiting hint —
                // "no signal" just means the camera hasn't connected over WiFi yet)
                if (!cameraConnected) {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.VideocamOff, contentDescription = null, tint = MUTED.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
                        if (activeEngine == "ftp") {
                            Text("MENUNGGU KAMERA WIFI", style = TextStyle(color = MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                            Text("Nyalakan hotspot HP → hubungkan kamera → tekan shutter di kamera", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 8.sp))
                        } else {
                            Text("NO CAMERA SIGNAL", style = TextStyle(color = MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                            Text("Pilih kamera atau cek USB", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 8.sp))
                        }
                    }
                }

                // Camera status badge
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(Modifier.size(5.dp).clip(RoundedCornerShape(2.dp)).background(if (cameraConnected) GREEN else RED))
                    Text("Ch.${opViewModel.myChannel.value} • ${if (cameraConnected) "●" else "○"}", style = TextStyle(color = Color.White, fontSize = 9.sp))
                    Text(ratio, style = TextStyle(color = MUTED, fontSize = 7.sp))
                }

                // Hand trigger indicator
                if (shutterMode == "hand" && handState != HandTriggerDetector.HandState.NONE) {
                    Card(
                        modifier = Modifier.align(Alignment.TopCenter).padding(4.dp),
                        colors = CardDefaults.cardColors(containerColor = when (handState) {
                            HandTriggerDetector.HandState.HAND_DETECTED -> Color.Black.copy(alpha = 0.7f)
                            HandTriggerDetector.HandState.CONFIRMED -> GREEN.copy(alpha = 0.8f)
                            HandTriggerDetector.HandState.TRIGGERED -> GOLD.copy(alpha = 0.8f)
                            else -> Color.Black.copy(alpha = 0.7f)
                        }),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            when (handState) {
                                HandTriggerDetector.HandState.HAND_DETECTED -> "Tangan terdeteksi…"
                                HandTriggerDetector.HandState.CONFIRMED -> "Tangan ✓ — lepaskan untuk foto"
                                HandTriggerDetector.HandState.TRIGGERED -> "Timer berjalan!"
                                else -> ""
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = TextStyle(color = if (handState == HandTriggerDetector.HandState.HAND_DETECTED) MUTED else BG, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        )
                    }
                }

                // Timer countdown
                if (timerCountdown > 0) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)), shape = RoundedCornerShape(50)) {
                            Text("$timerCountdown", modifier = Modifier.padding(horizontal = 30.dp, vertical = 12.dp), style = TextStyle(color = GOLD, fontSize = 48.sp, fontWeight = FontWeight.Black))
                        }
                    }
                }

                // Flash overlay
                if (showFlash) {
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.6f)))
                }

                // Camera picker
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton(onClick = { showCameraPicker = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Pilih Kamera", tint = GOLD, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = showCameraPicker, onDismissRequest = { showCameraPicker = false }, modifier = Modifier.background(PANEL)) {
                        Text("Pilih Kamera", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = TextStyle(color = GOLD, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        HorizontalDivider(color = BORDER, thickness = 0.5.dp)
                        if (availableCameras.isNotEmpty()) {
                            availableCameras.forEach { (cameraId, displayName) ->
                                val isSelected = cameraId == currentCameraId
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Icon(
                                                when {
                                                    displayName.contains("PTP", ignoreCase = true) -> Icons.Default.PhotoCamera
                                                    displayName.contains("WiFi", ignoreCase = true) -> Icons.Default.Wifi
                                                    displayName.contains("USB", ignoreCase = true) -> Icons.Default.Usb
                                                    displayName.contains("Depan", ignoreCase = true) -> Icons.Default.CameraFront
                                                    else -> Icons.Default.CameraAlt
                                                },
                                                contentDescription = null, tint = if (isSelected) GREEN else MUTED, modifier = Modifier.size(16.dp)
                                            )
                                            Text(displayName, style = TextStyle(color = if (isSelected) GREEN else Color.White, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal))
                                            if (isSelected) Text("✓", style = TextStyle(color = GREEN, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                                        }
                                    },
                                    onClick = { opViewModel.switchToCameraById(cameraId); showCameraPicker = false }
                                )
                            }
                        } else {
                            Text("Memuat kamera...", modifier = Modifier.padding(12.dp), style = TextStyle(color = MUTED, fontSize = 12.sp))
                        }
                        HorizontalDivider(color = BORDER, thickness = 0.5.dp)
                        DropdownMenuItem(
                            text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Default.Refresh, contentDescription = null, tint = CYAN, modifier = Modifier.size(14.dp)); Text("Pindai Ulang USB", style = TextStyle(color = CYAN, fontSize = 11.sp)) } },
                            onClick = { opViewModel.forceRescanUsbCamera(); showCameraPicker = false }
                        )
                    }
                }
            } // end aspect-ratio Box
        } // end camera preview Box

        // ── Shutter controls bar ──
        Column(
            modifier = Modifier.fillMaxWidth().background(PANEL.copy(alpha = 0.95f)).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Phase indicator
            val phaseLabel = when (capturePhase) {
                CapturePhase.STANDBY -> "Standby — menunggu target"
                CapturePhase.READY_1 -> if (isPhotoshoot) "Siap Foto" else "Pose 1 — Toga"
                CapturePhase.READY_2 -> "Pose 2 — Ijazah"
                CapturePhase.SENDING -> "Mengirim foto…"
            }
            val phaseColor = when (capturePhase) {
                CapturePhase.STANDBY -> MUTED
                CapturePhase.READY_1 -> GOLD
                CapturePhase.READY_2 -> GOLD
                CapturePhase.SENDING -> CYAN
            }
            if (currentTarget != null) {
                Text(phaseLabel, style = TextStyle(color = phaseColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
            }

            // Shutter modes (hidden in FTP/WiFi mode — the camera's OWN shutter
            // button takes the photo, app-side timer/hand modes don't apply)
            if (cameraSource != "ftp") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("manual" to "M", "timer-3" to "3s", "timer-5" to "5s", "timer-10" to "10s", "hand" to "✋").forEach { (m, label) ->
                        Card(modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).clickable { opViewModel.setShutterMode(m) }
                            .border(1.dp, if (shutterMode == m) GOLD else BORDER, RoundedCornerShape(4.dp)),
                            colors = CardDefaults.cardColors(containerColor = if (shutterMode == m) CARD else PANEL), shape = RoundedCornerShape(4.dp)) {
                            Text(label, modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp).fillMaxWidth(),
                                style = TextStyle(color = if (shutterMode == m) GOLD else MUTED, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
                        }
                    }
                }
            }
            // Physical shutter sync (PTP only) — photos taken with the camera's
            // OWN shutter button are detected over USB and enter the capture
            // pipeline automatically (no need to tap this screen).
            if (cameraSource == "ptp") {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        .background(if (physicalShutterSync) CARD else PANEL)
                        .border(1.dp, if (physicalShutterSync) CYAN else BORDER, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = if (physicalShutterSync) CYAN else MUTED, modifier = Modifier.size(12.dp))
                    Text("Shutter Kamera Fisik", style = TextStyle(color = if (physicalShutterSync) CYAN else MUTED, fontSize = 9.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Switch(
                        checked = physicalShutterSync,
                        onCheckedChange = { opViewModel.setPhysicalShutterSync(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CYAN,
                            checkedTrackColor = CYAN.copy(alpha = 0.4f),
                            uncheckedThumbColor = MUTED,
                            uncheckedTrackColor = BORDER
                        )
                    )
                }
            }
            // USB safe mode (PTP only) — workaround for phones whose USB driver
            // restarts the phone during PTP transfers: no interrupt-endpoint
            // reads + settle delay. ON by default; only turn OFF on phones that
            // never reboot (captures feel slightly snappier with it off).
            if (cameraSource == "ptp") {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        .background(if (usbSafeMode) CARD else PANEL)
                        .border(1.dp, if (usbSafeMode) CYAN else BORDER, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = if (usbSafeMode) CYAN else MUTED, modifier = Modifier.size(12.dp))
                    Text("Mode Aman USB", style = TextStyle(color = if (usbSafeMode) CYAN else MUTED, fontSize = 9.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Switch(
                        checked = usbSafeMode,
                        onCheckedChange = { opViewModel.setUsbSafeMode(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CYAN,
                            checkedTrackColor = CYAN.copy(alpha = 0.4f),
                            uncheckedThumbColor = MUTED,
                            uncheckedTrackColor = BORDER
                        )
                    )
                }
            }
            // USB kill switch (PTP only) — total opt-out for phones whose
            // kernel panics on ANY PTP/MTP bulk traffic (phone reboots).
            // ON = the app never touches usbfs; photos arrive via the
            // WiFi/FTP engine instead. Auto-locks after 2 detected reboots.
            if (cameraSource == "ptp") {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        .background(if (usbDisabled) CARD else PANEL)
                        .border(1.dp, if (usbDisabled) RED else BORDER, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Usb, contentDescription = null, tint = if (usbDisabled) RED else MUTED, modifier = Modifier.size(12.dp))
                    Text("Nonaktifkan USB" + if (usbDisabled) " (foto via WiFi)" else "",
                        style = TextStyle(color = if (usbDisabled) RED else MUTED, fontSize = 9.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Switch(
                        checked = usbDisabled,
                        onCheckedChange = { opViewModel.setUsbDisabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = RED,
                            checkedTrackColor = RED.copy(alpha = 0.4f),
                            uncheckedThumbColor = MUTED,
                            uncheckedTrackColor = BORDER
                        )
                    )
                }
            }
            // Crash-block: camera attached but auto-connect is paused after a
            // detected USB-driver reboot — the user taps "Hubungkan" to retry.
            if (cameraSource == "ptp" && usbConnectBlocked && !usbDisabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        .background(CARD).border(1.dp, CYAN, RoundedCornerShape(4.dp))
                        .clickable { opViewModel.userRequestedUsbConnect() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = CYAN, modifier = Modifier.size(12.dp))
                    Text("USB ditunda setelah restart — ketuk untuk Hubungkan", style = TextStyle(color = CYAN, fontSize = 9.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                }
            }
            // Live USB pipeline diagnostics + reboot forensics (PTP only)
            if (cameraSource == "ptp") {
                usbDiag?.let { d ->
                    Text("USB: $d", style = TextStyle(color = MUTED, fontSize = 8.sp), modifier = Modifier.fillMaxWidth())
                }
                lastUsbCrashStep?.let { c ->
                    Text("⚠ Ponsel terakhir restart saat: $c — laporkan kalau terulang", style = TextStyle(color = RED, fontSize = 8.sp, fontWeight = FontWeight.Bold), modifier = Modifier.fillMaxWidth())
                }
            }
            // Shutter button
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val shutterLabel = when {
                    isSending -> "Mengirim…"
                    capturePhase == CapturePhase.READY_2 -> "Ijazah"
                    capturePhase == CapturePhase.SENDING -> "Kirim"
                    else -> if (isPhotoshoot) "Foto" else "Toga"
                }
                val canCapture = currentTarget != null && capturePhase != CapturePhase.SENDING && !isSending && timerCountdown == 0
                if (cameraSource == "ftp") {
                    // Network (FTP) transfer mode — passive status card replaces the
                    // shutter button: photos are taken with the camera's OWN
                    // shutter button and arrive over the cable (USB tethering) or
                    // WiFi, there is nothing to trigger app-side (same visual
                    // language as the PTP rows above).
                    Row(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(4.dp))
                            .background(CARD)
                            .border(1.dp, if (ftpTetherAddress != null) GREEN else CYAN, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(if (ftpTetherAddress != null) Icons.Default.Usb else Icons.Default.Wifi,
                            contentDescription = null, tint = if (ftpTetherAddress != null) GREEN else CYAN,
                            modifier = Modifier.size(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Menunggu foto dari kamera…", style = TextStyle(color = if (ftpTetherAddress != null) GREEN else CYAN, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                            Text("FTP ${ftpServerAddress ?: "—"} ${if (ftpClientConnected) "• kamera terhubung" else "• menunggu kamera"}", style = TextStyle(color = MUTED, fontSize = 8.sp))
                            if (ftpTetherAddress != null) {
                                Text("KABEL USB aktif — foto masuk lewat kabel (USB-C)", style = TextStyle(color = GREEN, fontSize = 8.sp, fontWeight = FontWeight.Bold))
                                Text("Kamera: FTP Server 1 → Host = ${ftpTetherAddress}, Port 2121, Auto Transfer On", style = TextStyle(color = MUTED, fontSize = 8.sp))
                            } else if (!ftpClientConnected) {
                                Text("Mode kabel (disarankan): kamera MENU → Network → USB-LAN/Tethering → Tethering Connection → colok USB-C → ponsel: aktifkan USB/Ethernet tethering", style = TextStyle(color = MUTED, fontSize = 8.sp))
                                Text("Mode WiFi: hotspot HP WPA2 (bukan WPA3), kamera satu jaringan, port 2121", style = TextStyle(color = MUTED, fontSize = 8.sp))
                            }
                            ftpLastFile?.let { f -> Text("Terakhir: $f", style = TextStyle(color = MUTED, fontSize = 8.sp)) }
                            ftpLastError?.let { e -> Text(e, style = TextStyle(color = RED, fontSize = 8.sp)) }
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            if (!canCapture) return@Button
                            showFlash = true
                            scope.launch { delay(200); showFlash = false }
                            opViewModel.triggerCapture()
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (canCapture) GOLD else BORDER),
                        shape = RoundedCornerShape(8.dp),
                        enabled = canCapture
                    ) {
                        if (isSending) { CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BG, strokeWidth = 2.dp); Spacer(Modifier.width(4.dp)); Text("Kirim", color = BG, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        else if (timerCountdown > 0) { Text("$timerCountdown", color = BG, fontSize = 14.sp, fontWeight = FontWeight.Black) }
                        else { Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(20.dp), tint = BG); Spacer(Modifier.width(6.dp)); Text(shutterLabel, color = BG, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                // Settings toggle
                Card(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).clickable { showSettingsPanel = !showSettingsPanel }
                    .border(1.dp, if (showSettingsPanel) GOLD else BORDER, RoundedCornerShape(8.dp)),
                    colors = CardDefaults.cardColors(containerColor = if (showSettingsPanel) CARD else PANEL), shape = RoundedCornerShape(8.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.Tune, contentDescription = "Pengaturan", tint = if (showSettingsPanel) GOLD else MUTED, modifier = Modifier.size(20.dp)) }
                }
            }
            // Timer cancel
            if (timerCountdown > 0) {
                Button(onClick = { opViewModel.cancelTimerCapture() },
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RED), shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)) { Text("BATAL TIMER", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        } // end shutter controls bar
    } // end main Column
}

// ── Gridline Canvas (same as AdminOperatorScreen) ──
@Composable
private fun OperatorGridlineCanvas(type: String, thickness: Int = 1, colorName: String = "white", modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val baseColor = when (colorName) {
            "yellow" -> Color(0xFFfacc15)
            "red" -> Color(0xFFef4444)
            "cyan" -> Color(0xFF06b6d4)
            "green" -> Color(0xFF22c55e)
            else -> Color.White
        }
        val color = baseColor.copy(alpha = 0.35f)
        val stroke = when (thickness) { 1 -> 1f; 2 -> 2f; 3 -> 3f; else -> 1f }
        when (type) {
            "thirds" -> {
                drawLine(color, androidx.compose.ui.geometry.Offset(w / 3f, 0f), androidx.compose.ui.geometry.Offset(w / 3f, h), stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(2 * w / 3f, 0f), androidx.compose.ui.geometry.Offset(2 * w / 3f, h), stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(0f, h / 3f), androidx.compose.ui.geometry.Offset(w, h / 3f), stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(0f, 2 * h / 3f), androidx.compose.ui.geometry.Offset(w, 2 * h / 3f), stroke)
            }
            "quarters" -> {
                drawLine(color, androidx.compose.ui.geometry.Offset(w / 4f, 0f), androidx.compose.ui.geometry.Offset(w / 4f, h), stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(w / 2f, 0f), androidx.compose.ui.geometry.Offset(w / 2f, h), stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(3 * w / 4f, 0f), androidx.compose.ui.geometry.Offset(3 * w / 4f, h), stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(0f, h / 4f), androidx.compose.ui.geometry.Offset(w, h / 4f), stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(0f, h / 2f), androidx.compose.ui.geometry.Offset(w, h / 2f), stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(0f, 3 * h / 4f), androidx.compose.ui.geometry.Offset(w, 3 * h / 4f), stroke)
            }
            "crosshair" -> {
                drawLine(color, androidx.compose.ui.geometry.Offset(w / 2f, 0f), androidx.compose.ui.geometry.Offset(w / 2f, h), stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(0f, h / 2f), androidx.compose.ui.geometry.Offset(w, h / 2f), stroke)
                drawCircle(color, minOf(w, h) / 6f, center = androidx.compose.ui.geometry.Offset(w / 2f, h / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            }
            "diagonal" -> {
                drawLine(color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(w, h), stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(0f, h), stroke)
            }
        }
    }
}
