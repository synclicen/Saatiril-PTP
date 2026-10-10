package com.saatiril.andro.data

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.saatiril.andro.camera.Camera2Manager
import com.saatiril.andro.camera.PTPCameraManager
import com.saatiril.andro.camera.UVCCameraManager
import com.saatiril.andro.server.ClientInfo
import com.saatiril.andro.server.SaatirilServer
import com.saatiril.andro.server.ServerService
import com.saatiril.andro.server.ServerStats
import com.saatiril.andro.util.ExcelImporter
import com.saatiril.andro.util.PhotoSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ═════════════════════════════════════════════════════════════════════════
 * AdminViewModel — orchestrator for the Saatiril Android Admin app.
 * ═════════════════════════════════════════════════════════════════════════
 *
 * This is the Android equivalent of the Electron `main.ts` + the React
 * `main-app.tsx`/`project-setup.tsx`/`admin-dashboard.tsx` combined. It:
 *
 *  - Enforces the offline license ([LicenseManager]).
 *  - Manages the project list + current project ([ProjectStore]).
 *  - Drives project setup (name, mode, ratio, Excel import, output folder,
 *    session password).
 *  - Starts/stops the LAN Socket.io server ([SaatirilServer] via
 *    [ServerService] foreground service).
 *  - Handles incoming lan-messages: saves photos ([PhotoSaver]), updates the
 *    project DB, and responds to REQUEST_STATE / REQUEST_FRAME.
 *  - Exposes MC actions (callStudent / resetStudent / markStudentDone) which
 *    broadcast to operators via the server.
 *  - Drives top-level navigation (LICENSE → HUB → SETUP → MAIN).
 */
class AdminViewModel(application: Application) : AndroidViewModel(application) {

    companion object { private const val TAG = "AdminViewModel" }

    private val app = application
    private val gson = Gson()

    // ─── Sub-systems ────────────────────────────────────────────
    private val licenseManager = LicenseManager(application)
    private val projectStore = ProjectStore(application)
    val photoSaver = PhotoSaver(application)
    val driveBackupManager = com.saatiril.andro.backup.DriveBackupManager(application)
    val bleServerManager = com.saatiril.andro.ble.BLEServerManager(application)

    // ─── Navigation ─────────────────────────────────────────────
    enum class Screen { LOADING, LICENSE, ROLE_SELECT, HUB, SETUP, MAIN, GENERATOR, OPERATOR_CONNECT, OPERATOR_CAMERA, MC_CONNECT, MC_PANEL, MC_REMOTE, MC_MODE_SELECT, MC_REMOTE_SERVER }

    // Start with LOADING to prevent license screen flash
    private val _screen = MutableStateFlow(Screen.LOADING)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    init {
        // Start the Google Drive upload worker (processes queue in background)
        try {
            com.saatiril.andro.backup.DriveUploadWorker.getInstance(application)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to start DriveUploadWorker: ${e.message}")
        }

        // Set initial screen AFTER ViewModel is fully constructed
        // This prevents the license screen flash on app launch
        _screen.value = if (com.saatiril.andro.BuildConfig.MC_ONLY) {
            Screen.MC_MODE_SELECT
        } else if (licenseManager.getStatus().active) {
            Screen.ROLE_SELECT
        } else {
            Screen.LICENSE
        }
    }

    // ─── Camera managers (3-camera support: USB UVC + back + front) ───
    // Exposed so the AdminOperatorScreen can bind a TextureView and call
    // switchToCameraById(). These are the SAME hardened managers from the
    // android-operator APK (UVCCameraManager + Camera2Manager) — ported
    // verbatim so USB HDMI capture cards, front, and back cameras all work.
    val cameraUVCManager = UVCCameraManager(application)
    val camera2Manager = Camera2Manager(application)

    // PTP: direct DSLR control over USB (Picture Transfer Protocol)
    val ptpCameraManager = PTPCameraManager(application)

    /** Combined list of (cameraId, displayName) from all managers (PTP + UVC + Camera2). */
    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    /** "uvc" / "camera2" / "ptp" — which engine is currently active. */
    private val _activeCameraEngine = MutableStateFlow<String>("camera2")
    val activeCameraEngine: StateFlow<String> = _activeCameraEngine.asStateFlow()

    /** The current camera ID (from whichever engine is active). */
    val currentCameraId: StateFlow<String>
        get() = when (_activeCameraEngine.value) {
            "camera2" -> camera2Manager.currentCameraIdFlow
            "ptp" -> ptpCameraManager.currentCameraIdFlow
            else -> cameraUVCManager.currentCameraIdFlow
        }

    /** "uvc" / "builtin" / "ptp" / "none" — human-readable camera source label. */
    private val _cameraSource = MutableStateFlow<String>("builtin")
    val cameraSource: StateFlow<String> = _cameraSource.asStateFlow()

    /** Refresh the combined camera list from all engines (PTP + UVC + Camera2). */
    fun refreshAvailableCameras() {
        val ptpList = ptpCameraManager.availableCameras.value
        val uvcList = cameraUVCManager.availableCameras.value
        val c2List = camera2Manager.availableCameras.value
        _availableCameras.value = ptpList + uvcList + c2List
    }

    /** Switch to a specific camera by ID (handles PTP, UVC, and Camera2). */
    fun switchToCameraById(cameraId: String) {
        // PTP camera (ID starts with "ptp:")
        if (cameraId.startsWith("ptp:")) {
            Log.i(TAG, "switchToCameraById: PTP $cameraId")
            _activeCameraEngine.value = "ptp"
            try { cameraUVCManager.hidePreview() } catch (_: Exception) {}
            try { camera2Manager.closeCamera() } catch (_: Exception) {}
            ptpCameraManager.switchCamera(cameraId)
            _cameraSource.value = "ptp"
            return
        }

        val c2Cameras = camera2Manager.availableCameras.value
        val isCamera2 = c2Cameras.any { it.first == cameraId }
        if (isCamera2) {
            Log.i(TAG, "switchToCameraById: Camera2 $cameraId")
            _activeCameraEngine.value = "camera2"
            try { cameraUVCManager.hidePreview() } catch (_: Exception) {}
            camera2Manager.openCamera(cameraId)
            _cameraSource.value = "builtin"
        } else {
            Log.i(TAG, "switchToCameraById: UVC $cameraId")
            _activeCameraEngine.value = "uvc"
            try { camera2Manager.closeCamera() } catch (_: Exception) {}
            cameraUVCManager.switchCamera(cameraId)
            _cameraSource.value = "uvc"
        }
    }

    /** Force rescan for USB cameras (user tapped "Pindai Ulang USB"). */
    fun forceRescanUsbCamera() {
        Log.i(TAG, "forceRescanUsbCamera")
        cameraUVCManager.forceRescan()
        ptpCameraManager.forceRescan()
        refreshAvailableCameras()
    }

    /** Decoded frame bitmap (for overlay during capture). */
    private val _frameBitmap = MutableStateFlow<android.graphics.Bitmap?>(null)
    val frameBitmap: StateFlow<android.graphics.Bitmap?> = _frameBitmap.asStateFlow()

    /** Decode the current frame data URL into a Bitmap (for capture overlay). */
    fun decodeFrameBitmap() {
        val dataUrl = _project.value?.config?.frame ?: return
        if (dataUrl.isBlank() || dataUrl == "__FRAME_SAVED__") return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pure = if (dataUrl.contains(",")) dataUrl.substringAfter(",") else dataUrl
                val bytes = Base64.decode(pure, Base64.DEFAULT)
                _frameBitmap.value = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                Log.i(TAG, "Frame bitmap decoded: ${_frameBitmap.value?.width}x${_frameBitmap.value?.height}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode frame bitmap: ${e.message}", e)
            }
        }
    }

    // ─── License ────────────────────────────────────────────────
    private val _licenseStatus = MutableStateFlow(licenseManager.getStatus())
    val licenseStatus: StateFlow<LicenseStatus> = _licenseStatus.asStateFlow()

    fun activateLicense(code: String): Boolean {
        val ok = licenseManager.activate(code.trim())
        if (ok) {
            _licenseStatus.value = licenseManager.getStatus()
            _screen.value = Screen.ROLE_SELECT
        }
        return ok
    }

    fun deactivateLicense() {
        licenseManager.deactivate()
        _licenseStatus.value = licenseManager.getStatus()
        _screen.value = Screen.ROLE_SELECT
    }

    /** User chose Admin role — go to HUB (or LICENSE if no license). */
    fun selectAdminRole() {
        _screen.value = if (licenseManager.getStatus().active) Screen.HUB else Screen.LICENSE
    }

    /** User chose Operator role — go to OPERATOR_CONNECT (no license needed). */
    fun selectOperatorRole() {
        _screen.value = Screen.OPERATOR_CONNECT
    }

    /** User chose MC role — go to MC_CONNECT (no license needed). */
    fun selectMcRole() {
        _screen.value = Screen.MC_CONNECT
    }

    /** User chose MC Remote (BLE) role — go to MC_REMOTE (no license needed). */
    fun selectMcRemoteRole() {
        _screen.value = Screen.MC_REMOTE
    }

    /** User chose MC Remote Server (BLE Server for Electron) — go to MC_REMOTE_SERVER. */
    fun selectMcRemoteServerRole() {
        _screen.value = Screen.MC_REMOTE_SERVER
    }

    /** Go back to role selection from operator/MC screens. */
    fun backToRoleSelect() {
        // MC-only APK: return to mode selector (BLE or WiFi)
        _screen.value = if (com.saatiril.andro.BuildConfig.MC_ONLY) Screen.MC_MODE_SELECT else Screen.ROLE_SELECT
    }

    /** Operator connected — go to OPERATOR_CAMERA. */
    fun operatorConnected() {
        _screen.value = Screen.OPERATOR_CAMERA
    }

    /** Operator disconnected — go back to OPERATOR_CONNECT. */
    fun operatorDisconnected() {
        _screen.value = Screen.OPERATOR_CONNECT
    }

    /** MC connected — go to MC_PANEL. */
    fun mcConnected() {
        _screen.value = Screen.MC_PANEL
    }

    /** MC disconnected — go back to MC_CONNECT. */
    fun mcDisconnected() {
        _screen.value = Screen.MC_CONNECT
    }

    /** Open the developer license-code generator screen. */
    fun openGenerator() { _screen.value = Screen.GENERATOR }

    /** Close the generator — return to role selection. */
    fun closeGenerator() {
        _screen.value = Screen.ROLE_SELECT
    }

    /**
     * Generate a 30-day license code for a customer's Machine ID, authorized
     * by the developer Admin Key. Returns the full [LicenseManager.GenerateResult]
     * (code + expiry + display fields) or null on invalid input / wrong admin key.
     */
    fun generateLicenseCode(machineId: String, adminKey: String): LicenseManager.GenerateResult? {
        return licenseManager.generateLicenseFull(machineId.trim(), adminKey.trim())
    }

    // ─── Saved projects (Hub) ───────────────────────────────────
    private val _savedProjects = MutableStateFlow<List<Project>>(emptyList())
    val savedProjects: StateFlow<List<Project>> = _savedProjects.asStateFlow()

    fun refreshProjects() {
        _savedProjects.value = projectStore.list()
    }

    fun deleteProject(id: String) {
        projectStore.delete(id)
        refreshProjects()
    }

    fun openHub() { refreshProjects(); _screen.value = Screen.HUB }

    // ─── Project Setup state ────────────────────────────────────
    private val _setupName = MutableStateFlow("Wisuda " + android.text.format.DateFormat.format("yyyy", System.currentTimeMillis()))
    val setupName: StateFlow<String> = _setupName.asStateFlow()
    fun setSetupName(v: String) { _setupName.value = v }

    private val _setupMode = MutableStateFlow(CameraModes.SINGLE)
    val setupMode: StateFlow<String> = _setupMode.asStateFlow()
    fun setSetupMode(v: String) {
        _setupMode.value = v
        // When switching modes, re-merge the channel data so the combined
        // list reflects the new mode (dual uses both channels, others use only ch0).
        refreshCombinedStudents()
    }

    private val _setupRatio = MutableStateFlow("4:3")
    val setupRatio: StateFlow<String> = _setupRatio.asStateFlow()
    fun setSetupRatio(v: String) { _setupRatio.value = v }

    private val _setupPreset = MutableStateFlow("original")
    val setupPreset: StateFlow<String> = _setupPreset.asStateFlow()
    fun setSetupPreset(v: String) { _setupPreset.value = v }

    private val _setupPassword = MutableStateFlow<String?>(null)
    val setupPassword: StateFlow<String?> = _setupPassword.asStateFlow()
    fun setSetupPassword(v: String?) { _setupPassword.value = v?.takeIf { it.isNotBlank() } }

    private val _setupStudents = MutableStateFlow<List<Student>>(emptyList())
    val setupStudents: StateFlow<List<Student>> = _setupStudents.asStateFlow()

    // ─── Dual-channel Excel uploads (matches Electron's excelData[2]) ───
    // For mode "dual", the admin uploads 2 separate Excel files: one for
    // JALUR KIRI (channel 1) and one for JALUR KANAN (channel 2). Each
    // student is tagged with assignedChannel so the operator panel can
    // filter by channel. For single/photoshoot modes, only channel 0 is used.
    private val _setupChannel0Students = MutableStateFlow<List<Student>>(emptyList())
    val setupChannel0Students: StateFlow<List<Student>> = _setupChannel0Students.asStateFlow()
    private val _setupChannel0FileName = MutableStateFlow<String>("")
    val setupChannel0FileName: StateFlow<String> = _setupChannel0FileName.asStateFlow()

    private val _setupChannel1Students = MutableStateFlow<List<Student>>(emptyList())
    val setupChannel1Students: StateFlow<List<Student>> = _setupChannel1Students.asStateFlow()
    private val _setupChannel1FileName = MutableStateFlow<String>("")
    val setupChannel1FileName: StateFlow<String> = _setupChannel1FileName.asStateFlow()

    private val _importingChannel = MutableStateFlow<Int?>(null)
    val importingChannel: StateFlow<Int?> = _importingChannel.asStateFlow()

    /** Import students from an Excel/CSV file into the given channel (0 or 1). */
    fun importExcelChannel(uri: Uri, channel: Int) {
        _importingChannel.value = channel
        viewModelScope.launch {
            try {
                val students = withContext(Dispatchers.IO) { ExcelImporter.import(app, uri) }
                if (channel == 0) {
                    _setupChannel0Students.value = students
                    _setupChannel0FileName.value = uri.lastPathSegment?.substringAfterLast('/') ?: "file.xlsx"
                } else {
                    _setupChannel1Students.value = students
                    _setupChannel1FileName.value = uri.lastPathSegment?.substringAfterLast('/') ?: "file.xlsx"
                }
                // Refresh the combined list (with assignedChannel tags)
                refreshCombinedStudents()
            } catch (e: Exception) {
                Log.e(TAG, "Excel import channel $channel failed", e)
            } finally {
                _importingChannel.value = null
            }
        }
    }

    /** Clear the Excel data for a specific channel. */
    fun clearChannelExcel(channel: Int) {
        if (channel == 0) {
            _setupChannel0Students.value = emptyList()
            _setupChannel0FileName.value = ""
        } else {
            _setupChannel1Students.value = emptyList()
            _setupChannel1FileName.value = ""
        }
        refreshCombinedStudents()
    }

    /** Merge both channels' students into _setupStudents, tagging each with assignedChannel. */
    private fun refreshCombinedStudents() {
        val mode = _setupMode.value
        val combined = mutableListOf<Student>()
        if (CameraModes.isDualMode(mode) && mode != CameraModes.DUAL_PHOTOSHOOT) {
            // dual: channel 0 → assignedChannel=1, channel 1 → assignedChannel=2
            combined.addAll(_setupChannel0Students.value.map { it.copy(assignedChannel = 1) })
            combined.addAll(_setupChannel1Students.value.map { it.copy(assignedChannel = 2) })
        } else {
            // single / single-photoshoot / dual-photoshoot: only channel 0, all assignedChannel=1
            combined.addAll(_setupChannel0Students.value.map { it.copy(assignedChannel = 1) })
        }
        _setupStudents.value = combined
    }

    private val _setupOutputFolderUri = MutableStateFlow<String?>(photoSaver.getOutputFolder()?.toString())
    val setupOutputFolderUri: StateFlow<String?> = _setupOutputFolderUri.asStateFlow()

    // ─── Frame overlay (PNG, base64 data URL) ──────────────────
    private val _setupFrame = MutableStateFlow<String?>(null)
    val setupFrame: StateFlow<String?> = _setupFrame.asStateFlow()

    private val _setupFrameFileName = MutableStateFlow<String>("")
    val setupFrameFileName: StateFlow<String> = _setupFrameFileName.asStateFlow()

    /** Set/clear the frame overlay (base64 data URL of a PNG file). */
    fun setSetupFrame(dataUrl: String?, fileName: String = "") {
        _setupFrame.value = dataUrl
        _setupFrameFileName.value = fileName
    }

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    /** Begin a new project (resets setup state). */
    fun startNewProjectSetup() {
        _setupName.value = "Wisuda " + android.text.format.DateFormat.format("yyyy", System.currentTimeMillis())
        _setupMode.value = CameraModes.SINGLE
        _setupRatio.value = "4:3"
        _setupPreset.value = "original"
        _setupPassword.value = null
        _setupStudents.value = emptyList()
        _setupChannel0Students.value = emptyList()
        _setupChannel0FileName.value = ""
        _setupChannel1Students.value = emptyList()
        _setupChannel1FileName.value = ""
        _setupFrame.value = null
        _setupFrameFileName.value = ""
        _importStatus.value = null
        _editingProjectId.value = null
        _screen.value = Screen.SETUP
    }

    /** Edit an existing saved project (resume). */
    fun editProject(id: String) {
        val p = projectStore.load(id) ?: return
        _editingProjectId.value = p.id
        _setupName.value = p.name
        _setupMode.value = p.config.mode
        _setupRatio.value = p.config.ratio
        _setupPreset.value = p.config.preset
        _setupPassword.value = p.config.sessionPassword?.takeIf { it != "__PASSWORD_SET__" }
        _setupStudents.value = p.database
        // Restore per-channel data (split the database back into channels)
        _setupChannel0Students.value = p.database.filter { it.assignedChannel == 1 }
        _setupChannel0FileName.value = if (_setupChannel0Students.value.isNotEmpty()) "channel1.xlsx" else ""
        _setupChannel1Students.value = p.database.filter { it.assignedChannel == 2 }
        _setupChannel1FileName.value = if (_setupChannel1Students.value.isNotEmpty()) "channel2.xlsx" else ""
        // Restore frame (if it was saved as a real data URL, not the stripped sentinel)
        val savedFrame = p.config.frame
        if (savedFrame != null && savedFrame != "__FRAME_SAVED__" && savedFrame.startsWith("data:image/")) {
            _setupFrame.value = savedFrame
            _setupFrameFileName.value = "frame.png"
        } else {
            _setupFrame.value = null
            _setupFrameFileName.value = ""
        }
        _importStatus.value = "${p.database.size} mahasiswa dimuat"
        _screen.value = Screen.SETUP
    }
    private val _editingProjectId = MutableStateFlow<String?>(null)

    /** Import students from an Excel (.xlsx) or CSV file at the given Uri. */
    fun importExcel(uri: Uri) {
        _importStatus.value = "Mengimpor…"
        viewModelScope.launch {
            try {
                val students = withContext(Dispatchers.IO) { ExcelImporter.import(app, uri) }
                _setupStudents.value = students
                _importStatus.value = "Berhasil: ${students.size} mahasiswa"
            } catch (e: Exception) {
                Log.e(TAG, "Excel import failed", e)
                _importStatus.value = "Gagal impor: ${e.message}"
            }
        }
    }

    /** Set the SAF output folder (from ACTION_OPEN_DOCUMENT_TREE). */
    fun pickOutputFolder(treeUri: Uri) {
        photoSaver.setOutputFolder(treeUri)
        _setupOutputFolderUri.value = treeUri.toString()
    }

    // ─── Current (running) project ──────────────────────────────
    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    /** Startup error message (shown on the Setup screen if the server fails to start). */
    private val _startupError = MutableStateFlow<String?>(null)
    val startupError: StateFlow<String?> = _startupError.asStateFlow()

    /** True while the server is starting (disables the Start button). */
    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    /**
     * Create the project from setup state, persist it, start the server, and
     * move to MAIN. This is the "Start" button on the Setup screen.
     *
     * Wrapped in comprehensive error handling: if the foreground service or the
     * ktor server fails to start, the error is surfaced to the user via
     * [startupError] instead of crashing the app.
     */
    fun createAndStartProject() {
        if (_starting.value) return  // prevent double-tap
        Log.i(TAG, "createAndStartProject called — starting server initialization")
        _starting.value = true
        _startupError.value = null

        viewModelScope.launch {
            try {
                val name = _setupName.value.trim().ifEmpty { "Proyek Wisuda" }
                Log.i(TAG, "Project: name='$name', students=${_setupStudents.value.size}, mode=${_setupMode.value}")
                val students = _setupStudents.value
                val password = _setupPassword.value

                val projectId = _editingProjectId.value ?: java.util.UUID.randomUUID().toString()

                // Create project subfolder inside the admin's chosen output folder.
                // Matches Electron: targetFolder = root/sanitized_project_name
                // Electron uses fs.mkdirSync(targetFolder, {recursive:true}) + path.join.
                // Android SAF: create a sub-document (folder) inside the tree URI.
                val rootFolderUri = _setupOutputFolderUri.value ?: ""
                val sanitizedName = name.replace(Regex("[^a-zA-Z0-9_\\-\\s]"), "").replace(Regex("\\s+"), "_")
                if (rootFolderUri.isNotEmpty()) {
                    try {
                        val treeUri = android.net.Uri.parse(rootFolderUri)
                        val subfolderUri = photoSaver.createSubfolder(treeUri, sanitizedName)
                        if (subfolderUri != null) {
                            // Update the output folder to the subfolder — photos go here
                            photoSaver.setOutputFolder(subfolderUri)
                            _setupOutputFolderUri.value = subfolderUri.toString()
                            Log.i(TAG, "Project subfolder created: $sanitizedName → $subfolderUri")
                        } else {
                            Log.w(TAG, "Failed to create subfolder '$sanitizedName' — using root folder")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating project subfolder: ${e.message}", e)
                    }
                }

                val project = Project(
                    id = projectId,
                    name = name,
                    config = ProjectConfig(
                        mode = _setupMode.value,
                        ratio = _setupRatio.value,
                        preset = _setupPreset.value,
                        targetFolder = _setupOutputFolderUri.value ?: "",
                        frame = _setupFrame.value,
                        sessionPassword = password,
                        localFolder = sanitizedName
                    ),
                    database = students,
                    photoHistory = emptyList(),
                    captureVersions = emptyMap()
                )

                persistProject(project)
                _project.value = project
                refreshProjects()

                // Wire the lan-message handler BEFORE starting the server.
                SaatirilServer.onLanMessage = ::onLanMessage
                SaatirilServer.setSessionPasswordHash(password?.let { sha256(it) })

                // Start the foreground service first (keeps the process alive).
                // This calls startForegroundService() which is async — the service's
                // onStartCommand runs on the main thread shortly after.
                withContext(Dispatchers.IO) {
                    try {
                        Log.i(TAG, "Starting foreground service...")
                        ServerService.start(app)
                        Log.i(TAG, "Foreground service start requested")
                    } catch (e: Exception) {
                        Log.e(TAG, "Foreground service start failed (non-fatal): ${e.message}", e)
                        // Non-fatal — the ktor server can still run while app is in foreground.
                    }

                    // Start the ktor server (singleton). This is the call most likely
                    // to throw if there's a port conflict or a ktor init error.
                    try {
                        Log.i(TAG, "Starting ktor SaatirilServer...")
                        SaatirilServer.start(app)
                        Log.i(TAG, "SaatirilServer started successfully on port ${SaatirilServer.port.value}")
                    } catch (e: Exception) {
                        Log.e(TAG, "SaatirilServer.start FAILED", e)
                        throw e
                    }
                }

                // Verify server is actually running before navigating
                if (!SaatirilServer.running.value) {
                    Log.e(TAG, "Server reported not running after start() returned")
                    throw RuntimeException("Server tidak berhasil dimulai (running=false)")
                }

                Log.i(TAG, "Project created successfully — navigating to MAIN")
                _screen.value = Screen.MAIN
                // Pre-decode the frame overlay bitmap for use during capture.
                decodeFrameBitmap()

                // Start BLE server for MC Remote (no WiFi needed for MC trigger)
                startBLEServer()
            } catch (e: Exception) {
                Log.e(TAG, "createAndStartProject FAILED", e)
                _startupError.value = "Gagal memulai server: ${e.message ?: e.javaClass.simpleName}"
                // Clean up partial state
                // #8: SaatirilServer.stop() blocks for seconds — run it off the
                // main thread (ANR) while the error is surfaced to the user.
                viewModelScope.launch(Dispatchers.IO) {
                    try { SaatirilServer.stop() } catch (_: Exception) {}
                }
                try { ServerService.stop(app) } catch (_: Exception) {}
                SaatirilServer.onLanMessage = null
            } finally {
                _starting.value = false
            }
        }
    }

    /**
     * Export the student database to an Excel .xls file in the output folder.
     * Uses HTML-table format with .xls extension — Excel and Google Sheets
     * open this natively with styled headers and colored status cells.
     * No Apache POI needed (POI uses invoke-polymorphic-method-handle which
     * Android D8/R8 cannot dex).
     * Columns: No, NIM, Nama, Status, Channel — matches Electron exportToExcel.
     */
    fun exportToExcel(): String? {
        val proj = _project.value ?: return null
        val timeStamp = android.text.format.DateFormat.format("yyyyMMdd_HHmmss", System.currentTimeMillis()).toString()
        val filename = "Daftar_Peserta_${proj.name.replace(" ", "_")}_$timeStamp.xls"
        return try {
            val sb = StringBuilder()
            sb.append("<html xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:x=\"urn:schemas-microsoft-com:office:excel\" xmlns=\"http://www.w3.org/TR/REC-html40\">")
            sb.append("<head><meta charset=\"UTF-8\"><!--[if gte mso 9]><xml><x:ExcelWorkbook><x:ExcelWorksheets><x:ExcelWorksheet><x:Name>Peserta</x:Name><x:WorksheetOptions><x:DisplayGridlines/></x:WorksheetOptions></x:ExcelWorksheet></x:ExcelWorksheets></x:ExcelWorkbook></xml><![endif]--></head>")
            sb.append("<body><table border=\"1\">")
            // Header row with styling
            sb.append("<tr style=\"background-color:#1a0b2e;color:#d4af37;font-weight:bold;\">")
            for (h in arrayOf("No", "NIM", "Nama", "Status", "Channel")) {
                sb.append("<td>").append(h).append("</td>")
            }
            sb.append("</tr>")
            // Data rows
            proj.database.forEachIndexed { i, s ->
                val statusLabel = when {
                    s.status == "pending" -> "Menunggu"
                    s.status == "sent" -> "Dikirim"
                    s.status == "done" -> "Selesai"
                    isActiveStatus(s.status) -> "Aktif Ch.${getActiveChannel(s.status) ?: "?"}"
                    else -> s.status
                }
                val rowColor = when {
                    s.status == "done" -> "#22c55e"
                    s.status == "sent" -> "#06b6d4"
                    isActiveStatus(s.status) -> "#d4af37"
                    else -> "#ffffff"
                }
                sb.append("<tr>")
                sb.append("<td>").append(i + 1).append("</td>")
                sb.append("<td style=\"mso-number-format:'\\@';\">").append(escapeXml(s.nim)).append("</td>")
                sb.append("<td>").append(escapeXml(s.nama)).append("</td>")
                sb.append("<td style=\"color:$rowColor;font-weight:bold;\">").append(statusLabel).append("</td>")
                sb.append("<td>").append(s.assignedChannel).append("</td>")
                sb.append("</tr>")
            }
            sb.append("</table></body></html>")
            val uri = photoSaver.saveTextFile(sb.toString(), filename, "application/vnd.ms-excel")
            Log.i(TAG, "Excel exported: $filename (${proj.database.size} rows)")
            filename
        } catch (e: Exception) {
            Log.e(TAG, "Excel export failed: ${e.message}", e)
            null
        }
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Legacy CSV export — kept for fallback. */
    fun exportToCsv(): String? {
        val proj = _project.value ?: return null
        val timeStamp = android.text.format.DateFormat.format("yyyyMMdd_HHmmss", System.currentTimeMillis()).toString()
        val filename = "Daftar_Peserta_${proj.name.replace(" ", "_")}_$timeStamp.csv"
        val sb = StringBuilder()
        sb.append("No,NIM,Nama,Status,Channel\n")
        proj.database.forEachIndexed { i, s ->
            val statusLabel = when {
                s.status == "pending" -> "Menunggu"
                s.status == "sent" -> "Dikirim"
                s.status == "done" -> "Selesai"
                isActiveStatus(s.status) -> "Aktif Ch.${getActiveChannel(s.status) ?: "?"}"
                else -> s.status
            }
            sb.append("${i + 1},\"${s.nim}\",\"${s.nama}\",\"$statusLabel\",${s.assignedChannel}\n")
        }
        return try {
            photoSaver.saveTextFile(sb.toString(), filename, "text/csv")
            filename
        } catch (e: Exception) { null }
    }

    /** Stop the server and return to the Hub. */
    fun stopServer() {
        // #8: SaatirilServer.stop() blocks the calling thread for ~4.5s
        // (broadcast flush + engine stop) — never on the main thread (ANR).
        // The UI flips immediately; teardown runs on Dispatchers.IO.
        _project.value = null
        _screen.value = Screen.HUB
        SaatirilServer.onLanMessage = null
        SaatirilServer.onBleTrigger = null
        viewModelScope.launch(Dispatchers.IO) {
            try { SaatirilServer.stop() } catch (e: Exception) { Log.e(TAG, "stop error: ${e.message}", e) }
            try { ServerService.stop(app) } catch (_: Exception) {}
            stopBLEServer()
            refreshProjects()
        }
    }

    // ─── BLE Server for MC Remote ─────────────────────────────
    /** Start BLE GATT server for MC Remote trigger. */
    /**
     * Retry BLE advertising after the BLUETOOTH_ADVERTISE permission is granted.
     * Called by MainScaffold's permission-request callback. At project-start
     * time, startBLEServer ran but advertising FAILED silently because the
     * permission was missing (Android 12+) — the MC remote couldn't find the
     * admin during scan. After the user grants the permission, this restarts
     * the advertising so the MC remote can discover + connect.
     */
    fun retryBleAdvertising() {
        if (bleServerManager.isRunning()) {
            bleServerManager.restartAdvertising()
        } else {
            startBLEServer()
        }
    }

    private fun startBLEServer() {
        try {
            val proj = _project.value ?: return

            // Set up trigger callback
            bleServerManager.onTriggerReceived = { action, studentId ->
                handleBLETrigger(action, studentId)
            }
            // #5: also accept triggers forwarded over HTTP by the Electron
            // admin's embedded /admin-ble page (POST /api/ble-trigger on
            // this server) — same handler as native BLE triggers.
            SaatirilServer.onBleTrigger = { action, studentId ->
                handleBLETrigger(action, studentId)
            }

            // Re-push queue data + next student when an MC connects (the data was
            // pushed at project-start before the MC connected → notify was a no-op).
            bleServerManager.onClientConnected = {
                pushBLEQueueData()
                pushBLENextStudent()
            }

            // Send initial project info
            bleServerManager.updateProjectInfo(
                projectName = proj.name,
                mode = proj.config.mode,
                ratio = proj.config.ratio
            )

            // Start BLE server
            bleServerManager.start()

            // Send initial queue data
            pushBLEQueueData()
            pushBLENextStudent()
            bleServerManager.updateStatus(com.saatiril.andro.ble.BLEProtocol.Phase.STANDBY)

            Log.i(TAG, "BLE Server started for MC Remote")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start BLE server: ${e.message}")
        }
    }

    /** Stop BLE GATT server. */
    private fun stopBLEServer() {
        try {
            bleServerManager.stop()
            bleServerManager.onTriggerReceived = null
            SaatirilServer.onBleTrigger = null
            Log.i(TAG, "BLE Server stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop BLE server: ${e.message}")
        }
    }

    /** Handle trigger from MC Remote (via BLE). */
    private fun handleBLETrigger(action: String, studentId: String?) {
        val proj = _project.value ?: return
        val myChannel = 1 // Admin is always channel 1 in combined mode

        when (action) {
            com.saatiril.andro.ble.BLEProtocol.Action.PANGGIL -> {
                // Call the next pending student
                val nextPending = proj.database.firstOrNull { it.status == "pending" }
                if (nextPending != null) {
                    callStudent(nextPending, myChannel)
                }
            }
            com.saatiril.andro.ble.BLEProtocol.Action.NEXT -> {
                // Skip current active, call next pending
                val active = proj.database.firstOrNull { isActiveStatus(it.status) }
                if (active != null) {
                    // Reset active to pending, then call next
                    resetStudent(active.id, myChannel)
                    val nextPending = proj.database.firstOrNull { it.status == "pending" }
                    if (nextPending != null) {
                        callStudent(nextPending, myChannel)
                    }
                } else {
                    // No active, just call next pending
                    val nextPending = proj.database.firstOrNull { it.status == "pending" }
                    if (nextPending != null) {
                        callStudent(nextPending, myChannel)
                    }
                }
            }
            com.saatiril.andro.ble.BLEProtocol.Action.RESET -> {
                // Reset current active student
                val active = proj.database.firstOrNull { isActiveStatus(it.status) }
                if (active != null) {
                    resetStudent(active.id, myChannel)
                }
            }
        }

        // Update BLE data after action
        pushBLEQueueData()
        pushBLENextStudent()
    }

    /** Push current queue data to MC via BLE. */
    fun pushBLEQueueData() {
        try {
            val proj = _project.value ?: return
            val db = proj.database
            val myChannel = 1

            val pending = db.filter { it.status == "pending" }
            val active = db.filter { isActiveStatus(it.status) }
            val done = db.filter { it.status == "done" }

            // Build COMPACT JSON (array format per student — saves ~30 bytes per
            // student vs object keys). Summary uses short keys: t=total, p=pending,
            // d=done, a=active.
            // Each student is an array: [id, nim, nama(20), status, channel].
            fun compactStudent(s: Student) = org.json.JSONArray().apply {
                put(s.id); put(s.nim); put(s.nama.take(20)); put(s.status); put(s.assignedChannel)
            }

            // Build the students list in a Kotlin list first (org.json.JSONArray
            // on this classpath has no remove()), then convert to JSONArray.
            val studentList = mutableListOf<org.json.JSONArray>()
            // Active student(s) first (if any) — include assignedChannel.
            active.forEach { s -> studentList.add(compactStudent(s)) }
            // #10: add pending students one at a time while the payload still
            // fits the BLE characteristic budget (BLEProtocol.MAX_CHAR_SIZE).
            // The old fixed "next 4" could reach ~540-570B > 512B usable and the
            // notification never reached the MC remote.
            for (s in pending) {
                studentList.add(compactStudent(s))
                if (queueJsonByteSize(db.size, pending.size, done.size, active.size, studentList) >
                    com.saatiril.andro.ble.BLEProtocol.MAX_CHAR_SIZE) {
                    studentList.removeAt(studentList.size - 1)
                    break
                }
            }

            val studentsArray = org.json.JSONArray()
            studentList.forEach { studentsArray.put(it) }

            val json = org.json.JSONObject().apply {
                put("t", db.size)
                put("p", pending.size)
                put("d", done.size)
                put("a", active.size)
                put("s", studentsArray)
            }

            bleServerManager.updateQueueData(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "pushBLEQueueData error: ${e.message}")
        }
    }

    /** Serialized UTF-8 size (#10) of the queue JSON with the given students list. */
    private fun queueJsonByteSize(t: Int, p: Int, d: Int, a: Int, students: List<org.json.JSONArray>): Int {
        val arr = org.json.JSONArray()
        students.forEach { arr.put(it) }
        return org.json.JSONObject().apply {
            put("t", t); put("p", p); put("d", d); put("a", a); put("s", arr)
        }.toString().toByteArray(Charsets.UTF_8).size
    }

    /** Push next/active student to MC via BLE. */
    fun pushBLENextStudent() {
        try {
            val proj = _project.value ?: return
            val active = proj.database.firstOrNull { isActiveStatus(it.status) }
            val nextPending = proj.database.firstOrNull { it.status == "pending" }

            val student = active ?: nextPending
            if (student != null) {
                // #10: keep the payload within the BLE characteristic budget —
                // the name is the only unbounded field, so shrink it until it fits.
                var nama = student.nama
                var json = nextStudentJson(student, nama)
                while (json.toString().toByteArray(Charsets.UTF_8).size >
                    com.saatiril.andro.ble.BLEProtocol.MAX_CHAR_SIZE && nama.isNotEmpty()) {
                    nama = nama.dropLast(1)
                    json = nextStudentJson(student, nama)
                }
                bleServerManager.updateNextStudent(json.toString())
            } else {
                bleServerManager.updateNextStudent("{}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "pushBLENextStudent error: ${e.message}")
        }
    }

    /** #10: next-student JSON with a (possibly truncated) display name. */
    private fun nextStudentJson(s: Student, nama: String): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("id", s.id)
            put("nim", s.nim)
            put("nama", nama)
            put("status", s.status)
        }

    /**
     * Handle a photo captured locally by the admin's own phone camera (the
     * Operator tab in MainScaffold). This saves the photo to the output folder,
     * updates the project DB, and broadcasts SYNC_DB to all connected clients —
     * the same flow as [handlePhotosSaved] but initiated locally instead of
     * arriving via socket from a remote operator.
     */
    // ─── Capture phase (for admin operator's own camera) ───────
    // In standard mode, the admin captures 2 photos per student: Toga then Ijazah.
    // In photoshoot mode, only 1 photo. This state machine tracks partial captures.
    private val _opCapturedPhotos = MutableStateFlow<List<String>>(emptyList())
    val opCapturedPhotos: StateFlow<List<String>> = _opCapturedPhotos.asStateFlow()

    private val _capturePhase = MutableStateFlow(CapturePhase.STANDBY)
    val capturePhase: StateFlow<CapturePhase> = _capturePhase.asStateFlow()

    /** Reset the capture state when a new student is called or after finalize. */
    fun resetCaptureState() {
        _opCapturedPhotos.value = emptyList()
        _capturePhase.value = CapturePhase.STANDBY
    }

    /**
     * Handle a photo captured locally by the admin's own phone camera.
     *
     * In **standard mode**: the first capture is "Toga" (phase READY_1 → READY_2),
     * the second is "Ijazah" (phase READY_2 → SENDING). Both are saved with
     * versioned filenames, then PHOTOS_SAVED is broadcast.
     *
     * In **photoshoot mode**: only 1 photo is captured, saved with the
     * photoshoot filename convention, then PHOTOS_SAVED is broadcast.
     *
     * Filenames use the project's captureVersions map so retakes produce
     * `NIM_Nama_1_Toga_v2.jpg` instead of overwriting the original.
     */
    fun handleLocalCapture(student: Student, base64Photo: String, channel: Int) {
        val proj = _project.value ?: return
        val mode = proj.config.mode
        val isPhotoshoot = CameraModes.isPhotoshootMode(mode)
        val photosPerSession = CameraModes.photosPerSession(mode)  // 1 or 2

        // Accumulate the captured photo
        val currentPhotos = _opCapturedPhotos.value.toMutableList()
        currentPhotos.add(base64Photo)
        _opCapturedPhotos.value = currentPhotos

        if (currentPhotos.size < photosPerSession) {
            // Not enough photos yet — advance the phase, wait for the next shutter
            _capturePhase.value = if (currentPhotos.size == 1) CapturePhase.READY_2 else CapturePhase.READY_1
            // #11: Emit OP_PROGRESS so MC tab shows live operator status (matches Electron operator-panel.tsx:805)
            val progressMsg = if (currentPhotos.size == 1) "Pose 1 OK — Siap Foto 2" else "Siap Foto 1"
            _opProgress.value = _opProgress.value.toMutableMap().apply { put(channel, progressMsg) }
            SaatirilServer.broadcastLanMessage(SocketEvents.OP_PROGRESS,
                OpProgressData(channel = channel, status = progressMsg))
            return
        }

        // ── All photos captured — finalize ──
        _capturePhase.value = CapturePhase.SENDING
        val photos = currentPhotos.toList()

        // Compute version from captureVersions (increment on retake)
        val versionKey = "${student.id}_$channel"
        val currentVersion = proj.captureVersions[versionKey] ?: 0
        val newVersion = currentVersion + 1

        // Build filenames per mode
        val filenames: List<String> = if (isPhotoshoot) {
            listOf(com.saatiril.andro.util.FilenameUtils.buildPhotoshootFilename(
                student.nim, student.nama, channel, newVersion))
        } else {
            // Standard: photo[0]=Toga (suffix 1), photo[1]=Ijazah (suffix 2)
            photos.mapIndexed { i, _ ->
                com.saatiril.andro.util.FilenameUtils.buildStandardFilename(
                    student.nim, student.nama, i + 1,
                    if (i == 0) "Toga" else "Ijazah", newVersion)
            }
        }

        // Save each photo to the SAF output folder (on IO dispatcher)
        // CRITICAL: This must complete BEFORE we broadcast PHOTOS_SAVED.
        // The save is synchronous within the coroutine to ensure photos are
        // actually written to disk before the app reports success.
        viewModelScope.launch(Dispatchers.IO) {
            // First verify the output folder is accessible
            val folderUri = photoSaver.getOutputFolder()
            if (folderUri == null) {
                Log.e(TAG, "=== SAVE FAILED: No output folder set! ===")
                Log.e(TAG, "PhotoSaver.getOutputFolder() returned null. " +
                    "Check if pickOutputFolder() was called and persisted URI permission was granted.")
                return@launch
            }
            Log.i(TAG, "=== Saving ${photos.size} photo(s) to folder: $folderUri ===")

            for ((i, photo) in photos.withIndex()) {
                try {
                    Log.i(TAG, "Saving photo ${i+1}/${photos.size}: ${filenames[i]} " +
                        "(base64 length: ${photo.length})")
                    val savedUri = photoSaver.savePhoto(photo, filenames[i])
                    if (savedUri != null) {
                        Log.i(TAG, "✅ Photo saved: ${filenames[i]} → $savedUri")
                        // Per-project Google Drive backup (PER-PROJECT, opt-in).
                        // uploadPhotoForProject uses THIS project's driveFolder
                        // (null = no backup for this project — NOT auto-connected).
                        // Synchronous local SAF copy on Dispatchers.IO (fast).
                        driveBackupManager.uploadPhotoForProject(savedUri.toString(), filenames[i], _project.value?.config?.driveFolder)
                    } else {
                        Log.e(TAG, "❌ Photo save returned null: ${filenames[i]} " +
                            "(savePhoto returned null — check PhotoSaver logs)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to save ${filenames[i]}: ${e.message}", e)
                }
            }
            Log.i(TAG, "=== Save complete: ${photos.size} photo(s) processed ===")
        }

        // Update project DB
        val updatedDb = proj.database.map { s ->
            if (s.id == student.id) {
                // Dual-photoshoot: done if EITHER channel has photographed.
                // Other modes: done after this capture session.
                s.copy(status = "done")
            } else s
        }
        val historyItem = PhotoHistoryItem(student = student, photos = photos, channel = channel)
        val updatedHistory = proj.photoHistory.filterNot { it.student.id == student.id && it.channel == channel } + historyItem
        val updatedVersions = proj.captureVersions.toMutableMap().apply { put(versionKey, newVersion) }
        _project.value = proj.copy(database = updatedDb, photoHistory = updatedHistory, captureVersions = updatedVersions)
        _project.value?.let { persistProject(it) }

        // Broadcast PHOTOS_SAVED (so remote operators/MCs update) + SYNC_DB
        SaatirilServer.broadcastLanMessage(SocketEvents.PHOTOS_SAVED, PhotosSavedData(
            student = student.copy(status = "done"),
            photos = photos,
            channel = channel,
            version = newVersion,
            filename = filenames.firstOrNull() ?: ""
        ))
        // In non-photoshoot modes, also emit STUDENT_DONE (matches Electron operator-panel.tsx:894)
        // so the MC panel unblocks immediately without waiting for the heavy PHOTOS_SAVED payload.
        if (!isPhotoshoot) {
            SaatirilServer.broadcastLanMessage(SocketEvents.STUDENT_DONE, StudentDoneData(student.id, channel))
        }
        // #11: Emit OP_PROGRESS "Selesai" so MC knows operator is done (matches Electron operator-panel.tsx:907)
        _opProgress.value = _opProgress.value.toMutableMap().apply { put(channel, "Selesai — Menunggu target...") }
        SaatirilServer.broadcastLanMessage(SocketEvents.OP_PROGRESS,
            OpProgressData(channel = channel, status = "Selesai — Menunggu target..."))
        pushSyncDb()

        // Update BLE MC Remote — photo done, ready for next
        bleServerManager.updateStatus(com.saatiril.andro.ble.BLEProtocol.Phase.DONE, student.id)
        pushBLEQueueData()
        pushBLENextStudent()

        // Reset capture state for the next student
        resetCaptureState()
    }

    // ─── Server state (delegated to SaatirilServer singleton) ───
    val serverRunning: StateFlow<Boolean> = SaatirilServer.running
    val serverClients: StateFlow<List<ClientInfo>> = SaatirilServer.clients
    val serverStats: StateFlow<ServerStats> = SaatirilServer.stats
    val lanIp: StateFlow<String?> = SaatirilServer.lanIp
    val serverPort: StateFlow<Int> = SaatirilServer.port

    // ─── MC channel selection (for dual modes) ─────────────────
    private val _myChannel = MutableStateFlow(1)
    val myChannel: StateFlow<Int> = _myChannel.asStateFlow()

    fun setMyChannel(ch: Int) { _myChannel.value = ch }

    // ─── MC actions (broadcast via server) ─────────────────────
    /**
     * Call a student to the stage (non-photoshoot modes: single/dual).
     * Sets status to active_<channel>, emits MC_CALL to operators on that channel.
     * Channels are INDEPENDENT in dual mode — ch1 MC → ch1 operator only.
     */
    fun callStudent(student: Student, channel: Int) {
        _project.value?.let { proj ->
            // Simple map — only set the called student to active_N. No stale-reset
            // scan (matches the Electron handleCallNow fix ce50e92: in normal flow
            // STUDENT_DONE marks the previous student 'done' before the MC calls
            // the next, so no stale active_N to reset).
            val updatedDb = proj.database.map { s ->
                if (s.id == student.id) s.copy(status = "active_$channel") else s
            }
            val updated = proj.copy(database = updatedDb)
            _project.value = updated
            // #7: persist status changes (same rationale as handleMcCall — a
            // crash mid-ceremony must not revert the call queue).
            persistProject(updated)
        }
        SaatirilServer.broadcastLanMessage(SocketEvents.MC_CALL, McCallData(student, channel))
        pushSyncDb()
        // Update BLE MC Remote
        pushBLEQueueData()
        pushBLENextStudent()
        bleServerManager.updateStatus(com.saatiril.andro.ble.BLEProtocol.Phase.READY, student.id)
    }

    /**
     * Send a student to operator(s) — photoshoot modes only.
     * - single-photoshoot: send to 1 channel (myChannel)
     * - dual-photoshoot: send to BOTH channels simultaneously (cooperative)
     * Sets status to 'sent' (not active_N), operators pick from queue.
     */
    fun sendToOperator(student: Student, channels: List<Int>) {
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == student.id) s.copy(status = "sent") else s
            }
            val updated = proj.copy(database = updatedDb)
            _project.value = updated
            // #7: persist status changes (crash mid-ceremony must not revert).
            persistProject(updated)
        }
        // Emit MC_CALL to each requested channel
        for (ch in channels) {
            SaatirilServer.broadcastLanMessage(SocketEvents.MC_CALL, McCallData(student, ch))
        }
        pushSyncDb()
    }

    fun resetStudent(studentId: String, channel: Int) {
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == studentId) s.copy(status = "pending") else s
            }
            // Clear ALL photoHistory entries for this student (matches Electron mc-panel.tsx 423-425)
            val updatedHistory = proj.photoHistory.filterNot { it.student.id == studentId }
            _project.value = proj.copy(database = updatedDb, photoHistory = updatedHistory)
            _project.value?.let { persistProject(it) }
        }
        SaatirilServer.broadcastLanMessage(SocketEvents.STUDENT_RESET, StudentResetData(studentId, channel))
        pushSyncDb()
        // Update BLE MC Remote
        pushBLEQueueData()
        pushBLENextStudent()
        bleServerManager.updateStatus(com.saatiril.andro.ble.BLEProtocol.Phase.STANDBY, studentId)
    }

    /** markStudentDone is now ONLY called automatically by handlePhotosSaved/handleLocalCapture.
     *  Removed from MC panel — completion is event-driven (operator photo → auto-done). */
    fun markStudentDone(studentId: String, channel: Int) {
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == studentId) s.copy(status = "done") else s
            }
            val updated = proj.copy(database = updatedDb)
            _project.value = updated
            // #7: persist status changes (same rationale as handleStudentDone).
            persistProject(updated)
        }
        SaatirilServer.broadcastLanMessage(SocketEvents.STUDENT_DONE, StudentDoneData(studentId, channel))
        pushSyncDb()
    }

    /**
     * Set the CURRENT project's Google Drive backup folder (PER-PROJECT, opt-in).
     * @param folderUri the SAF tree Uri string (from DriveBackupManager.
     *   takeProjectBackupPermission), or null to disable backup for this project.
     * Updates the project's config.driveFolder + persists. NOT auto-connected —
     * the admin explicitly calls this per-project. Each project can have its
     * own folder (or none).
     */
    fun setProjectDriveFolder(folderUri: String?) {
        _project.value?.let { proj ->
            val updated = proj.copy(config = proj.config.copy(driveFolder = folderUri))
            _project.value = updated
            persistProject(updated)
        }
    }

    /** Broadcast the current project state to all clients (SYNC_DB). */
    fun pushSyncDb() {
        val proj = _project.value ?: return
        // Strip sensitive/large fields (matches the protocol).
        val stripped = proj.copy(
            config = proj.config.copy(
                sessionPassword = if (proj.config.sessionPassword != null) "__PASSWORD_SET__" else null
            ),
            photoHistory = proj.photoHistory.map { it.copy(photos = emptyList()) }
        )
        SaatirilServer.broadcastLanMessage(SocketEvents.SYNC_DB, SyncDbData(stripped))
    }

    // ─── OP_PROGRESS (live operator status text per channel) ───
    // Electron mc-panel.tsx:84-85 subscribes to OP_PROGRESS and displays
    // "Ch.{n}: {status}" so MC knows when to call the next student.
    private val _opProgress = MutableStateFlow<Map<Int, String>>(emptyMap())
    val opProgress: StateFlow<Map<Int, String>> = _opProgress.asStateFlow()

    private fun handleOpProgress(data: JsonElement?) {
        val obj = data as? JsonObject ?: return
        val channel = obj.get("channel")?.takeIf { !it.isJsonNull }?.asInt ?: return
        val status = obj.get("status")?.takeIf { !it.isJsonNull }?.asString ?: return
        _opProgress.value = _opProgress.value.toMutableMap().apply { put(channel, status) }
        Log.d(TAG, "OP_PROGRESS ch$channel: $status")
    }

    // ─── Lan-message handler (called by SaatirilServer) ─────────
    private fun onLanMessage(event: String, data: JsonElement?, senderSid: String?) {
        try {
            when (event) {
                SocketEvents.PHOTOS_SAVED -> handlePhotosSaved(data)
                SocketEvents.MC_CALL -> handleMcCall(data)
                SocketEvents.STUDENT_DONE -> handleStudentDone(data)
                SocketEvents.STUDENT_RESET -> handleStudentReset(data)
                SocketEvents.REQUEST_STATE -> handleRequestState(senderSid)
                SocketEvents.REQUEST_FRAME -> handleRequestFrame(data, senderSid)
                SocketEvents.OP_PROGRESS -> handleOpProgress(data)
                SocketEvents.SYNC_DB -> { /* we are the source of truth; ignore */ }
                else -> Log.d(TAG, "lan-message '$event' (unhandled)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onLanMessage error for $event: ${e.message}", e)
        }
    }

    private fun handlePhotosSaved(data: JsonElement?) {
        val obj = data as? JsonObject ?: return
        val student = parseStudent(obj.get("student") as? JsonObject)
        val photos = parseStringList(obj.get("photos"))
        val channel = obj.get("channel")?.takeIf { !it.isJsonNull }?.asInt ?: 1
        val version = obj.get("version")?.takeIf { !it.isJsonNull }?.asInt ?: 1
        val primaryFilename = obj.get("filename")?.takeIf { !it.isJsonNull }?.asString ?: ""

        if (photos.isEmpty() || student.id.isBlank()) return

        val proj = _project.value ?: return
        val mode = proj.config.mode
        val isPhotoshoot = CameraModes.isPhotoshootMode(mode)
        val isDualPhotoshoot = mode == CameraModes.DUAL_PHOTOSHOOT
        val photosPerSession = CameraModes.photosPerSession(mode)

        // hasEnoughPhotos check (matches Electron admin-dashboard.tsx line 173)
        if (photos.size < photosPerSession) {
            Log.w(TAG, "PHOTOS_SAVED: not enough photos (${photos.size} < $photosPerSession) — ignoring")
            return
        }

        // Save each photo to the SAF output folder.
        viewModelScope.launch(Dispatchers.IO) {
            for ((i, photo) in photos.withIndex()) {
                val filename = deriveFilename(primaryFilename, student, channel, version, i, photos.size, isPhotoshoot)
                try {
                    val savedUri = photoSaver.savePhoto(photo, filename)
                    if (savedUri != null) {
                        // Per-project Google Drive backup (PER-PROJECT, opt-in).
                        // Uses THIS project's driveFolder (null = no backup —
                        // NOT auto-connected). Synchronous local SAF copy.
                        driveBackupManager.uploadPhotoForProject(savedUri.toString(), filename, proj.config?.driveFolder)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save photo $filename: ${e.message}")
                }
            }
        }

        // Determine if the student is "done":
        // - Standard / single-photoshoot: done after this PHOTOS_SAVED.
        // - Dual-photoshoot (#9): done only when BOTH channels have photographed —
        //   this channel just got its photos, so require the OTHER channel's
        //   photoHistory entry too (the cross-channel check that was previously
        //   computed but never used — `otherHasPhotos` was dead code).
        val shouldMarkDone = if (isDualPhotoshoot) {
            val otherChannel = if (channel == 1) 2 else 1
            proj.photoHistory.any { it.student.id == student.id && it.channel == otherChannel && it.photos.isNotEmpty() }
        } else {
            true
        }

        // Update project: add to photoHistory, mark student done (if applicable).
        val updatedDb = proj.database.map { s ->
            if (s.id == student.id && shouldMarkDone) s.copy(status = "done") else s
        }
        val historyItem = PhotoHistoryItem(student = student, photos = photos, channel = channel)
        val updatedHistory = proj.photoHistory.filterNot { it.student.id == student.id && it.channel == channel } + historyItem
        val updatedVersions = proj.captureVersions.toMutableMap().apply {
            put("${student.id}_$channel", version)
        }
        _project.value = proj.copy(
            database = updatedDb,
            photoHistory = updatedHistory,
            captureVersions = updatedVersions
        )
        _project.value?.let { persistProject(it) }
    }

    private fun handleMcCall(data: JsonElement?) {
        val obj = data as? JsonObject ?: return
        val studentObj = obj.get("student") as? JsonObject ?: return
        val student = parseStudent(studentObj)
        val channel = obj.get("channel")?.takeIf { !it.isJsonNull }?.asInt ?: 1
        _project.value?.let { proj ->
            // Simple map — only set the called student to active_N. No stale-reset
            // scan (matches the Electron handleCallNow fix ce50e92: in normal flow
            // STUDENT_DONE marks the previous student 'done' before the MC calls
            // the next, so no stale active_N to reset).
            val updatedDb = proj.database.map { s ->
                if (s.id == student.id) s.copy(status = "active_$channel") else s
            }
            val updated = proj.copy(database = updatedDb)
            _project.value = updated
            // #7: persist status changes so a crash mid-ceremony doesn't revert
            // the MC call queue.
            persistProject(updated)
        }
        // Broadcast updated DB to all clients (including the MC that sent this)
        // so MC web panel sees the status change confirmed
        pushSyncDb()
    }

    private fun handleStudentDone(data: JsonElement?) {
        val obj = data as? JsonObject ?: return
        val studentId = obj.get("studentId")?.takeIf { !it.isJsonNull }?.asString ?: return
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == studentId) s.copy(status = "done") else s
            }
            val updated = proj.copy(database = updatedDb)
            _project.value = updated
            // #7: persist status changes so a crash mid-ceremony doesn't revert
            // the student's done state.
            persistProject(updated)
        }
        // Broadcast updated DB to all clients
        pushSyncDb()
    }

    private fun handleStudentReset(data: JsonElement?) {
        val obj = data as? JsonObject ?: return
        val studentId = obj.get("studentId")?.takeIf { !it.isJsonNull }?.asString ?: return
        _project.value?.let { proj ->
            val updatedDb = proj.database.map { s ->
                if (s.id == studentId) s.copy(status = "pending") else s
            }
            // Clear ALL photoHistory entries for this student so the operator
            // queue re-shows them (matches Electron mc-panel.tsx lines 423-425).
            val updatedHistory = proj.photoHistory.filterNot { it.student.id == studentId }
            _project.value = proj.copy(database = updatedDb, photoHistory = updatedHistory)
            _project.value?.let { persistProject(it) }
        }
        // Broadcast updated DB to all clients
        pushSyncDb()
    }

    private fun handleRequestState(senderSid: String?) {
        // A client just connected and wants the current project state.
        pushSyncDb()
    }

    private fun handleRequestFrame(data: JsonElement?, senderSid: String?) {
        val proj = _project.value ?: return
        val frame = proj.config.frame
        if (senderSid == null) return
        if (frame.isNullOrBlank() || frame == "__FRAME_SAVED__") {
            SaatirilServer.sendLanMessageToClient(senderSid, SocketEvents.FRAME_DATA,
                FrameDataPayload(projectId = proj.id, frame = ""))
        } else {
            SaatirilServer.sendLanMessageToClient(senderSid, SocketEvents.FRAME_DATA,
                FrameDataPayload(projectId = proj.id, frame = frame))
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    // ─── Project persistence (#7) ───────────────────────────────
    // All projectStore.save() calls go through persistProject(): dispatched to
    // Dispatchers.IO (never main-thread disk I/O) and serialized behind a mutex
    // so concurrent lan-message bursts never interleave file writes.
    private val saveMutex = Mutex()

    private fun persistProject(project: Project) {
        viewModelScope.launch(Dispatchers.IO) {
            saveMutex.withLock { projectStore.save(project) }
        }
    }

    private fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun parseStudent(obj: JsonObject?): Student {
        if (obj == null) return Student()
        return Student(
            id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: "",
            nim = obj.get("nim")?.takeIf { !it.isJsonNull }?.asString ?: "",
            nama = obj.get("nama")?.takeIf { !it.isJsonNull }?.asString ?: "",
            status = obj.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "pending",
            assignedChannel = obj.get("assignedChannel")?.takeIf { !it.isJsonNull }?.asInt
                ?: obj.get("assigned_channel")?.takeIf { !it.isJsonNull }?.asInt ?: 1
        )
    }

    private fun parseStringList(el: JsonElement?): List<String> {
        if (el == null || !el.isJsonArray) return emptyList()
        return el.asArray.mapNotNull { it.takeIf { !it.isJsonNull }?.asString }
    }

    private val JsonElement.asArray get() = this.asJsonArray.toList()

    /**
     * Derive a filename for photo[index] given the primary filename from the
     * operator. Matches the NIM_Nama_N_Toga.jpg / NIM_Nama_N_Ijazah.jpg
     * convention (see FilenameUtils).
     */
    private fun deriveFilename(
        primary: String, student: Student, channel: Int, version: Int,
        index: Int, total: Int, isPhotoshoot: Boolean = false
    ): String {
        // Photoshoot mode: 1 photo, use buildPhotoshootFilename
        if (isPhotoshoot) {
            return com.saatiril.andro.util.FilenameUtils.buildPhotoshootFilename(
                student.nim, student.nama, channel, version)
        }
        // Standard mode: photo[0]=Toga (suffix 1), photo[1]=Ijazah (suffix 2)
        return com.saatiril.andro.util.FilenameUtils.buildStandardFilename(
            student.nim, student.nama, index + 1,
            if (index == 0) "Toga" else "Ijazah", version)
    }

    override fun onCleared() {
        // Don't stop the server here — it should outlive the ViewModel (foreground service).
        super.onCleared()
    }
}
