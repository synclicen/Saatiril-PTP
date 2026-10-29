package com.saatiril.andro.ui.operator

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.util.Base64
import android.util.Log
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.zIndex
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
import androidx.compose.ui.draw.scale
import androidx.core.content.ContextCompat
import com.saatiril.andro.camera.CameraCapture
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.CameraModes
import com.saatiril.andro.data.CapturePhase
import com.saatiril.andro.data.Student
import com.saatiril.andro.data.getActiveChannel
import com.saatiril.andro.data.isActiveStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Admin Operator panel — a simplified camera panel for the admin's own phone.
 *
 * In the Electron app, the admin sees MC + Operator side-by-side. The Operator
 * panel shows the admin's webcam preview and a shutter button. On Android, this
 * uses Camera2 to show the phone's built-in camera as a BACKUP camera (the
 * primary photography is done by operators on separate phones with the
 * `android-operator` APK + DSLR + USB capture card).
 *
 * When the admin captures a photo here:
 *  1. The frame is cropped to the project's aspect ratio + filter preset applied.
 *  2. Saved to the output folder via [com.saatiril.andro.util.PhotoSaver].
 *  3. The current target student (set by MC) is marked "done".
 *  4. SYNC_DB is broadcast to all connected MC/operator clients.
 */
@Composable
fun AdminOperatorScreen(viewModel: AdminViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsState()

    // Camera permission state
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    // Request camera permission on first entry
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ── 3-camera support: use ViewModel's managers (UVC + Camera2) ──
    // The ViewModel exposes cameraUVCManager (USB capture cards via USB Host)
    // and camera2Manager (built-in front/back). Both are the SAME hardened
    // managers from the android-operator APK — ported verbatim.
    val textureView = remember { TextureView(context) }
    val availableCameras by viewModel.availableCameras.collectAsState()
    val activeEngine by viewModel.activeCameraEngine.collectAsState()
    val cameraSource by viewModel.cameraSource.collectAsState()
    val currentCameraId by viewModel.currentCameraId.collectAsState()
    val uvcConnected by viewModel.cameraUVCManager.isConnected.collectAsState()
    val c2Connected by viewModel.camera2Manager.isConnected.collectAsState()
    val ptpConnected by viewModel.ptpCameraManager.isConnected.collectAsState()
    // FTP (WiFi transfer) engine — the camera uploads photos to this phone;
    // the connected-dot + the passive status card below read off the manager.
    val ftpClientConnected by viewModel.ftpManager.clientConnected.collectAsState()
    val ftpServerAddress by viewModel.ftpManager.serverAddress.collectAsState()
    val ftpLastFile by viewModel.ftpManager.lastFile.collectAsState()
    val ftpLastError by viewModel.ftpManager.lastError.collectAsState()
    val physicalShutterSync by viewModel.physicalShutterSync.collectAsState()
    val usbSafeMode by viewModel.usbSafeMode.collectAsState()
    val frameBitmap by viewModel.frameBitmap.collectAsState()
    var showCameraPicker by remember { mutableStateOf(false) }

    val cameraConnected = when (activeEngine) { "uvc" -> uvcConnected; "camera2" -> c2Connected; "ftp" -> ftpClientConnected; else -> ptpConnected }

    var capturedBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    // ── Shutter modes (manual / timer-3 / timer-5 / timer-10 / hand) ──
    var shutterMode by remember { mutableStateOf("manual") }
    // ── Camera mirror (flip horizontal) ──
    // Toggles Modifier.scale(scaleX = -1f) on the camera preview (AndroidView)
    // so the live preview is mirrored. Does NOT affect the captured photo
    // (CameraX ImageCapture captures the raw sensor frame, not the TextureView
    // display) — photos are saved correctly oriented.
    var mirror by remember { mutableStateOf(false) }
    var timerCountdown by remember { mutableStateOf<Int?>(null) }
    var timerJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // ── Hot shoe trigger (camera physical shutter → audio pulse → capture) ──
    // When ON, the AudioRecord monitors the mic input for sharp amplitude spikes
    // (hot shoe pulse from the camera's flash/shutter). When detected → trigger
    // capture (same as tapping the on-screen shutter). Uses AUDIO JACK (not
    // Bluetooth) → no conflict with BLE MC remote. Simultaneous with USB video
    // capture (different input paths).
    var hotShoeTrigger by remember { mutableStateOf(false) }
    var hotShoePulseCount by remember { mutableStateOf(0) }

    // ── Hot shoe: RECORD_AUDIO runtime permission ──
    // The manifest declares RECORD_AUDIO but it was never REQUESTED at
    // runtime, so AudioRecord failed silently and the hot shoe trigger was
    // dead. Request it when the user toggles hot shoe ON; the pulse monitor
    // only starts once permission is granted.
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var showAudioPermissionDenied by remember { mutableStateOf(false) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            // Auto-start the pulse monitor now that permission is granted.
            hotShoeTrigger = true
        } else {
            showAudioPermissionDenied = true
        }
    }

    // ── Hand trigger (matches Electron palmTriggerEnabled + usePalmDetection) ──
    var handState by remember { mutableStateOf(com.saatiril.andro.camera.HandTriggerDetector.HandState.NONE) }
    var handDetectionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())


    // Initialize + start/stop hand detection based on shutter mode
    LaunchedEffect(shutterMode) {
        if (shutterMode == "hand") {
            // Initialize MediaPipe Hand Landmarker (loads 7.8MB model from assets)
            val initialized = com.saatiril.andro.camera.HandTriggerDetector.initialize(context)
            if (initialized) {
                com.saatiril.andro.camera.HandTriggerDetector.start()
                Log.i("OperatorScreen", "Hand trigger detection started")

                // Start detection loop — grab preview frame every 200ms
                handDetectionJob = scope.launch(Dispatchers.IO) {
                    while (shutterMode == "hand" && com.saatiril.andro.camera.HandTriggerDetector.isDetecting()) {
                        try {
                            val bitmap = withContext(Dispatchers.Main) { textureView.bitmap }
                            if (bitmap != null) {
                                com.saatiril.andro.camera.HandTriggerDetector.processFrame(bitmap)
                                handState = com.saatiril.andro.camera.HandTriggerDetector.handState
                            }
                        } catch (e: Exception) {
                            Log.w("OperatorScreen", "Hand detect frame error: ${e.message}")
                        }
                        delay(200)
                    }
                }
            } else {
                Log.e("OperatorScreen", "Failed to initialize HandTriggerDetector")
                shutterMode = "manual" // fallback
            }
        } else {
            // Stop hand detection when mode changes
            com.saatiril.andro.camera.HandTriggerDetector.stop()
            handDetectionJob?.cancel()
            handDetectionJob = null
            handState = com.saatiril.andro.camera.HandTriggerDetector.HandState.NONE
        }
    }

    // ── Gridline overlay (matches Electron operator-panel.tsx:263-266) ──
    var gridlineEnabled by remember { mutableStateOf(true) }
    var gridlineType by remember { mutableStateOf("thirds") }  // thirds | quarters | crosshair | diagonal
    var gridlineThickness by remember { mutableStateOf(1) }  // 1=thin, 2=medium, 3=thick
    var gridlineColor by remember { mutableStateOf("white") }  // white | yellow | red | cyan | green

    // ── Flash overlay on capture ──
    var showFlash by remember { mutableStateOf(false) }

    // ── Capture phase (from ViewModel) ──
    val capturePhase by viewModel.capturePhase.collectAsState()
    val opCapturedPhotos by viewModel.opCapturedPhotos.collectAsState()

    // Find the current target student (the one MC called to the stage)
    val db = project?.database ?: emptyList()
    val mode = project?.config?.mode ?: CameraModes.SINGLE
    val isPhotoshoot = CameraModes.isPhotoshootMode(mode)
    val isDual = CameraModes.isDualMode(mode)
    val myChannel by viewModel.myChannel.collectAsState()

    // Operator queue: in photoshoot mode, operator can pick from 'sent' students
    // (matches Electron operator-panel.tsx renderOpSearch + renderQueueList).
    // In non-photoshoot, the active student is auto-assigned by MC.
    var opSearchQuery by remember { mutableStateOf("") }
    var opSelectedStudent by remember { mutableStateOf<Student?>(null) }

    // Photoshoot: list of 'sent' students (operator's queue)
    val opSentQueue = if (isPhotoshoot) db.filter { it.status == "sent" } else emptyList()
    val opSearchResults = if (isPhotoshoot && opSearchQuery.trim().isNotEmpty()) {
        val q = opSearchQuery.trim().lowercase()
        opSentQueue.filter { it.nama.lowercase().contains(q) || it.nim.lowercase().contains(q) }
    } else opSentQueue

    // Active target:
    // - Non-photoshoot: auto from MC_CALL (active_N on our channel)
    // - Photoshoot: operator-selected, OR first 'sent' student if none selected
    val activeStudent = if (isPhotoshoot) {
        opSelectedStudent ?: opSentQueue.firstOrNull()
    } else {
        db.firstOrNull { isActiveStatus(it.status) && getActiveChannel(it.status) == myChannel }
    }
    val activeChannel = activeStudent?.let {
        if (isPhotoshoot) it.assignedChannel else (getActiveChannel(it.status) ?: myChannel)
    } ?: myChannel

    // Reset capture state when the active student changes (new student called)
    val activeStudentId = activeStudent?.id
    LaunchedEffect(activeStudentId) {
        viewModel.resetCaptureState()
    }
    // ── rememberUpdatedState refs — reads ALWAYS use the CURRENT values ──
    // Used by (1) the async PTP capture path inside triggerCapture below
    // (DSLR capture takes seconds — values may change while in flight) and
    // (2) the hand-trigger DisposableEffect further down (it only re-runs
    // when shutterMode changes, so capturing activeStudent etc. directly
    // froze them at the moment hand mode was entered — after MC called the
    // next student, photos were attributed to the WRONG student). Reading
    // through the updated State references always uses the CURRENT values.
    val currentActiveStudent by rememberUpdatedState(activeStudent)
    val currentActiveChannel by rememberUpdatedState(activeChannel)
    val currentProject by rememberUpdatedState(project)
    val currentFrameBitmap by rememberUpdatedState(frameBitmap)

    // ── PTP photo delivery — shared by the on-screen capture path (below) and
    // the physical-shutter watcher (further below). Decodes the full-res JPEG
    // data URL, applies the project frame/filters, flashes, and routes the
    // photo into the local capture pipeline. Always reads the CURRENT
    // student/channel/project/frame via the rememberUpdatedState refs above
    // (DSLR transfer takes seconds — values may change while in flight).
    val deliverPtpPhoto: (String) -> Unit = { dataUrl ->
        scope.launch(Dispatchers.Default) {
            try {
                val pure = if (dataUrl.contains(",")) dataUrl.substringAfter(",") else dataUrl
                val bytes = Base64.decode(pure, Base64.DEFAULT)
                val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (source == null) {
                    captureError = "Gagal decode foto PTP"
                    return@launch
                }
                val proj = currentProject
                val processed = if (proj != null) {
                    try { CameraCapture.processFrame(source, proj.config, currentFrameBitmap) } catch (e: Exception) { source }
                } else source
                val base64 = CameraCapture.bitmapToBase64(processed, 95)
                if (processed !== source) processed.recycle()
                source.recycle()
                showFlash = true
                scope.launch { delay(200); showFlash = false }
                val student = currentActiveStudent
                if (student != null) {
                    viewModel.handleLocalCapture(student, base64, currentActiveChannel)
                } else {
                    captureError = "Tidak ada mahasiswa aktif"
                }
            } catch (e: Exception) {
                captureError = "Gagal: ${e.message}"
            } finally {
                isCapturing = false
            }
        }
    }

    // ── Physical shutter sync (PTP) — photos taken with the camera's OWN
    // shutter button arrive here over USB (PTPCameraManager external shutter
    // watch) and are delivered through the SAME path as on-screen captures.
    // Gated on: PTP engine active + the physical shutter toggle ON.
    // - If an on-screen capture is already in flight → skip (photos would race).
    // - If a countdown timer is running → CANCEL it first: the shot already
    //   happened, letting the timer fire would double-capture.
    LaunchedEffect(Unit) {
        viewModel.ptpCameraManager.externalShutterPhotos.collect { dataUrl ->
            if (viewModel.activeCameraEngine.value != "ptp") return@collect
            if (!viewModel.physicalShutterSync.value) return@collect
            if (isCapturing) {
                Log.i("OperatorScreen", "External PTP photo skipped — capture already in flight")
                return@collect
            }
            if (timerCountdown != null || timerJob != null) {
                Log.i("OperatorScreen", "External PTP photo — cancelling countdown timer (double-capture guard)")
                timerJob?.cancel()
                timerJob = null
                timerCountdown = null
            }
            Log.i("OperatorScreen", "External PTP photo (physical shutter) → delivering")
            isCapturing = true
            deliverPtpPhoto(dataUrl)
        }
    }

    // ── FTP (WiFi transfer) — photos the camera uploads arrive here and are
    // delivered through the SAME path as on-screen captures.
    LaunchedEffect(Unit) {
        viewModel.ftpManager.ftpPhotos.collect { dataUrl ->
            if (viewModel.activeCameraEngine.value != "ftp") return@collect
            if (isCapturing) {
                Log.i("OperatorScreen", "FTP photo skipped — capture already in flight")
                return@collect
            }
            if (timerCountdown != null || timerJob != null) {
                Log.i("OperatorScreen", "FTP photo — cancelling countdown timer (double-capture guard)")
                timerJob?.cancel()
                timerJob = null
                timerCountdown = null
            }
            Log.i("OperatorScreen", "FTP photo (WiFi) → delivering")
            isCapturing = true
            deliverPtpPhoto(dataUrl)
        }
    }

    // ── Hot shoe: extract the capture logic into a reusable lambda ──
    // (used by both the on-screen shutter button + the hot shoe audio detector).
    val triggerCapture: () -> Unit = {
        if (!isCapturing && activeStudent != null && timerCountdown == null) {
            captureError = null
            val timerDuration = when (shutterMode) { "timer-3" -> 3; "timer-5" -> 5; "timer-10" -> 10; else -> 0 }
            val doCapture: () -> Unit = {
                when {
                    viewModel.activeCameraEngine.value == "ftp" -> {
                        // WiFi engine: photos come from the camera over FTP — there is
                        // nothing to trigger app-side; surface a hint instead.
                        captureError = "Mode WiFi: foto diambil dengan shutter kamera (tidak ada tombol app)"
                    }
                    viewModel.activeCameraEngine.value == "ptp" && viewModel.ptpCameraManager.isConnected.value -> {
                        // PTP path: trigger the DSLR shutter over USB, then process
                        // the full-res photo ASYNC (DSLR autofocus takes seconds).
                        isCapturing = true
                        captureError = null
                        viewModel.ptpCameraManager.capturePhoto { dataUrl ->
                            if (dataUrl == null) {
                                val reason = viewModel.ptpCameraManager.lastError.value
                                captureError = "PTP capture gagal${if (reason != null) ": $reason" else ""}"
                                isCapturing = false
                                return@capturePhoto
                            }
                            deliverPtpPhoto(dataUrl)
                        }
                    }
                    else -> {
                        // Existing Camera2/UVC path — textureView bitmap grab
                        isCapturing = true
                        try {
                            val bitmap = textureView.bitmap
                            val proj = project
                            if (bitmap != null && proj != null) {
                                val processed = try { CameraCapture.processFrame(bitmap, proj.config, frameBitmap) } catch (e: Exception) { bitmap }
                                val base64 = CameraCapture.bitmapToBase64(processed, 95)
                                showFlash = true
                                scope.launch { delay(200); showFlash = false }
                                viewModel.handleLocalCapture(activeStudent!!, base64, activeChannel)
                            } else { captureError = if (bitmap == null) "Preview belum siap." else "Proyek tidak ditemukan." }
                        } catch (e: Exception) { captureError = "Gagal: ${e.message}" } finally { isCapturing = false }
                    }
                }
            }
            if (timerDuration > 0) {
                timerCountdown = timerDuration
                timerJob = scope.launch {
                    for (i in timerDuration downTo 1) { timerCountdown = i; delay(1000) }
                    timerCountdown = null; timerJob = null; doCapture()
                }
            } else { doCapture() }
        }
    }

    // ── Hot shoe: AudioRecord pulse detector ──
    // Monitors the mic input for sharp amplitude spikes (hot shoe pulse from
    // camera's flash/shutter). When detected → increment hotShoePulseCount →
    // the LaunchedEffect below calls triggerCapture(). Uses AUDIO JACK (not
    // Bluetooth) → no conflict with BLE MC remote.
    DisposableEffect(hotShoeTrigger, hasAudioPermission) {
        // Only start the monitor thread when the toggle is ON AND the
        // RECORD_AUDIO permission is actually granted (see launcher above).
        if (!hotShoeTrigger || !hasAudioPermission) {
            onDispose { }
        } else {
            val sampleRate = 44100
            val minBuf = android.media.AudioRecord.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf, 1024)
            val audioRecord = try {
                android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: Exception) {
                Log.e("HotShoe", "AudioRecord init failed: ${e.message}")
                null
            }
            if (audioRecord != null && audioRecord.state == android.media.AudioRecord.STATE_INITIALIZED) {
                audioRecord.startRecording()
                val buffer = ShortArray(1024)
                var lastTriggerTime = 0L
                val threshold = 25000  // High — only sharp hot shoe pulses (scale 0-32767)
                val debounceMs = 2000L  // 2 seconds between triggers (anti double + anti noise)
                val monitorThread = Thread {
                    while (hotShoeTrigger && audioRecord.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            var sum = 0L
                            for (i in 0 until read) { sum += buffer[i].toLong() * buffer[i].toLong() }
                            val rms = Math.sqrt(sum.toDouble() / read).toInt()
                            if (rms > threshold) {
                                val now = System.currentTimeMillis()
                                if (now - lastTriggerTime > debounceMs) {
                                    lastTriggerTime = now
                                    Log.i("HotShoe", "Pulse detected (RMS=$rms) — triggering capture")
                                    mainHandler.post { hotShoePulseCount++ }
                                }
                            }
                        }
                    }
                }
                monitorThread.start()
                Log.i("HotShoe", "Audio pulse detector started (threshold=$threshold, debounce=${debounceMs}ms)")
            }
            onDispose {
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                Log.i("HotShoe", "Audio pulse detector stopped")
            }
        }
    }

    // ── Hot shoe: when a pulse is detected → trigger capture ──
    LaunchedEffect(hotShoePulseCount) {
        if (hotShoePulseCount > 0) {
            triggerCapture()
        }
    }

    // Clear operator selection when the selected student becomes 'done'
    LaunchedEffect(opSelectedStudent?.status) {
        if (opSelectedStudent?.status == "done") {
            opSelectedStudent = null
        }
    }

    // Wire hand trigger callback — when hand leaves frame, trigger capture.
    // (reads currentActiveStudent / currentActiveChannel / currentProject /
    // currentFrameBitmap via the rememberUpdatedState refs declared above
    // the triggerCapture lambda — this DisposableEffect only re-runs when
    // shutterMode changes, so direct captures would freeze stale values)
    DisposableEffect(shutterMode) {
        if (shutterMode == "hand") {
            com.saatiril.andro.camera.HandTriggerDetector.onHandLeft = {
                scope.launch(Dispatchers.Main) {
                    timerCountdown = 3
                    timerJob = scope.launch {
                        for (i in 3 downTo 1) {
                            timerCountdown = i
                            delay(1000)
                        }
                        timerCountdown = null
                        timerJob = null
                        val student = currentActiveStudent
                        if (student != null && !isCapturing) {
                            isCapturing = true
                            try {
                                val bitmap = textureView.bitmap
                                val proj = currentProject
                                if (bitmap != null && proj != null) {
                                    val processed = try {
                                        CameraCapture.processFrame(bitmap, proj.config, currentFrameBitmap)
                                    } catch (e: Exception) { bitmap }
                                    val base64 = CameraCapture.bitmapToBase64(processed, 95)
                                    showFlash = true
                                    scope.launch { delay(200); showFlash = false }
                                    viewModel.handleLocalCapture(student, base64, currentActiveChannel)
                                }
                            } catch (e: Exception) {
                                captureError = "Gagal capture: ${e.message}"
                            } finally {
                                isCapturing = false
                            }
                        }
                    }
                }
            }
        }
        onDispose {
            com.saatiril.andro.camera.HandTriggerDetector.onHandLeft = null
        }
    }

    // Init camera on first show (only if permission granted), release on dispose
    DisposableEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            // Set project aspect ratio so camera preview buffer matches selected ratio
            val projRatio = project?.config?.ratio ?: "4:3"
            val parts = projRatio.split(":")
            val ar = if (parts.size == 2) (parts[0].toFloatOrNull() ?: 4f) / (parts[1].toFloatOrNull() ?: 3f) else 4f / 3f
            viewModel.camera2Manager.setProjectAspectRatio(ar)

            // Bind TextureView to both managers + enumerate + open default (back camera)
            viewModel.camera2Manager.setTextureView(textureView)
            viewModel.cameraUVCManager.setTextureView(textureView)
            viewModel.ptpCameraManager.setTextureView(textureView)

            // BUG FIX: TextureView keeps only ONE surfaceTextureListener — the
            // second setTextureView call above replaced Camera2's, so Camera2
            // never received surface events (intermittent permanent black
            // preview). Install ONE shared listener LAST that fans the events
            // out to ALL managers.
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    viewModel.camera2Manager.onSurfaceTextureAvailable(width, height)
                    viewModel.cameraUVCManager.onSurfaceTextureAvailable(surface, width, height)
                    viewModel.ptpCameraManager.onSurfaceTextureAvailable(width, height)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    viewModel.camera2Manager.onSurfaceTextureSizeChanged(width, height)
                    viewModel.cameraUVCManager.onSurfaceTextureSizeChanged(surface, width, height)
                    viewModel.ptpCameraManager.onSurfaceTextureSizeChanged(width, height)
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    val c2Handled = viewModel.camera2Manager.onSurfaceTextureDestroyed()
                    val uvcHandled = viewModel.cameraUVCManager.onSurfaceTextureDestroyed()
                    val ptpHandled = viewModel.ptpCameraManager.onSurfaceTextureDestroyed()
                    return c2Handled && uvcHandled && ptpHandled
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    // Called on every frame — no-op (all managers no-op here)
                }
            }

            viewModel.camera2Manager.enumerateCameras()
            viewModel.cameraUVCManager.initCamera()
            viewModel.ptpCameraManager.initCamera()
            viewModel.refreshAvailableCameras()
            viewModel.camera2Manager.openCamera()
        }
        onDispose {
            // Cancel any in-flight countdown timer (was never cancelled on dispose)
            timerJob?.cancel()
            timerJob = null
            try { viewModel.camera2Manager.closeCamera() } catch (_: Exception) {}
            try { viewModel.cameraUVCManager.hidePreview() } catch (_: Exception) {}
            try { viewModel.ptpCameraManager.closeCamera() } catch (_: Exception) {}
            try { viewModel.ftpManager.stop() } catch (_: Exception) {}
        }
    }

    // If no camera permission, show a request screen
    if (!hasCameraPermission) {
        Column(
            modifier = Modifier.fillMaxSize().background(BG).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = GOLD, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("Izin Kamera Diperlukan", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            Text(
                "Panel Operator membutuhkan akses kamera untuk pratinjau dan tangkap foto. Klik tombol di bawah untuk berikan izin.",
                style = TextStyle(color = MUTED, fontSize = 12.sp), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Berikan Izin Kamera", color = BG, fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    // Collapsible panels state
    var showQueuePanel by remember { mutableStateOf(false) }
    var showSettingsPanel by remember { mutableStateOf(false) }

    // ── RESTRUCTURED: NO floating popups. Queue & settings are INLINE panels
    // below the shutter button (show/hide). They take vertical space from the
    // camera preview, so the camera shrinks when a panel is open — but the
    // shutter button is ALWAYS visible and NEVER covered.
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BG),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // ─── Compact target info bar (single line, ~4% of screen) ───
        // HIGHLIGHTED: gold-tinted bg + gold border + gold bold name so the
        // operator can clearly read + compare this name with the one MC sent
        // (matches the React OperatorPanel's isActive row highlight).
        activeStudent?.let { student ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(GOLD.copy(alpha = 0.18f))
                    .border(1.5.dp, GOLD, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = GOLD, modifier = Modifier.size(14.dp))
                Text(student.nama.take(25).ifBlank { student.nim.take(25) }, style = TextStyle(color = GOLD, fontWeight = FontWeight.Black, fontSize = 12.sp), modifier = Modifier.weight(1f), maxLines = 1)
                Text(student.nim.take(15), style = TextStyle(color = MUTED, fontSize = 9.sp, fontFamily = FontFamily.Monospace))
                Card(colors = CardDefaults.cardColors(containerColor = GOLD), shape = RoundedCornerShape(3.dp)) {
                    Text("Ch.$activeChannel", Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = TextStyle(color = BG, fontWeight = FontWeight.Bold, fontSize = 8.sp))
                }
            }
        } ?: run {
            // Plan B: Manual mode when MC is disconnected/unavailable
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MUTED, modifier = Modifier.size(12.dp))
                Text(" Menunggu MC…", style = TextStyle(color = MUTED, fontSize = 10.sp))
                Spacer(Modifier.weight(1f))
                // Plan B: Manual PANGGIL button — operator can call next student directly
                val db = project?.database ?: emptyList()
                val nextPending = db.firstOrNull { it.status == "pending" }
                if (nextPending != null) {
                    Button(
                        onClick = { viewModel.callStudent(nextPending, 1) },
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AMBER),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp), tint = BG)
                        Spacer(Modifier.width(4.dp))
                        Text("PANGGIL MANUAL", color = BG, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ─── Camera preview — aspect-ratio-locked to project ratio ───
        // Camera preview — aspect-ratio-locked to project ratio.
        // For portrait ratios (3:4, 9:16, 2:3, 4:6): use fillMaxHeight so the
        // preview is constrained by HEIGHT (not width) → portrait orientation.
        // For landscape/square ratios (4:3, 16:9, 1:1): use fillMaxWidth.
        val ratio = project?.config?.ratio ?: "4:3"
        val aspectRatio = when (ratio) {
            "4:3" -> 4f / 3f
            "3:4" -> 3f / 4f
            "16:9" -> 16f / 9f
            "9:16" -> 9f / 16f
            "2:3" -> 2f / 3f
            "4:6" -> 4f / 6f
            "1:1" -> 1f
            else -> 4f / 3f
        }
        val isPortrait = aspectRatio < 1f  // height > width

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .border(1.dp, BORDER, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            // Inner Box: aspect-ratio-locked container for camera + overlays
            // Portrait: fillMaxHeight + aspectRatio → constrains width
            // Landscape: fillMaxWidth + aspectRatio → constrains height
            Box(
                modifier = Modifier
                    .then(
                        if (isPortrait) Modifier.fillMaxHeight().aspectRatio(aspectRatio)
                        else Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                    )
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { textureView },
                    modifier = Modifier.fillMaxSize().then(if (mirror) Modifier.scale(scaleX = -1f, scaleY = 1f) else Modifier)
                )

                // Gridline overlay (inside ratio box so it matches the preview)
                if (gridlineEnabled) {
                    GridlineCanvas(
                        type = gridlineType,
                        thickness = gridlineThickness,
                        colorName = gridlineColor,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Frame overlay on top of the preview — MATCHES ELECTRON exactly.
                // Electron: <img src={frameData} className="absolute inset-0 w-full h-full object-fill" style={{ zIndex: 5 }} />
                // The frame PNG is transparent in the center (where camera shows through)
                // and opaque at the borders (decorative frame with text/logos).
                // object-fill = ContentScale.FillBounds in Compose (stretches to fill container).
                frameBitmap?.let { fb ->
                    Image(
                        bitmap = fb.asImageBitmap(),
                        contentDescription = "Frame overlay",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }

                // Capture error overlay (inside ratio box)
                captureError?.let { err ->
                    Card(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp),
                        colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.8f)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(err, Modifier.padding(4.dp), style = TextStyle(color = Color.White, fontSize = 8.sp))
                    }
                }
            } // end inner aspect-ratio Box

            // Camera status + source overlay (OUTER box — always visible, not clipped by ratio)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(Modifier.size(5.dp).clip(RoundedCornerShape(2.dp)).background(if (cameraConnected) GREEN else RED))
                val srcLabel = when (cameraSource) { "uvc" -> "USB"; "builtin" -> "HP"; "ptp" -> "PTP"; "ftp" -> "FTP"; else -> "-" }
                Text(
                    "Ch.$activeChannel • $srcLabel ${if (cameraConnected) "●" else "○"}",
                    style = TextStyle(color = Color.White, fontSize = 9.sp)
                )
                // #8: Aspect-ratio + frame + gridline badges (matches Electron operator-panel.tsx:1762-1777)
                if (project?.config?.frame != null) Text("Frame", style = TextStyle(color = GREEN, fontSize = 7.sp))
                Text(project?.config?.ratio ?: "4:3", style = TextStyle(color = MUTED, fontSize = 7.sp))
                if (gridlineEnabled) Text(gridlineType, style = TextStyle(color = MUTED, fontSize = 7.sp))
            }

            // #9: NO CAMERA SIGNAL overlay (matches Electron operator-panel.tsx:1749-1760)
            if (!cameraConnected) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.VideocamOff, contentDescription = null, tint = MUTED.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
                    Text("NO CAMERA SIGNAL", style = TextStyle(color = MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                    Text("Cek koneksi kamera atau pilih kamera lain", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 8.sp))
                }
            }

            // Hand trigger state indicator (matches Electron operator-panel.tsx:1708-1721)
            if (shutterMode == "hand" && handState != com.saatiril.andro.camera.HandTriggerDetector.HandState.NONE) {
                Card(
                    modifier = Modifier.align(Alignment.TopCenter).padding(4.dp),
                    colors = CardDefaults.cardColors(containerColor = when (handState) {
                        com.saatiril.andro.camera.HandTriggerDetector.HandState.HAND_DETECTED -> Color.Black.copy(alpha = 0.7f)
                        com.saatiril.andro.camera.HandTriggerDetector.HandState.CONFIRMED -> GREEN.copy(alpha = 0.8f)
                        com.saatiril.andro.camera.HandTriggerDetector.HandState.TRIGGERED -> GOLD.copy(alpha = 0.8f)
                        else -> Color.Black.copy(alpha = 0.7f)
                    }),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    val handLabel = when (handState) {
                        com.saatiril.andro.camera.HandTriggerDetector.HandState.HAND_DETECTED -> "Tangan terdeteksi…"
                        com.saatiril.andro.camera.HandTriggerDetector.HandState.CONFIRMED -> "Tangan ✓ — lepaskan untuk foto"
                        com.saatiril.andro.camera.HandTriggerDetector.HandState.TRIGGERED -> "Timer berjalan!"
                        else -> ""
                    }
                    val handTint = when (handState) {
                        com.saatiril.andro.camera.HandTriggerDetector.HandState.HAND_DETECTED -> MUTED
                        com.saatiril.andro.camera.HandTriggerDetector.HandState.CONFIRMED -> BG
                        com.saatiril.andro.camera.HandTriggerDetector.HandState.TRIGGERED -> BG
                        else -> MUTED
                    }
                    Text(
                        handLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = TextStyle(color = handTint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    )
                }
            }

            // Camera picker dropdown button (top-right) — 3 cameras: USB / Belakang / Depan
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                IconButton(
                    onClick = { showCameraPicker = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Pilih Kamera", tint = GOLD, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = showCameraPicker,
                    onDismissRequest = { showCameraPicker = false },
                    modifier = Modifier.background(PANEL)
                ) {
                    // Header
                    Text("Pilih Kamera", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = TextStyle(color = GOLD, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    HorizontalDivider(color = BORDER, thickness = 0.5.dp)

                    if (availableCameras.isNotEmpty()) {
                        availableCameras.forEach { (cameraId, displayName) ->
                            val isCurrentlySelected = cameraId == currentCameraId
                            val isUsb = displayName.contains("USB", ignoreCase = true)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(
                                            when {
                                                displayName.contains("PTP", ignoreCase = true) -> Icons.Default.PhotoCamera
                                                displayName.contains("WiFi", ignoreCase = true) -> Icons.Default.Wifi
                                                isUsb -> Icons.Default.Usb
                                                displayName.contains("Depan", ignoreCase = true) -> Icons.Default.CameraFront
                                                else -> Icons.Default.CameraAlt
                                            },
                                            contentDescription = null,
                                            tint = if (isCurrentlySelected) GREEN else MUTED,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(displayName, style = TextStyle(
                                            color = if (isCurrentlySelected) GREEN else Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = if (isCurrentlySelected) FontWeight.Bold else FontWeight.Normal
                                        ))
                                        if (isCurrentlySelected) Text("✓", style = TextStyle(color = GREEN, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                                    }
                                },
                                onClick = {
                                    viewModel.switchToCameraById(cameraId)
                                    showCameraPicker = false
                                }
                            )
                        }
                    } else {
                        Text("Memuat kamera...", modifier = Modifier.padding(12.dp),
                            style = TextStyle(color = MUTED, fontSize = 12.sp))
                    }

                    HorizontalDivider(color = BORDER, thickness = 0.5.dp)
                    // Force rescan USB
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = CYAN, modifier = Modifier.size(14.dp))
                                Text("Pindai Ulang USB", style = TextStyle(color = CYAN, fontSize = 11.sp))
                            }
                        },
                        onClick = {
                            viewModel.forceRescanUsbCamera()
                            showCameraPicker = false
                        }
                    )
                }
            }

            // #4: Timer countdown overlay ON camera preview (big number, centered)
            // Matches Electron operator-panel.tsx:1686-1706
            if (timerCountdown != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            "$timerCountdown",
                            modifier = Modifier.padding(horizontal = 30.dp, vertical = 12.dp),
                            style = TextStyle(color = GOLD, fontSize = 48.sp, fontWeight = FontWeight.Black)
                        )
                    }
                }
            }

            // Flash overlay on capture
            if (showFlash) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.6f)))
            }

            // ── COMPACT QUEUE OVERLAY (inside camera, bottom-left) ──
            // Does NOT affect camera size or aspect ratio — it floats on top.
            // Shows 4-5 items max, auto-scrolls to active student, highlights gold.
            if (showQueuePanel) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .width(150.dp)
                        .zIndex(10f),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.75f)),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GOLD.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(4.dp)) {
                        // Header
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(Icons.Default.List, contentDescription = null, tint = GOLD, modifier = Modifier.size(10.dp))
                            Text(
                                if (isPhotoshoot) "Dikirim" else "Ch.$myChannel",
                                style = TextStyle(color = GOLD, fontSize = 8.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f)
                            )
                            Card(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).clickable { showQueuePanel = false },
                                colors = CardDefaults.cardColors(containerColor = BORDER)) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Close, contentDescription = "Tutup", tint = MUTED, modifier = Modifier.size(8.dp))
                                }
                            }
                        }
                        HorizontalDivider(color = BORDER.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 1.dp))
                        // Queue list — original order, NO sorting
                        // Active student stays at original position with gold highlight
                        val opQueueList = if (isPhotoshoot) {
                            opSentQueue
                        } else {
                            db.filter { it.assignedChannel == myChannel && (it.status == "pending" || isActiveStatus(it.status)) }
                        }
                        if (opQueueList.isEmpty()) {
                            Text("Kosong", style = TextStyle(color = MUTED, fontSize = 8.sp), modifier = Modifier.padding(4.dp))
                        } else {
                            // Find active index for auto-scroll
                            val activeIdx = opQueueList.indexOfFirst { isActiveStatus(it.status) || (isPhotoshoot && it.id == activeStudent?.id) }
                            val scrollQ = rememberScrollState()
                            LaunchedEffect(activeIdx, opQueueList.size) {
                                kotlinx.coroutines.delay(50)
                                if (activeIdx >= 0) scrollQ.animateScrollTo(activeIdx * 28) // ~28dp per row
                            }
                            Column(
                                modifier = Modifier.heightIn(max = 112.dp).verticalScroll(scrollQ), // 4 items × 28dp
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                opQueueList.forEachIndexed { i, s ->
                                    val isActive = isActiveStatus(s.status) || (isPhotoshoot && s.id == activeStudent?.id)
                                    val isSelected = s.id == activeStudent?.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(if (isPhotoshoot) Modifier.clickable { opSelectedStudent = s } else Modifier)
                                            .padding(horizontal = 3.dp, vertical = 2.dp)
                                            .then(if (isActive) Modifier.background(GOLD.copy(alpha = 0.15f), RoundedCornerShape(3.dp)) else Modifier),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("${i+1}", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 7.sp), modifier = Modifier.width(10.dp))
                                        Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(if (isActive) GOLD else MUTED.copy(alpha = 0.3f)))
                                        Text(
                                            s.nama.take(18).ifBlank { s.nim.take(18) },
                                            style = TextStyle(
                                                color = if (isActive) GOLD else Color.White,
                                                fontSize = 8.sp,
                                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1
                                        )
                                        if (isActive) Text("◆", style = TextStyle(color = GOLD, fontSize = 6.sp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── COMPACT SETTINGS OVERLAY (inside camera, top-right below camera picker) ──
            // Does NOT affect camera size — floats on top.
            if (showSettingsPanel) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 44.dp, end = 4.dp)
                        .width(160.dp)
                        .zIndex(10f),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GOLD.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = GOLD, modifier = Modifier.size(10.dp))
                            Text("Pengaturan", style = TextStyle(color = GOLD, fontSize = 8.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                            Card(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).clickable { showSettingsPanel = false },
                                colors = CardDefaults.cardColors(containerColor = BORDER)) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Close, contentDescription = "Tutup", tint = MUTED, modifier = Modifier.size(8.dp))
                                }
                            }
                        }
                        HorizontalDivider(color = BORDER.copy(alpha = 0.5f), thickness = 0.5.dp)
                        // Phase
                        val phaseLabel = when (capturePhase) { CapturePhase.STANDBY -> "Standby"; CapturePhase.READY_1 -> "Pose 1 — Toga"; CapturePhase.READY_2 -> "Pose 2 — Ijazah"; CapturePhase.SENDING -> "Mengirim..." }
                        Text(phaseLabel, style = TextStyle(color = CYAN, fontSize = 7.sp, fontWeight = FontWeight.Bold))
                        // Grid toggle row
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Card(modifier = Modifier.clip(RoundedCornerShape(3.dp)).clickable { gridlineEnabled = !gridlineEnabled }.border(1.dp, if (gridlineEnabled) GOLD else BORDER, RoundedCornerShape(3.dp)),
                                colors = CardDefaults.cardColors(containerColor = if (gridlineEnabled) CARD else Color.Transparent), shape = RoundedCornerShape(3.dp)) {
                                Text("Grid", modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp), style = TextStyle(color = if (gridlineEnabled) GOLD else MUTED, fontSize = 7.sp, fontWeight = FontWeight.Bold))
                            }
                            if (gridlineEnabled) {
                                listOf("thirds" to "⅓", "quarters" to "¼", "crosshair" to "+", "diagonal" to "╳").forEach { (t, l) ->
                                    Card(modifier = Modifier.clip(RoundedCornerShape(2.dp)).clickable { gridlineType = t }.border(1.dp, if (gridlineType == t) GOLD else BORDER, RoundedCornerShape(2.dp)),
                                        colors = CardDefaults.cardColors(containerColor = if (gridlineType == t) CARD else Color.Transparent), shape = RoundedCornerShape(2.dp)) { Text(l, modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp), style = TextStyle(color = if (gridlineType == t) GOLD else MUTED, fontSize = 7.sp, fontWeight = FontWeight.Bold)) }
                                }
                            }
                        }
                        if (gridlineEnabled) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                listOf(1 to "T", 2 to "M", 3 to "K").forEach { (th, l) ->
                                    Card(modifier = Modifier.clip(RoundedCornerShape(2.dp)).clickable { gridlineThickness = th }.border(1.dp, if (gridlineThickness == th) GOLD else BORDER, RoundedCornerShape(2.dp)),
                                        colors = CardDefaults.cardColors(containerColor = if (gridlineThickness == th) CARD else Color.Transparent), shape = RoundedCornerShape(2.dp)) { Text(l, modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp), style = TextStyle(color = if (gridlineThickness == th) GOLD else MUTED, fontSize = 7.sp, fontWeight = FontWeight.Bold)) }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                listOf("white" to Color.White, "yellow" to Color(0xFFfacc15), "red" to Color(0xFFef4444), "cyan" to Color(0xFF06b6d4), "green" to Color(0xFF22c55e)).forEach { (n, c) ->
                                    Card(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).clickable { gridlineColor = n }.border(2.dp, if (gridlineColor == n) GOLD else Color.Transparent, RoundedCornerShape(5.dp)),
                                        colors = CardDefaults.cardColors(containerColor = c), shape = RoundedCornerShape(5.dp)) {}
                                }
                            }
                        }
                    }
                }
            }
        } // end camera preview Box

        // ─── SHUTTER CONTROLS BAR (always visible, never covered) ───
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PANEL.copy(alpha = 0.95f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Row 1: Shutter modes (compact) — MANUAL / 3s / 5s / 10s / HAND
            // (hidden in FTP/WiFi mode — the camera's OWN shutter button takes
            // the photo, app-side timer/hand modes don't apply)
            if (activeEngine != "ftp") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("manual" to "M", "timer-3" to "3s", "timer-5" to "5s", "timer-10" to "10s", "hand" to "✋").forEach { (mode, label) ->
                        Card(modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).clickable { shutterMode = mode }
                            .border(1.dp, if (shutterMode == mode) GOLD else BORDER, RoundedCornerShape(4.dp)),
                            colors = CardDefaults.cardColors(containerColor = if (shutterMode == mode) CARD else PANEL), shape = RoundedCornerShape(4.dp)) {
                            Text(label, modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp).fillMaxWidth(),
                                style = TextStyle(color = if (shutterMode == mode) GOLD else MUTED, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
                        }
                    }
                }
            }
            // Row 1.5: Mirror toggle (flip horizontal preview — does NOT affect
            // the captured photo, only the live preview).
            Card(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { mirror = !mirror }
                .border(1.dp, if (mirror) GOLD else BORDER, RoundedCornerShape(4.dp)),
                colors = CardDefaults.cardColors(containerColor = if (mirror) CARD else PANEL), shape = RoundedCornerShape(4.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⇄", style = TextStyle(color = if (mirror) GOLD else MUTED, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                    Text("Mirror", style = TextStyle(color = if (mirror) GOLD else MUTED, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                    if (mirror) { Spacer(Modifier.weight(1f)); Text("Aktif ✓", style = TextStyle(color = GOLD, fontSize = 8.sp)) }
                }
            }
            // Row 1.6: Hot shoe trigger toggle (camera physical shutter → audio
            // pulse → capture). Uses audio jack (NOT Bluetooth) → no conflict
            // with BLE MC remote. Connect hot shoe adapter to 3.5mm audio jack.
            // Requires the RECORD_AUDIO runtime permission — requested on
            // first toggle ON (was never requested before → dead trigger).
            Card(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable {
                    if (!hotShoeTrigger && !hasAudioPermission) {
                        showAudioPermissionDenied = false
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        hotShoeTrigger = !hotShoeTrigger
                    }
                }
                .border(1.dp, if (hotShoeTrigger) GOLD else BORDER, RoundedCornerShape(4.dp)),
                colors = CardDefaults.cardColors(containerColor = if (hotShoeTrigger) CARD else PANEL), shape = RoundedCornerShape(4.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📸", style = TextStyle(color = if (hotShoeTrigger) GOLD else MUTED, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                    Text("Hot Shoe", style = TextStyle(color = if (hotShoeTrigger) GOLD else MUTED, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                    if (hotShoeTrigger) { Spacer(Modifier.weight(1f)); Text("Aktif ✓", style = TextStyle(color = GOLD, fontSize = 8.sp)) }
                    else { Spacer(Modifier.weight(1f)); Text("Audio trigger", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 7.sp)) }
                }
            }
            // Hot shoe permission denied hint
            if (showAudioPermissionDenied) {
                Text(
                    "Izin mikrofon (RECORD_AUDIO) ditolak — hot shoe trigger tidak bisa mendeteksi denyut audio.",
                    style = TextStyle(color = RED, fontSize = 8.sp),
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
            // Row 1.7: Physical shutter sync (PTP only) — photos taken with the
            // camera's OWN shutter button are detected over USB and delivered
            // through the same local capture pipeline as the on-screen shutter.
            // Gates ONLY external-photo delivery — the on-screen shutter button
            // always works regardless of this toggle.
            if (activeEngine == "ptp") {
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
                        onCheckedChange = { viewModel.setPhysicalShutterSync(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CYAN,
                            checkedTrackColor = CYAN.copy(alpha = 0.4f),
                            uncheckedThumbColor = MUTED,
                            uncheckedTrackColor = BORDER
                        )
                    )
                }
            }
            // Row 1.75: USB safe mode (PTP only) — no interrupt-endpoint reads +
            // settle delay after claim. Workaround for phones whose USB driver
            // restarts the phone during PTP transfers. ON by default.
            if (activeEngine == "ptp") {
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
                        onCheckedChange = { viewModel.setUsbSafeMode(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CYAN,
                            checkedTrackColor = CYAN.copy(alpha = 0.4f),
                            uncheckedThumbColor = MUTED,
                            uncheckedTrackColor = BORDER
                        )
                    )
                }
            }
            // Row 2: Shutter button + Queue toggle + Settings toggle
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val shutterLabel = when {
                    isCapturing -> "..."
                    capturePhase == CapturePhase.READY_2 -> "Ijazah"
                    capturePhase == CapturePhase.SENDING -> "Kirim"
                    else -> if (CameraModes.isPhotoshootMode(project?.config?.mode ?: CameraModes.SINGLE)) "Foto" else "Toga"
                }
                if (activeEngine == "ftp") {
                    // WiFi (FTP) transfer mode — passive status card replaces the
                    // shutter button: photos are taken with the camera's OWN
                    // shutter button and arrive over WiFi, there is nothing to
                    // trigger app-side (same visual language as the PTP rows above).
                    Row(
                        modifier = Modifier.weight(1f).heightIn(min = 60.dp).clip(RoundedCornerShape(4.dp))
                            .background(CARD)
                            .border(1.dp, CYAN, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = CYAN, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Menunggu foto dari kamera…", style = TextStyle(color = CYAN, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                            Text("FTP ${ftpServerAddress ?: "—"} ${if (ftpClientConnected) "• kamera terhubung" else "• menunggu kamera"}", style = TextStyle(color = MUTED, fontSize = 8.sp))
                            ftpLastFile?.let { f -> Text("Terakhir: $f", style = TextStyle(color = MUTED, fontSize = 8.sp)) }
                            ftpLastError?.let { e -> Text(e, style = TextStyle(color = RED, fontSize = 8.sp)) }
                        }
                    }
                } else {
                    Button(
                        onClick = { triggerCapture() },
                        modifier = Modifier.weight(1f).height(60.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (activeStudent != null && !isCapturing && timerCountdown == null) GOLD else BORDER),
                        shape = RoundedCornerShape(8.dp), enabled = activeStudent != null && !isCapturing && timerCountdown == null,
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        if (isCapturing) { CircularProgressIndicator(modifier = Modifier.size(20.dp), color = BG, strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)); Text("Simpan", color = BG, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        else if (timerCountdown != null) { Text("$timerCountdown", color = BG, fontSize = 18.sp, fontWeight = FontWeight.Black) }
                        else { Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(24.dp), tint = BG); Spacer(Modifier.width(6.dp)); Text(shutterLabel, color = BG, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                // Queue toggle — shows/hides inline queue panel BELOW this bar
                Card(modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).clickable { showQueuePanel = !showQueuePanel; showSettingsPanel = false }
                    .border(1.dp, if (showQueuePanel) GOLD else BORDER, RoundedCornerShape(8.dp)),
                    colors = CardDefaults.cardColors(containerColor = if (showQueuePanel) CARD else PANEL), shape = RoundedCornerShape(8.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.List, contentDescription = "Antrean", tint = if (showQueuePanel) GOLD else MUTED, modifier = Modifier.size(18.dp)) }
                }
                // Settings toggle — shows/hides inline settings panel BELOW this bar
                Card(modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).clickable { showSettingsPanel = !showSettingsPanel; showQueuePanel = false }
                    .border(1.dp, if (showSettingsPanel) GOLD else BORDER, RoundedCornerShape(8.dp)),
                    colors = CardDefaults.cardColors(containerColor = if (showSettingsPanel) CARD else PANEL), shape = RoundedCornerShape(8.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.Tune, contentDescription = "Pengaturan", tint = if (showSettingsPanel) GOLD else MUTED, modifier = Modifier.size(18.dp)) }
                }
            }
            // Timer cancel
            if (timerCountdown != null) {
                Button(onClick = { timerJob?.cancel(); timerJob = null; timerCountdown = null },
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RED), shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)) { Text("BATAL", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        } // end shutter controls bar
    } // end main Column
}

// ─── Gridline Canvas (matches Electron SVG gridlines) ────────
@Composable
private fun GridlineCanvas(type: String, thickness: Int = 1, colorName: String = "white", modifier: Modifier = Modifier) {
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
                drawLine(color, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
                drawLine(color, Offset(2 * w / 3f, 0f), Offset(2 * w / 3f, h), stroke)
                drawLine(color, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
                drawLine(color, Offset(0f, 2 * h / 3f), Offset(w, 2 * h / 3f), stroke)
            }
            "quarters" -> {
                drawLine(color, Offset(w / 4f, 0f), Offset(w / 4f, h), stroke)
                drawLine(color, Offset(w / 2f, 0f), Offset(w / 2f, h), stroke)
                drawLine(color, Offset(3 * w / 4f, 0f), Offset(3 * w / 4f, h), stroke)
                drawLine(color, Offset(0f, h / 4f), Offset(w, h / 4f), stroke)
                drawLine(color, Offset(0f, h / 2f), Offset(w, h / 2f), stroke)
                drawLine(color, Offset(0f, 3 * h / 4f), Offset(w, 3 * h / 4f), stroke)
            }
            "crosshair" -> {
                drawLine(color, Offset(w / 2f, 0f), Offset(w / 2f, h), stroke)
                drawLine(color, Offset(0f, h / 2f), Offset(w, h / 2f), stroke)
                drawCircle(color, minOf(w, h) / 6f, center = Offset(w / 2f, h / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            }
            "diagonal" -> {
                drawLine(color, Offset(0f, 0f), Offset(w, h), stroke)
                drawLine(color, Offset(w, 0f), Offset(0f, h), stroke)
                val faint = color.copy(alpha = 0.15f)
                drawLine(faint, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
                drawLine(faint, Offset(2 * w / 3f, 0f), Offset(2 * w / 3f, h), stroke)
                drawLine(faint, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
                drawLine(faint, Offset(0f, 2 * h / 3f), Offset(w, 2 * h / 3f), stroke)
            }
        }
    }
}
