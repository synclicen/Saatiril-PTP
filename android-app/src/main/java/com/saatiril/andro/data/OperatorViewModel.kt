package com.saatiril.andro.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saatiril.andro.camera.Camera2Manager
import com.saatiril.andro.camera.CameraCapture
import com.saatiril.andro.camera.HandTriggerDetector
import com.saatiril.andro.camera.PTPCameraManager
import com.saatiril.andro.camera.UVCCameraManager
import com.saatiril.andro.util.FilenameUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ═════════════════════════════════════════════════════════════════════════
 * Central ViewModel — v10 Dual Camera Engine (UVCCamera + CameraX)
 * ═════════════════════════════════════════════════════════════════════════
 *
 * v10 CHANGES (fundamental architectural shift — NATIVE USB camera):
 * - Camera: DualCameraManager replaces WebViewCameraManager.
 *   UVCCamera (com.herohan:UVCAndroid) talks DIRECTLY to USB hardware
 *   via USB Host API, bypassing Android's broken Camera2 HAL entirely.
 *   CameraX handles built-in cameras (which work fine).
 * - USB Detection: UVCCamera's USBMonitor detects USB devices natively.
 *   No more WebView/getUserMedia (which couldn't see USB cameras on Android).
 * - Photo Capture: UVCCamera IFrameCallback → NV21 → JPEG → base64.
 *   CameraX ImageCapture → JPEG → base64. No JavaScript involved.
 * - Photo Saving: REMOVED. Photos are NOT saved on the operator device.
 *   They are sent via socket.io to the admin who saves them (matching
 *   the Electron browser-mode behavior exactly).
 * - Frame Overlay: TODO: apply in Kotlin (not JavaScript).
 *
 * Camera priority:
 * 1. USB/External camera (UVCCamera) — auto-selected if present
 * 2. Built-in back camera (CameraX) — fallback
 * 3. Built-in front camera (CameraX) — last resort
 */
class OperatorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OperatorViewModel"
        private const val STATE_REQUEST_INTERVAL_MS = 3000L
    }

    private val socketManager = SocketManager()

    // ─── Camera Managers ──────────────────────────────────────────
    // UVC: USB capture cards (MacroSilicon, etc.) — DO NOT MODIFY THIS
    // Camera2: Built-in phone cameras (front/back)

    val cameraUVCManager = UVCCameraManager(application)
    val camera2Manager = Camera2Manager(application)

    // PTP: direct DSLR control over USB (Picture Transfer Protocol)
    val ptpCameraManager = PTPCameraManager(application)

    // ─── Connection State ───────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _myRole = MutableStateFlow<String>(Roles.OPERATOR)
    val myRole: StateFlow<String> = _myRole.asStateFlow()

    private val _myChannel = MutableStateFlow(1)
    val myChannel: StateFlow<Int> = _myChannel.asStateFlow()

    private val _latencyMs = MutableStateFlow(-1L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    private val _passwordRequired = MutableStateFlow(false)
    val passwordRequired: StateFlow<Boolean> = _passwordRequired.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    // ─── Project State ──────────────────────────────────────────

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _currentTarget = MutableStateFlow<Student?>(null)
    val currentTarget: StateFlow<Student?> = _currentTarget.asStateFlow()

    // ─── Capture State ──────────────────────────────────────────

    private val _capturePhase = MutableStateFlow(CapturePhase.STANDBY)
    val capturePhase: StateFlow<CapturePhase> = _capturePhase.asStateFlow()

    private val _capturedPhotos = MutableStateFlow<List<String>>(emptyList())
    val capturedPhotos: StateFlow<List<String>> = _capturedPhotos.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    // BUG #4 fix: surface photo-send errors to the operator UI. The previous
    // implementation logged errors and silently advanced the operator to the
    // next target — the operator had no idea their photos never reached the
    // admin. The UI binds to this flow and shows a Snackbar / red banner when
    // a send fails so the operator can manually retry (re-capture).
    private val _captureError = MutableStateFlow<String?>(null)
    val captureError: StateFlow<String?> = _captureError.asStateFlow()

    /** Clear the last capture error (called by the UI when the user dismisses it). */
    fun clearCaptureError() { _captureError.value = null }

    // BUG #4 fix: app-scoped CoroutineScope so the photo retry flush outlives
    // ViewModel clear (matches AdminViewModel.appScope rationale in BUG #9).
    private val opAppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // BUG #4 fix: file-based persistence for un-delivered photos. The
    // SocketManager already has an IN-MEMORY retry queue (CRITICAL_EVENTS),
    // but that queue is lost if the operator app is killed (OOM, low battery,
    // user swipe-away) before the socket reconnects. This file queue is the
    // backup: every finalizeCapture writes the photo data here BEFORE marking
    // done, and a startup flush re-sends any leftover items once the socket
    // reconnects. Photos are removed from the queue only after a confirmed
    // send (or after MAX_QUEUE_RETRIES attempts — then they're left on disk
    // for manual recovery).
    private val photoQueueDir: File by lazy {
        File(getApplication<Application>().filesDir, "operator_photo_queue").apply { mkdirs() }
    }

    // ─── Gridline Settings ──────────────────────────────────────

    private val _gridlineSettings = MutableStateFlow(GridlineSettings())
    val gridlineSettings: StateFlow<GridlineSettings> = _gridlineSettings.asStateFlow()

    // ─── Shutter Mode ──────────────────────────────────────────

    private val _shutterMode = MutableStateFlow("manual")
    val shutterMode: StateFlow<String> = _shutterMode.asStateFlow()

    private val _timerCountdown = MutableStateFlow(0)
    val timerCountdown: StateFlow<Int> = _timerCountdown.asStateFlow()

    private var timerJob: Job? = null

    // ─── Hand Trigger (Trigger Tangan) ──────────────────────────

    private val _handState = MutableStateFlow(HandTriggerDetector.HandState.NONE)
    val handState: StateFlow<HandTriggerDetector.HandState> = _handState.asStateFlow()

    // ─── Op Search ──────────────────────────────────────────────

    private val _opSearchQuery = MutableStateFlow("")
    val opSearchQuery: StateFlow<String> = _opSearchQuery.asStateFlow()

    // ─── MC Call Buffer ────────────────────────────────────────

    private val _mcCallBuffer = MutableStateFlow<List<Student>>(emptyList())
    val mcCallBuffer: StateFlow<List<Student>> = _mcCallBuffer.asStateFlow()

    // ─── Operator Queue ─────────────────────────────────────────

    private val _opQueue = MutableStateFlow<List<Student>>(emptyList())
    val opQueue: StateFlow<List<Student>> = _opQueue.asStateFlow()

    private val _channelStudents = MutableStateFlow<List<Student>>(emptyList())
    val channelStudents: StateFlow<List<Student>> = _channelStudents.asStateFlow()

    private val _savePath = MutableStateFlow("")
    val savePath: StateFlow<String> = _savePath.asStateFlow()

    /**
     * Recompute the operator queue whenever project, mcCallBuffer, or myChannel changes.
     */
    private fun updateOpQueue() {
        val proj = _project.value
        val mode = proj?.config?.mode ?: CameraModes.SINGLE
        val isPhotoshoot = CameraModes.isPhotoshootMode(mode)
        val myCh = _myChannel.value

        _channelStudents.value = if (proj == null) {
            emptyList()
        } else if (isPhotoshoot) {
            proj.database
        } else {
            proj.database.filter { it.assignedChannel == myCh }
        }

        val newQueue = if (proj == null) {
            _mcCallBuffer.value
        } else if (isPhotoshoot) {
            val db = proj.database
            val alreadyPhotographed = proj.photoHistory
                .filter { it.channel == myCh }
                .map { it.student.id }.toSet()
            val doneIds = db.filter { it.status == "done" }.map { it.id }.toSet()

            // dbItems: students to photograph = 'sent' OR 'active_N', NOT 'done'.
            // NOTE: do NOT exclude via alreadyPhotographed here. A 'sent'/'active_N'
            // student is, by definition, NOT yet photographed (photographed students
            // are 'done' = excluded via doneIds). The alreadyPhotographed check
            // previously excluded 'sent' students when the operator's persisted
            // photoHistory had STALE entries (from a previous session or a reset
            // whose cleared photoHistory didn't persist before close). That caused
            // the operator to LOSE the queue after close+reopen — the PROSES count
            // was correct (DB has 'sent' students) but the queue list was empty.
            // Now 'sent'/'active_N' students always show in the queue regardless of
            // stale photoHistory (mirrors the Electron fix: commits f96e64f + 85b5017).
            val sentFromDb = db.filter { student ->
                (student.status == "sent" || isActiveStatus(student.status)) &&
                !doneIds.contains(student.id)
            }
            val sentIds = sentFromDb.map { it.id }.toSet()

            val bufferAdditions = _mcCallBuffer.value.filter {
                !sentIds.contains(it.id) && !doneIds.contains(it.id) && !alreadyPhotographed.contains(it.id)
            }
            sentFromDb + bufferAdditions
        } else {
            _channelStudents.value
        }

        _opQueue.value = newQueue

        Log.d(TAG, "updateOpQueue: queueSize=${newQueue.size}, channelSize=${_channelStudents.value.size}, mode=$mode, isPhotoshoot=$isPhotoshoot, myCh=$myCh")
    }

    // ─── Camera Source ──────────────────────────────────────────

    private val _cameraSource = MutableStateFlow("none")
    val cameraSource: StateFlow<String> = _cameraSource.asStateFlow()

    private val _uvcDeviceAttached = MutableStateFlow(false)
    val uvcDeviceAttached: StateFlow<Boolean> = _uvcDeviceAttached.asStateFlow()

    private val _cameraConnected = MutableStateFlow(false)
    val cameraConnected: StateFlow<Boolean> = _cameraConnected.asStateFlow()

    // Track which camera engine is active: "uvc" or "camera2"
    private val _activeCameraEngine = MutableStateFlow("uvc")
    val activeCameraEngine: StateFlow<String> = _activeCameraEngine.asStateFlow()

    // Merged available cameras from UVC + Camera2
    private val _allAvailableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _allAvailableCameras.asStateFlow()

    // Hoisted to a single stable StateFlow — the old custom getter returned a
    // different flow instance depending on the active engine, which made
    // Compose re-subscribe collectAsState on every recomposition.
    private val _currentCameraId = MutableStateFlow("")
    val currentCameraId: StateFlow<String> = _currentCameraId.asStateFlow()

    // ─── Physical shutter sync (PTP only) ─────────────────────
    // When the PTP engine is active and this is ON, photos taken with the
    // camera's OWN physical shutter button are detected over USB and enter
    // the normal capture pipeline — the photographer never has to touch the
    // phone/laptop screen. Default ON (that's the whole point of PTP mode).
    private val _physicalShutterSync = MutableStateFlow(true)
    val physicalShutterSync: StateFlow<Boolean> = _physicalShutterSync.asStateFlow()

    fun setPhysicalShutterSync(enabled: Boolean) {
        _physicalShutterSync.value = enabled
        Log.i(TAG, "Physical shutter sync: $enabled")
    }

    init {
        // Keep the hoisted flow in sync with whichever engine is active.
        viewModelScope.launch {
            cameraUVCManager.currentCameraIdFlow.collect { id ->
                if (_activeCameraEngine.value == "uvc") _currentCameraId.value = id
            }
        }
        viewModelScope.launch {
            camera2Manager.currentCameraIdFlow.collect { id ->
                if (_activeCameraEngine.value == "camera2") _currentCameraId.value = id
            }
        }
        viewModelScope.launch {
            ptpCameraManager.currentCameraIdFlow.collect { id ->
                if (_activeCameraEngine.value == "ptp") _currentCameraId.value = id
            }
        }

        // Physical shutter sync — watch while PTP is connected AND enabled.
        viewModelScope.launch {
            combine(ptpCameraManager.isConnected, _physicalShutterSync) { conn, enabled ->
                conn && enabled
            }.collect { active ->
                if (active) ptpCameraManager.startExternalShutterWatch()
                else ptpCameraManager.stopExternalShutterWatch()
            }
        }
        // Photos taken with the camera's own physical shutter → normal pipeline.
        viewModelScope.launch {
            ptpCameraManager.externalShutterPhotos.collect { dataUrl ->
                onExternalShutterPhoto(dataUrl)
            }
        }
    }

    // ─── Frame Overlay ──────────────────────────────────────────

    private val _frameBitmap = MutableStateFlow<Bitmap?>(null)
    val frameBitmap: StateFlow<Bitmap?> = _frameBitmap.asStateFlow()

    // ─── State Request Retry ────────────────────────────────────

    private var stateRequestJob: Job? = null
    private var frameRequestJob: Job? = null

    private fun startStateRequestLoop() {
        stateRequestJob?.cancel()
        stateRequestJob = viewModelScope.launch {
            while (isActive && _project.value == null) {
                socketManager.requestState()
                delay(STATE_REQUEST_INTERVAL_MS)
            }
        }
    }

    private fun stopStateRequestLoop() {
        stateRequestJob?.cancel()
        stateRequestJob = null
    }

    private fun startFrameRequestLoop() {
        frameRequestJob?.cancel()
        frameRequestJob = viewModelScope.launch {
            delay(1000L)
            while (isActive && _frameBitmap.value == null && _project.value != null) {
                requestFrameIfNeeded()
                delay(5000L)
            }
        }
    }

    private fun stopFrameRequestLoop() {
        frameRequestJob?.cancel()
        frameRequestJob = null
    }

    private fun requestFrameIfNeeded() {
        val proj = _project.value
        if (proj != null && _frameBitmap.value == null) {
            val needsFrame = proj.config.frame == "__FRAME_SAVED__" ||
                    proj.config.frame.isNullOrEmpty() ||
                    (_frameBitmap.value == null && proj.config.frame != null && proj.config.frame != "__FRAME_SAVED__")
            if (needsFrame) {
                Log.i(TAG, "requestFrameIfNeeded: Requesting frame for project ${proj.id}")
                socketManager.requestFrame(proj.id)
            }
        }
    }

    // ─── Camera Lifecycle ───────────────────────────────────────

    private var cameraConnectedCollector: Job? = null
    private var cameraTypeCollector: Job? = null
    private var camera2ConnectedCollector: Job? = null
    private var camera2TypeCollector: Job? = null
    private var ptpConnectedCollector: Job? = null
    private var ptpTypeCollector: Job? = null

    /**
     * Initialize ALL THREE camera systems:
     * - UVCCamera for USB capture cards (MacroSilicon, etc.) — DO NOT MODIFY
     * - Camera2 for built-in phone cameras (front/back)
     * - PTPCameraManager for direct DSLR control over USB
     */
    fun initCamera() {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "initCamera: Triple engine — UVC + Camera2 + PTP")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        // Initialize UVC camera system (USB capture cards) — UNCHANGED from v18
        cameraUVCManager.initCamera()

        // Enumerate Camera2 built-in cameras (front/back)
        camera2Manager.enumerateCameras()

        // Initialize the PTP engine (direct DSLR control over USB)
        ptpCameraManager.initCamera()

        // Set up UVC collectors
        if (cameraConnectedCollector?.isActive != true) {
            cameraConnectedCollector?.cancel()
            cameraConnectedCollector = viewModelScope.launch {
                cameraUVCManager.isConnected.collect { connected ->
                    if (_activeCameraEngine.value == "uvc") {
                        _cameraConnected.value = connected
                    }
                    updateCameraSource()
                    refreshAvailableCameras()
                }
            }
        }

        if (cameraTypeCollector?.isActive != true) {
            cameraTypeCollector?.cancel()
            cameraTypeCollector = viewModelScope.launch {
                cameraUVCManager.cameraType.collect {
                    updateCameraSource()
                    refreshAvailableCameras()
                }
            }
        }

        // Set up Camera2 collectors
        if (camera2ConnectedCollector?.isActive != true) {
            camera2ConnectedCollector?.cancel()
            camera2ConnectedCollector = viewModelScope.launch {
                camera2Manager.isConnected.collect { connected ->
                    if (_activeCameraEngine.value == "camera2") {
                        _cameraConnected.value = connected
                    }
                    updateCameraSource()
                    refreshAvailableCameras()
                }
            }
        }

        if (camera2TypeCollector?.isActive != true) {
            camera2TypeCollector?.cancel()
            camera2TypeCollector = viewModelScope.launch {
                camera2Manager.cameraType.collect {
                    updateCameraSource()
                    refreshAvailableCameras()
                }
            }
        }

        // Set up PTP collectors
        if (ptpConnectedCollector?.isActive != true) {
            ptpConnectedCollector?.cancel()
            ptpConnectedCollector = viewModelScope.launch {
                ptpCameraManager.isConnected.collect { connected ->
                    if (_activeCameraEngine.value == "ptp") {
                        _cameraConnected.value = connected
                    }
                    updateCameraSource()
                    refreshAvailableCameras()
                }
            }
        }

        if (ptpTypeCollector?.isActive != true) {
            ptpTypeCollector?.cancel()
            ptpTypeCollector = viewModelScope.launch {
                ptpCameraManager.cameraType.collect {
                    updateCameraSource()
                    refreshAvailableCameras()
                }
            }
        }

        // Build initial merged camera list
        refreshAvailableCameras()

        Log.i(TAG, "Camera initialized — UVC + Camera2 + PTP, cameraSource=${_cameraSource.value}")
    }

    private fun updateCameraSource() {
        val ptpConnected = ptpCameraManager.isConnected.value
        val uvcConnected = cameraUVCManager.isConnected.value
        val cameraType = cameraUVCManager.cameraType.value
        val isUSB = cameraUVCManager.isUSBCamera.value
        val c2Connected = camera2Manager.isConnected.value

        // If a PTP camera (DSLR) is connected, it takes priority
        if (ptpConnected) {
            _activeCameraEngine.value = "ptp"
            _cameraConnected.value = true
            _cameraSource.value = "ptp"
            return
        }

        // If UVC is connected, it takes priority
        if (uvcConnected) {
            _activeCameraEngine.value = "uvc"
            _cameraConnected.value = true
            _cameraSource.value = if (isUSB || cameraType == "external") "uvc" else "builtin"
            return
        }

        // If Camera2 is connected
        if (c2Connected) {
            _activeCameraEngine.value = "camera2"
            _cameraConnected.value = true
            _cameraSource.value = "builtin"
            return
        }

        // Nothing connected yet — keep whatever engine was last chosen
        when (_activeCameraEngine.value) {
            "camera2" -> {
                _cameraConnected.value = c2Connected
                _cameraSource.value = if (c2Connected) "builtin" else "none"
            }
            "ptp" -> {
                _cameraConnected.value = ptpConnected
                _cameraSource.value = if (ptpConnected) "ptp" else "none"
            }
            else -> {
                _cameraConnected.value = uvcConnected
                _cameraSource.value = if (uvcConnected) "uvc" else "none"
            }
        }
    }

    /**
     * Refresh the merged available cameras list from all engines (PTP + UVC + Camera2).
     */
    private fun refreshAvailableCameras() {
        val ptpList = ptpCameraManager.availableCameras.value
        val uvcList = cameraUVCManager.availableCameras.value
        val c2List = camera2Manager.availableCameras.value
        _allAvailableCameras.value = ptpList + uvcList + c2List
    }

    fun switchCamera() {
        val cameras = _allAvailableCameras.value
        if (cameras.isEmpty()) return

        val currentId = currentCameraId.value
        val currentIndex = cameras.indexOfFirst { it.first == currentId }
        val nextIndex = (currentIndex + 1) % cameras.size
        val nextCamera = cameras[nextIndex]

        switchToCameraById(nextCamera.first)
    }

    fun getAvailableCameras(): List<Pair<String, String>> {
        cameraUVCManager.refreshCameraList()
        ptpCameraManager.refreshCameraList()
        camera2Manager.enumerateCameras()
        refreshAvailableCameras()
        return _allAvailableCameras.value
    }

    fun switchToCameraById(cameraId: String) {
        // PTP camera (ID starts with "ptp:")
        if (cameraId.startsWith("ptp:")) {
            Log.i(TAG, "switchToCameraById: Switching to PTP camera $cameraId")
            _activeCameraEngine.value = "ptp"
            _currentCameraId.value = cameraId

            // Stop the other engines
            try { camera2Manager.closeCamera() } catch (_: Exception) {}
            try { cameraUVCManager.hidePreview() } catch (_: Exception) {}

            ptpCameraManager.switchCamera(cameraId)
            _cameraConnected.value = true
            _cameraSource.value = "ptp"
            return
        }

        // Check if this is a Camera2 camera (ID is numeric 0, 1, etc.)
        val c2Cameras = camera2Manager.availableCameras.value
        val isCamera2 = c2Cameras.any { it.first == cameraId }

        if (isCamera2) {
            Log.i(TAG, "switchToCameraById: Switching to Camera2 camera $cameraId")
            _activeCameraEngine.value = "camera2"
            _currentCameraId.value = cameraId

            // Close UVC preview (but don't destroy the monitor)
            try { cameraUVCManager.capturePhoto {} } catch (_: Exception) {}

            camera2Manager.openCamera(cameraId)
            _cameraConnected.value = true
            _cameraSource.value = "builtin"
        } else {
            Log.i(TAG, "switchToCameraById: Switching to UVC camera $cameraId")
            _activeCameraEngine.value = "uvc"
            _currentCameraId.value = cameraId

            // Close Camera2
            try { camera2Manager.closeCamera() } catch (_: Exception) {}

            cameraUVCManager.switchCamera(cameraId)
        }
    }

    /**
     * Force rescan for USB cameras. Called when user taps "Pindai Ulang USB".
     * v13: WebView's devicechange + forceRescan() handles this.
     */
    fun forceRescanUsbCamera() {
        Log.i(TAG, "forceRescanUsbCamera: User requested USB camera rescan")
        cameraUVCManager.forceRescan()
        ptpCameraManager.forceRescan()
    }

    fun setUvcDeviceAttached(attached: Boolean) {
        _uvcDeviceAttached.value = attached
        if (attached) {
            viewModelScope.launch {
                delay(1000)
                cameraUVCManager.forceRescan()
            }
        }
    }

    // ─── Photo Capture ──────────────────────────────────────────

    /**
     * Trigger a photo capture. Supports timer mode.
     * v10: Capture via DualCameraManager (UVCCamera or CameraX).
     * Native capture — no JavaScript involved.
     */
    fun triggerCapture() {
        val phase = _capturePhase.value
        if (phase == CapturePhase.SENDING || _isSending.value) return
        if (phase != CapturePhase.READY_1 && phase != CapturePhase.READY_2) return

        if (isTimerMode() && _timerCountdown.value <= 0) {
            startTimer { doCapture() }
            return
        }

        doCapture()
    }

    fun cancelTimerCapture() {
        cancelTimer()
    }

    /**
     * v10: Capture via DualCameraManager (UVCCamera or CameraX).
     * USB: IFrameCallback → NV21 → JPEG → base64
     * Built-in: ImageCapture → JPEG → base64
     *
     * The result is a base64 data URL string, ready to send via socket.
     * Photos are NOT saved locally — only sent to admin via socket.
     */
    private fun doCapture() {
        Log.i(TAG, "doCapture: phase=${_capturePhase.value}, target=${_currentTarget.value?.nama}")

        val config = _project.value?.config

        // Capture from the currently active camera source
        val captureCallback: (String?) -> Unit = { base64DataUrl ->
            if (base64DataUrl == null) {
                Log.e(TAG, "Capture returned null from camera engine")
            } else {
                Log.i(TAG, "doCapture: Raw photo captured (${base64DataUrl.length} chars)")

                // ═══════════════════════════════════════════════════════
                // POST-PROCESS: Apply frame overlay + filter + crop
                // This processes the raw camera capture and applies:
                // 1. Center-crop to project aspect ratio
                // 2. Filter preset (studio, cinematic, etc.)
                // 3. Frame overlay (if selected by admin)
                // ═══════════════════════════════════════════════════════
                val processedDataUrl = if (config != null) {
                    processPhotoWithFrame(base64DataUrl, config)
                } else {
                    Log.w(TAG, "doCapture: No config — sending raw photo without frame")
                    base64DataUrl
                }

                handleCapturedPhoto(processedDataUrl)
            }
        }

        when (_activeCameraEngine.value) {
            "camera2" -> camera2Manager.capturePhoto(captureCallback)
            "ptp" -> ptpCameraManager.capturePhoto(captureCallback)
            else -> cameraUVCManager.capturePhoto(captureCallback)
        }
    }

    /**
     * A photo taken with the camera's OWN physical shutter button, detected by
     * the PTP external shutter watch. Routes it through the exact same
     * processing + delivery pipeline as an app-triggered capture. Gated by
     * capture phase so a stray shot between students (STANDBY/SENDING) is
     * ignored instead of mis-attributed.
     */
    private fun onExternalShutterPhoto(dataUrl: String) {
        if (_activeCameraEngine.value != "ptp") return
        if (!_physicalShutterSync.value) return

        val phase = _capturePhase.value
        if (phase != CapturePhase.READY_1 && phase != CapturePhase.READY_2) {
            Log.i(TAG, "onExternalShutterPhoto: ignored — phase=$phase")
            return
        }
        if (_isSending.value) {
            Log.i(TAG, "onExternalShutterPhoto: ignored — previous photo still sending")
            return
        }

        // The shot already happened — an armed timer would trigger a SECOND
        // capture; cancel it and deliver this photo instead.
        cancelTimer()

        Log.i(TAG, "onExternalShutterPhoto: accepted (${dataUrl.length} chars), phase=$phase")
        val config = _project.value?.config
        val processed = if (config != null) {
            try {
                processPhotoWithFrame(dataUrl, config)
            } catch (e: Exception) {
                Log.e(TAG, "onExternalShutterPhoto: process failed — ${e.message}")
                dataUrl
            }
        } else {
            Log.w(TAG, "onExternalShutterPhoto: no config — raw photo without frame")
            dataUrl
        }
        handleCapturedPhoto(processed)
    }

    /**
     * Process a raw captured photo:
     * 1. Decode base64 → Bitmap
     * 2. Center-crop to project aspect ratio
     * 3. Apply filter preset (studio, cinematic, etc.)
     * 4. Overlay frame bitmap (if any)
     * 5. Encode back to base64 data URL
     */
    private fun processPhotoWithFrame(rawDataUrl: String, config: ProjectConfig): String {
        try {
            // Decode the raw photo
            val pureBase64 = if (rawDataUrl.contains(",")) rawDataUrl.substringAfter(",") else rawDataUrl
            val photoBytes = Base64.decode(pureBase64, Base64.DEFAULT)
            val sourceBitmap = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)

            if (sourceBitmap == null) {
                Log.e(TAG, "processPhotoWithFrame: Failed to decode raw photo bitmap")
                return rawDataUrl
            }

            Log.i(TAG, "processPhotoWithFrame: source=${sourceBitmap.width}x${sourceBitmap.height}, frame=${_frameBitmap.value != null}, preset=${config.preset}")

            // Process: crop + filter + frame overlay
            val processedBitmap = CameraCapture.processFrame(
                sourceBitmap = sourceBitmap,
                config = config,
                frameBitmap = _frameBitmap.value
            )

            // Convert back to base64 data URL
            val resultDataUrl = CameraCapture.bitmapToBase64(processedBitmap, 95)

            Log.i(TAG, "processPhotoWithFrame: output=${processedBitmap.width}x${processedBitmap.height} (${resultDataUrl.length} chars)")

            // Recycle bitmaps
            if (sourceBitmap != processedBitmap) {
                sourceBitmap.recycle()
            }
            processedBitmap.recycle()

            return resultDataUrl

        } catch (e: Exception) {
            Log.e(TAG, "processPhotoWithFrame: Failed — sending raw photo: ${e.message}")
            return rawDataUrl
        }
    }

    /**
     * Handle a captured photo (base64 data URL from camera engine).
     * Photos are ONLY sent via socket to the admin's output folder.
     * They are NOT saved locally to the phone — the admin determines
     * the output folder during project initialization, and photos go there.
     */
    private fun handleCapturedPhoto(base64DataUrl: String) {
        val mode = _project.value?.config?.mode
        val target = _currentTarget.value
        if (mode == null || target == null) {
            Log.e(TAG, "handleCapturedPhoto: mode or target is null — dropping photo!")
            return
        }
        val photosPerSession = CameraModes.photosPerSession(mode)

        val currentPhotos = _capturedPhotos.value.toMutableList()
        currentPhotos.add(base64DataUrl)
        _capturedPhotos.value = currentPhotos

        // NOTE: Photos are NOT saved to the phone's local storage.
        // They are sent via socket (PHOTOS_SAVED) to the admin app,
        // which saves them to the admin-designated output folder.
        // Local saving was removed because it caused photos to appear
        // in the phone's Gallery, which is not the intended behavior.

        Log.i(TAG, "handleCapturedPhoto: photo ${currentPhotos.size}/${photosPerSession} captured for ${target.nama} — will be sent via socket")

        if (photosPerSession == 1 || currentPhotos.size >= photosPerSession) {
            // All photos captured — finalize (send via socket only, NO local save)
            viewModelScope.launch {
                finalizeCapture(target, currentPhotos)
            }
        } else {
            // More photos needed (standard mode: Toga done, Ijazah next)
            _capturePhase.value = CapturePhase.READY_2
            socketManager.sendOpProgress("Pose 1 OK — Siap Foto 2")
        }
    }

    // ─── Connection ─────────────────────────────────────────────

    private var socketListenersInitialized = false

    fun setRole(role: String) {
        _myRole.value = role
    }

    fun connect(serverUrl: String, role: String, channel: Int, password: String? = null) {
        _serverUrl.value = serverUrl
        _myRole.value = role
        _myChannel.value = channel
        _authError.value = null
        _connectionError.value = null

        if (!socketListenersInitialized) {
            setupSocketListeners()
            socketListenersInitialized = true
        }
        socketManager.connect(serverUrl, role, channel, password)
        updateOpQueue()
    }

    // ─── MC Actions ──────────────────────────────────────────────

    /**
     * MC calls a student to the stage on the given channel.
     * Updates local state immediately and broadcasts MC_CALL to operators.
     */
    fun callStudent(student: Student, channel: Int) {
        // Update local project DB so MC sees status change instantly
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == student.id) s.copy(status = "active_$channel")
                else if (isActiveStatus(s.status) && getActiveChannel(s.status) == channel) {
                    // demote previously-active student on same channel back to pending
                    s.copy(status = "pending")
                } else s
            }
            _project.value = proj.copy(database = updatedDb)
        }
        socketManager.sendMcCall(student, channel)
        Log.i(TAG, "MC_CALL sent: ${student.nama} ch=$channel")
    }

    /**
     * MC sends a student to operator(s) — photoshoot modes only.
     * Mirrors AdminViewModel.sendToOperator but emits over the socket to the
     * remote admin/server (which then updates its own DB + broadcasts SYNC_DB).
     *
     * - single-photoshoot: channels = [myChannel] → only ch1 sees the student.
     * - dual-photoshoot: channels = [1, 2] → both channels see the student
     *   simultaneously (cooperative mode — either operator can photograph).
     *
     * Sets the student's status to "sent" locally (instant UI feedback) and
     * emits one MC_CALL per requested channel. The server's handleMcCall is
     * mode-aware (photoshoot → "sent") so the SYNC_DB that bounces back keeps
     * the "sent" status (it does NOT get reverted to "active_$channel").
     */
    fun sendToOperator(student: Student, channels: List<Int>) {
        // Update local project DB so MC sees status change instantly
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == student.id) s.copy(status = "sent") else s
            }
            _project.value = proj.copy(database = updatedDb)
        }
        // Emit MC_CALL to each requested channel (student carries status="sent"
        // so the operator + server both see the correct state immediately).
        val sentStudent = student.copy(status = "sent")
        for (ch in channels) {
            socketManager.sendMcCall(sentStudent, ch)
        }
        Log.i(TAG, "MC_CALL sent (photoshoot): ${student.nama} channels=$channels")
    }

    /**
     * MC sends ALL selected students to operator(s) in one batch — photoshoot
     * modes only. Mirrors the Electron handleSendBatchToOperator: marks every
     * selected student as "sent" in a single DB pass, then emits one MC_CALL
     * per (student × channel). The server's handleMcCall + the single SYNC_DB
     * broadcast at the end consolidate the wire traffic.
     *
     * Order matters in dual mode (both channels must see the same student
     * before the next), so the per-student loop emits to all channels before
     * advancing.
     *
     * @param students the selected pending students (caller is responsible
     *   for filtering out non-pending — matches Electron's selectedStudents
     *   memo which keeps only `s.status === 'pending'`).
     * @param channels [1] for single-photoshoot, [1, 2] for dual-photoshoot.
     */
    fun sendBatchToOperator(students: List<Student>, channels: List<Int>) {
        if (students.isEmpty()) return
        // Single pass: mark ALL selected students as 'sent' locally so the MC's
        // queue reflects the change instantly (operator queue also recomputes
        // via SYNC_DB bounce-back from the server).
        _project.value?.let { proj ->
            val selectedIds = students.map { it.id }.toSet()
            val updatedDb = proj.database.map { s ->
                if (selectedIds.contains(s.id)) s.copy(status = "sent") else s
            }
            _project.value = proj.copy(database = updatedDb)
        }
        // Emit MC_CALL for each (student × channel). The student object
        // carries status="sent" so the server's handleMcCall (mode-aware) and
        // operators both see the correct state immediately.
        for (student in students) {
            val sentStudent = student.copy(status = "sent")
            for (ch in channels) {
                socketManager.sendMcCall(sentStudent, ch)
            }
        }
        Log.i(TAG, "MC_CALL batch sent (photoshoot): ${students.size} students, channels=$channels")
    }

    /**
     * MC/Admin resets a student back to pending.
     */
    fun resetStudent(studentId: String, channel: Int) {
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == studentId) s.copy(status = "pending") else s
            }
            _project.value = proj.copy(database = updatedDb)
        }
        socketManager.sendStudentReset(studentId, channel)
    }

    /**
     * MC/Admin marks a student as fully done.
     */
    fun markStudentDone(studentId: String, channel: Int) {
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == studentId) s.copy(status = "done") else s
            }
            _project.value = proj.copy(database = updatedDb)
        }
        socketManager.sendStudentDoneFromMc(studentId, channel)
    }

    fun disconnect() {
        stopStateRequestLoop()
        stopFrameRequestLoop()
        socketManager.disconnect()
        _project.value = null
        _currentTarget.value = null
        _capturePhase.value = CapturePhase.STANDBY
        _capturedPhotos.value = emptyList()
        _mcCallBuffer.value = emptyList()
        _frameBitmap.value = null
        updateOpQueue()
    }

    fun submitPassword(password: String) {
        _authError.value = null
        socketManager.resendWithPassword(password)
    }

    // ─── Socket Event Listeners ─────────────────────────────────

    private fun setupSocketListeners() {
        socketManager.on("state_changed") { state ->
            val cs = state as? ConnectionState ?: return@on
            _connectionState.value = cs
        }

        socketManager.on("password_required") {
            _passwordRequired.value = true
        }

        socketManager.on("auth_success") {
            _passwordRequired.value = false
            _authError.value = null
            startStateRequestLoop()
            requestFrameIfNeeded()
            startFrameRequestLoop()
            // BUG #4: re-send any photo queue items left from a previous
            // session (app was killed before the background send completed).
            flushPhotoQueue()
        }

        socketManager.on("auth_failed") { reason ->
            val reasonStr = reason as? String ?: "unknown"
            _authError.value = reasonStr
            if (reasonStr == "session_password_required") {
                _passwordRequired.value = true
            }
        }

        socketManager.on("connection_error") { error ->
            val errorStr = error?.toString() ?: "Connection failed"
            Log.e(TAG, "Connection error: $errorStr")
            _connectionError.value = errorStr
        }

        socketManager.on("latency") { latency ->
            _latencyMs.value = latency as? Long ?: -1L
        }

        socketManager.on("server_shutdown") {
            stopStateRequestLoop()
            disconnect()
        }

        // ─── MC_CALL ──────────────────────────────────────────────

        socketManager.on(SocketEvents.MC_CALL) { data ->
            val mcCall = data as? McCallData
            if (mcCall == null) {
                Log.e(TAG, "MC_CALL: data is not McCallData — type=${data?.javaClass?.simpleName}")
                return@on
            }

            Log.i(TAG, "MC_CALL received: student=${mcCall.student.nama}, ch=${mcCall.channel}, myCh=${_myChannel.value}")

            val mode = _project.value?.config?.mode
            val isPhotoshoot = mode != null && CameraModes.isPhotoshootMode(mode)

            if (!isPhotoshoot && mcCall.channel != _myChannel.value) {
                Log.d(TAG, "MC_CALL for channel ${mcCall.channel}, ignoring (my channel: ${_myChannel.value})")
                return@on
            }

            if (isPhotoshoot) {
                val buffer = _mcCallBuffer.value.toMutableList()
                val without = buffer.filter { it.id != mcCall.student.id }
                _mcCallBuffer.value = without + mcCall.student
            } else {
                _currentTarget.value = mcCall.student
                _capturePhase.value = CapturePhase.READY_1
                _capturedPhotos.value = emptyList()
            }

            if (_project.value == null) {
                val buffer = _mcCallBuffer.value.toMutableList()
                val without = buffer.filter { it.id != mcCall.student.id }
                _mcCallBuffer.value = without + mcCall.student
            }

            updateOpQueue()
        }

        // ─── SYNC_DB ──────────────────────────────────────────────

        socketManager.on(SocketEvents.SYNC_DB) { data ->
            val syncData = data as? SyncDbData
            if (syncData == null) {
                Log.e(TAG, "SYNC_DB: data is not SyncDbData")
                return@on
            }

            Log.i(TAG, "SYNC_DB received: project=${syncData.project.name}, dbSize=${syncData.project.database.size}")
            handleSyncDb(syncData.project)

            if (_project.value != null) {
                stopStateRequestLoop()
            }
        }

        // ─── STUDENT_RESET ────────────────────────────────────────

        socketManager.on(SocketEvents.STUDENT_RESET) { data ->
            val resetData = data as? StudentResetData ?: return@on

            Log.i(TAG, "STUDENT_RESET received: studentId=${resetData.studentId}, channel=${resetData.channel}")

            val mode = _project.value?.config?.mode
            val isPhotoshoot = mode != null && CameraModes.isPhotoshootMode(mode)

            if (!isPhotoshoot && resetData.channel != _myChannel.value) return@on

            if (_currentTarget.value?.id == resetData.studentId) {
                _currentTarget.value = null
                _capturePhase.value = CapturePhase.STANDBY
                _capturedPhotos.value = emptyList()
            }

            val buffer = _mcCallBuffer.value.toMutableList()
            if (buffer.removeAll { it.id == resetData.studentId }) {
                _mcCallBuffer.value = buffer
            }

            _project.value?.let { proj ->
                val updatedDb = proj.database.map { student ->
                    if (student.id == resetData.studentId) {
                        student.copy(status = "pending")
                    } else student
                }
                _project.value = proj.copy(database = updatedDb)
            }

            updateOpQueue()
        }

        // ─── FRAME_DATA ──────────────────────────────────────────

        socketManager.on(SocketEvents.FRAME_DATA) { data ->
            val frameData = data as? FrameDataPayload ?: return@on
            Log.i(TAG, "FRAME_DATA received: length=${frameData.frame.length}")
            _project.value?.let { proj ->
                _project.value = proj.copy(
                    config = proj.config.copy(frame = frameData.frame)
                )
            }
            decodeFrameBitmap(frameData.frame)
            stopFrameRequestLoop()
            updateOpQueue()
        }

        // ─── PHOTOS_SAVED (from other operators in dual mode) ─────

        socketManager.on(SocketEvents.PHOTOS_SAVED) { data ->
            val photosSaved = data as? PhotosSavedData ?: return@on
            val mode = _project.value?.config?.mode

            if (mode == null || !CameraModes.isPhotoshootMode(mode) || !CameraModes.isDualMode(mode)) return@on

            _currentTarget.value?.let { target ->
                if (target.id == photosSaved.student.id) {
                    _currentTarget.value = null
                    _capturePhase.value = CapturePhase.STANDBY
                    _capturedPhotos.value = emptyList()
                }
            }
        }

        // ─── STUDENT_DONE (from other operators in dual mode) ─────

        socketManager.on(SocketEvents.STUDENT_DONE) { data ->
            val doneData = data as? StudentDoneData ?: return@on
            val mode = _project.value?.config?.mode

            if (mode == null || !CameraModes.isDualMode(mode) || doneData.channel == _myChannel.value) return@on

            _project.value?.let { proj ->
                val updatedDb = proj.database.map { student ->
                    if (student.id == doneData.studentId) {
                        student.copy(status = "done")
                    } else student
                }
                _project.value = proj.copy(database = updatedDb)
            }

            updateOpQueue()
        }
    }

    // ─── Sync DB Handler ────────────────────────────────────────

    private fun handleSyncDb(incomingProject: Project) {
        val currentProject = _project.value

        if (currentProject == null) {
            _project.value = incomingProject
            Log.i(TAG, "SYNC_DB (first): project=${incomingProject.name}, mode=${incomingProject.config.mode}, dbSize=${incomingProject.database.size}")

            val dbIdSet = incomingProject.database.map { it.id }.toSet()
            val buffer = _mcCallBuffer.value.toMutableList()
            val newBuffer = buffer.filter { !dbIdSet.contains(it.id) }
            if (newBuffer.size != buffer.size) {
                _mcCallBuffer.value = newBuffer
            }

            decodeFrameBitmap(incomingProject.config.frame)
            if (incomingProject.config.frame == "__FRAME_SAVED__" || incomingProject.config.frame.isNullOrEmpty()) {
                socketManager.requestFrame(incomingProject.id)
                startFrameRequestLoop()
            }

            if (!CameraModes.isPhotoshootMode(incomingProject.config.mode)) {
                val activeStudent = incomingProject.database.find {
                    it.assignedChannel == _myChannel.value && isActiveStatus(it.status)
                }
                if (activeStudent != null && _currentTarget.value?.id != activeStudent.id) {
                    _currentTarget.value = activeStudent
                    if (_capturePhase.value == CapturePhase.STANDBY) {
                        _capturePhase.value = CapturePhase.READY_1
                        _capturedPhotos.value = emptyList()
                    }
                }
            }
            updateOpQueue()
            return
        }

        val mergedDb = mergeDatabases(currentProject.database, incomingProject.database)
        val mergedVersions = mergeCaptureVersions(currentProject.captureVersions, incomingProject.captureVersions)
        val mergedHistory = mergePhotoHistory(currentProject.photoHistory, incomingProject.photoHistory)

        val preservedFrame = if (incomingProject.config.frame == "__FRAME_SAVED__") {
            currentProject.config.frame
        } else {
            incomingProject.config.frame
        }

        val mergedProject = incomingProject.copy(
            database = mergedDb,
            captureVersions = mergedVersions,
            photoHistory = mergedHistory,
            config = incomingProject.config.copy(frame = preservedFrame)
        )

        _project.value = mergedProject

        val buffer = _mcCallBuffer.value.toMutableList()
        val doneIds = mergedDb.filter { it.status == "done" }.map { it.id }.toSet()
        if (buffer.removeAll { doneIds.contains(it.id) }) {
            _mcCallBuffer.value = buffer
        }

        _currentTarget.value?.let { target ->
            val updatedStudent = mergedDb.find { it.id == target.id }
            if (updatedStudent?.status == "done") {
                _currentTarget.value = null
                _capturePhase.value = CapturePhase.STANDBY
                _capturedPhotos.value = emptyList()
            }
        }

        if (!CameraModes.isPhotoshootMode(mergedProject.config.mode)) {
            val activeStudent = mergedDb.find {
                it.assignedChannel == _myChannel.value && isActiveStatus(it.status)
            }
            if (activeStudent != null && _currentTarget.value?.id != activeStudent.id) {
                _currentTarget.value = activeStudent
                if (_capturePhase.value == CapturePhase.STANDBY) {
                    _capturePhase.value = CapturePhase.READY_1
                    _capturedPhotos.value = emptyList()
                }
            }
        }

        updateOpQueue()

        if (preservedFrame == "__FRAME_SAVED__" || (preservedFrame.isNullOrEmpty() && _frameBitmap.value == null)) {
            socketManager.requestFrame(incomingProject.id)
            startFrameRequestLoop()
        } else if (preservedFrame != currentProject.config.frame) {
            decodeFrameBitmap(preservedFrame)
            stopFrameRequestLoop()
        }
    }

    private fun mergeDatabases(local: List<Student>, incoming: List<Student>): List<Student> {
        val statusPriority = mapOf("pending" to 0, "sent" to 1)
        val localMap = local.associateBy { it.id }

        return incoming.map { inc ->
            val loc = localMap[inc.id]
            if (loc == null) return@map inc

            val locPriority = statusPriority[loc.status] ?: (if (isActiveStatus(loc.status)) 2 else 3)
            val incPriority = statusPriority[inc.status] ?: (if (isActiveStatus(inc.status)) 2 else 3)

            if (locPriority >= incPriority) loc else inc
        }
    }

    private fun mergeCaptureVersions(
        local: Map<String, Int>, incoming: Map<String, Int>
    ): Map<String, Int> {
        val allKeys = local.keys + incoming.keys
        return allKeys.associateWith { key ->
            maxOf(local[key] ?: 0, incoming[key] ?: 0)
        }
    }

    private fun mergePhotoHistory(
        local: List<PhotoHistoryItem>, incoming: List<PhotoHistoryItem>
    ): List<PhotoHistoryItem> {
        val localMap = local.associateBy { "${it.student.id}_${it.channel}" }

        return incoming.map { inc ->
            val key = "${inc.student.id}_${inc.channel}"
            val loc = localMap[key]

            if (loc != null && inc.photos.isEmpty() && loc.photos.isNotEmpty()) {
                loc
            } else {
                inc
            }
        }
    }

    // ─── Finalize Capture ───────────────────────────────────────

    /**
     * Complete the capture pipeline.
     *
     * v10: Photos NOT saved on operator device (matching Electron browser mode).
     * Only sent via socket to admin who saves them.
     *
     * Priority order:
     * 1. IMMEDIATE: Send STUDENT_DONE (lightweight — unblocks MC instantly)
     * 2. IMMEDIATE: Reset capture state (operator can proceed to next target)
     * 3. BACKGROUND: Send PHOTOS_SAVED, send SYNC_DB
     */
    private suspend fun finalizeCapture(student: Student, photos: List<String>) {
        val mode = _project.value?.config?.mode
        val proj = _project.value
        if (mode == null || proj == null) {
            Log.e(TAG, "finalizeCapture: project/mode is null — cannot finalize!")
            return
        }

        Log.i(TAG, "finalizeCapture: student=${student.nama}, photos=${photos.size}, mode=$mode")
        _capturePhase.value = CapturePhase.SENDING
        _isSending.value = true

        try {
            val versionKey = "${student.id}_${_myChannel.value}"
            val currentVersion = proj.captureVersions[versionKey] ?: 0
            val newVersion = currentVersion + 1

            val filenames: List<String> = if (CameraModes.isPhotoshootMode(mode)) {
                listOf(FilenameUtils.buildPhotoshootFilename(student.nim, student.nama, _myChannel.value, newVersion))
            } else {
                photos.mapIndexed { idx, _ ->
                    val suffix = idx + 1
                    val type = if (suffix == 1) "Toga" else "Ijazah"
                    FilenameUtils.buildStandardFilename(student.nim, student.nama, suffix, type, newVersion)
                }
            }
            val primaryFilename = filenames.firstOrNull() ?: ""

            // BUG #4 fix: persist captured photo bytes to a local file-based
            // queue BEFORE marking done. If this persist fails (disk full,
            // permission revoked) we surface the error to the operator and
            // DO NOT mark done — the operator can re-capture. The previous
            // implementation marked done in _project.value and then launched
            // a fire-and-forget background send: if the app died (OOM, swipe
            // away) before the send completed, the photo bytes were lost
            // forever while the operator's UI happily advanced to the next
            // target.
            val queueFile = withContext(Dispatchers.IO) {
                try {
                    val json = JSONObject().apply {
                        put("studentId", student.id)
                        put("studentNim", student.nim)
                        put("studentNama", student.nama)
                        put("channel", _myChannel.value)
                        put("version", newVersion)
                        put("filename", primaryFilename)
                        put("timestamp", System.currentTimeMillis())
                        put("photos", JSONArray(photos))
                    }
                    val f = File(photoQueueDir, "${student.id}_${_myChannel.value}_${newVersion}_${System.currentTimeMillis()}.json")
                    f.writeText(json.toString())
                    Log.i(TAG, "BUG#4: persisted photo set to ${f.name} (${photos.size} photos)")
                    f
                } catch (e: Exception) {
                    Log.e(TAG, "BUG#4: FAILED to persist photo set: ${e.message}", e)
                    null
                }
            }

            if (queueFile == null) {
                // Persist failed — surface error, DO NOT mark done, reset phase
                // so the operator can retry the shutter.
                _capturePhase.value = if (CameraModes.photosPerSession(mode) == 2) CapturePhase.READY_2 else CapturePhase.READY_1
                _isSending.value = false
                _captureError.value = "Gagal menyimpan foto ke antrian lokal — coba lagi"
                return
            }

            // ═══ PHASE 1: IMMEDIATE — unblock MC and reset operator state ═══
            socketManager.sendStudentDone(student.id)

            _currentTarget.value = null
            _capturedPhotos.value = emptyList()
            _capturePhase.value = CapturePhase.STANDBY
            _isSending.value = false

            val buffer = _mcCallBuffer.value.toMutableList()
            if (buffer.removeAll { it.id == student.id }) {
                _mcCallBuffer.value = buffer
            }

            val updatedDb = proj.database.map { s ->
                if (s.id == student.id) s.copy(status = "done") else s
            }
            val updatedVersions = proj.captureVersions.toMutableMap().apply {
                this[versionKey] = newVersion
            }
            val updatedHistory = proj.photoHistory.toMutableList().apply {
                removeAll { it.student.id == student.id && it.channel == _myChannel.value }
                add(PhotoHistoryItem(student = student, photos = photos, channel = _myChannel.value))
            }
            val updatedProject = proj.copy(
                database = updatedDb,
                captureVersions = updatedVersions,
                photoHistory = updatedHistory
            )
            _project.value = updatedProject
            updateOpQueue()

            // Clear any previous error — the persist succeeded, send is now in flight.
            _captureError.value = null

            Log.i(TAG, "finalizeCapture: Operator state reset — ready for next target")

            // ═══ PHASE 2: BACKGROUND — send photos via socket ═══
            // BUG #4 fix: launch on opAppScope (survives ViewModel clear) so the
            // send completes even if the user backgrounds the app. On success
            // we delete the persisted queue file (it served its purpose as a
            // backup). On failure we LEAVE the file — a startup flush (see
            // [flushPhotoQueue]) will retry the send on the next reconnect.
            opAppScope.launch {
                try {
                    socketManager.sendPhotosSaved(
                        student = student,
                        photos = photos,
                        version = newVersion,
                        filename = primaryFilename
                    )

                    socketManager.sendOpProgress("Selesai — Menunggu target...")

                    Log.i(TAG, "finalizeCapture: Photos sent via socket (NOT saved locally — matching Electron browser mode)")

                    socketManager.sendSyncDb(updatedProject)

                    Log.i(TAG, "finalizeCapture: Background tasks complete for ${student.nama}")

                    // BUG #4: send completed (or queued in SocketManager's
                    // in-memory queue) — remove the backup file.
                    withContext(Dispatchers.IO) {
                        try { queueFile.delete() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "finalizeCapture: Background task error: ${e.message}", e)
                    // BUG #4: surface the error — but DON'T revert _project.value
                    // (the operator already advanced). The persisted queue file
                    // remains on disk for the startup flush to retry.
                    _captureError.value = "Foto tersimpan lokal tapi gagal terkirim ke admin — akan diulang otomatis"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize capture: ${e.message}", e)
            _capturePhase.value = CapturePhase.READY_1
            _isSending.value = false
            _captureError.value = "Gagal finalisasi: ${e.message}"
        }
    }

    /**
     * BUG #4 fix: retry-send any photo queue items left on disk from a previous
     * session (app was killed before the background send completed). Called
     * once after the socket successfully connects. Best-effort: if the send
     * fails again the file remains for the next call.
     */
    fun flushPhotoQueue() {
        opAppScope.launch {
            val files = withContext(Dispatchers.IO) {
                try { photoQueueDir.listFiles()?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
            }
            if (files.isEmpty()) return@launch
            Log.i(TAG, "BUG#4: flushing ${files.size} leftover photo queue item(s)")
            for (f in files) {
                try {
                    val json = JSONObject(withContext(Dispatchers.IO) { f.readText() })
                    val student = Student(
                        id = json.optString("studentId"),
                        nim = json.optString("studentNim"),
                        nama = json.optString("studentNama"),
                        status = "done"
                    )
                    val channel = json.optInt("channel", 1)
                    val version = json.optInt("version", 1)
                    val filename = json.optString("filename", "")
                    val photos = mutableListOf<String>()
                    val arr = json.optJSONArray("photos")
                    if (arr != null) for (i in 0 until arr.length()) photos.add(arr.getString(i))

                    _myChannel.value = channel
                    socketManager.sendPhotosSaved(student, photos, version, filename)
                    Log.i(TAG, "BUG#4: re-sent photo set ${f.name} (student=${student.nama})")
                    withContext(Dispatchers.IO) { try { f.delete() } catch (_: Exception) {} }
                } catch (e: Exception) {
                    Log.w(TAG, "BUG#4: failed to re-send ${f.name}: ${e.message} — will retry on next flush")
                }
            }
        }
    }

    // ─── Shutter Mode ──────────────────────────────────────────

    fun setShutterMode(mode: String) {
        _shutterMode.value = mode
        cancelTimer()
    }

    // NOTE: The old setHandTriggerEnabled/startHandDetection/stopHandDetection
    // dead code was removed — it was never called from anywhere and its
    // onHandLeft registration would have clobbered the screen's own callback.

    fun setOpSearchQuery(query: String) {
        _opSearchQuery.value = query
    }

    fun setOpCurrentTarget(student: Student) {
        _currentTarget.value = student
        _capturePhase.value = CapturePhase.READY_1
        _capturedPhotos.value = emptyList()
        socketManager.sendOpProgress("Target dipilih: ${student.nama}")
    }

    fun getTimerDuration(): Int = when (_shutterMode.value) {
        "timer-3" -> 3
        "timer-5" -> 5
        "timer-10" -> 10
        else -> 0
    }

    fun isTimerMode(): Boolean = _shutterMode.value.startsWith("timer-")

    private fun startTimer(onComplete: () -> Unit) {
        cancelTimer()
        val duration = getTimerDuration()
        if (duration <= 0) { onComplete(); return }

        _timerCountdown.value = duration
        timerJob = viewModelScope.launch {
            while (_timerCountdown.value > 0) {
                delay(1000)
                _timerCountdown.value -= 1
            }
            if (_timerCountdown.value <= 0) {
                onComplete()
            }
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _timerCountdown.value = 0
    }

    // ─── Gridline Settings ──────────────────────────────────────

    fun setGridlineEnabled(enabled: Boolean) {
        _gridlineSettings.value = _gridlineSettings.value.copy(enabled = enabled)
    }

    fun setGridlineType(type: GridlineType) {
        _gridlineSettings.value = _gridlineSettings.value.copy(type = type)
    }

    fun setGridlineThickness(thickness: GridlineThickness) {
        _gridlineSettings.value = _gridlineSettings.value.copy(thickness = thickness)
    }

    fun setGridlineColor(color: GridlineColor) {
        _gridlineSettings.value = _gridlineSettings.value.copy(color = color)
    }

    // ─── Frame Bitmap Decoding ──────────────────────────────────

    private fun decodeFrameBitmap(frameBase64: String?) {
        if (frameBase64 == null || frameBase64 == "__FRAME_SAVED__" || frameBase64.isEmpty()) {
            _frameBitmap.value = null
            return
        }

        try {
            val pureBase64 = if (frameBase64.contains(",")) {
                frameBase64.substringAfter(",")
            } else {
                frameBase64
            }
            val bytes = Base64.decode(pureBase64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            _frameBitmap.value = bitmap
            Log.i(TAG, "Frame bitmap decoded: ${bitmap?.width}x${bitmap?.height}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode frame bitmap: ${e.message}")
            _frameBitmap.value = null
        }
    }

    // ─── Cleanup ────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopStateRequestLoop()
        stopFrameRequestLoop()
        cancelTimer()
        cameraConnectedCollector?.cancel()
        cameraTypeCollector?.cancel()
        camera2ConnectedCollector?.cancel()
        camera2TypeCollector?.cancel()
        ptpConnectedCollector?.cancel()
        ptpTypeCollector?.cancel()
        socketManager.destroy()
        cameraUVCManager.destroy()
        camera2Manager.destroy()
        ptpCameraManager.destroy()
    }
}
