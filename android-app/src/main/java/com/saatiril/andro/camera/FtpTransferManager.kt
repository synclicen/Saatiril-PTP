package com.saatiril.andro.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * FtpTransferManager — fourth photo input engine: WiFi transfer, no USB.
 *
 * The camera (e.g. Sony a7 IV with built-in "FTP Transfer Function") is set to
 * auto-upload every shot to an FTP server. This class IS that server, running
 * inside the operator/admin app on the same phone (typically on the phone's own
 * hotspot, so it works fully offline at the venue).
 *
 * Why this exists: on some phones the USB host driver panics and reboots the
 * phone during PTP/MTP transfers (kernel-level bug — no app fix possible, even
 * with USB safe mode). WiFi removes the cable from the equation entirely:
 * the photographer just presses the camera's OWN shutter button, the photo
 * arrives here over WiFi, and is emitted on [ftpPhotos] in the exact same
 * shape as PTPCameraManager.externalShutterPhotos — so the existing pipeline
 * (frame overlay, phase gating, delivery) is reused unchanged.
 *
 * FTP subset implemented (deliberately tolerant — the camera drives, we accept):
 *   USER/PASS (any credentials), SYST, FEAT, OPTS, PWD, CWD, CDUP, TYPE A/I,
 *   PASV (advertises the exact address the camera reached us on), PORT (active
 *   mode — outbound data connection), STOR, APPE (resume), NOOP, ABOR, QUIT,
 *   MKD/RMD/DELE (accepted, no-op), LIST/NLST (empty), RETR/SIZE/MDTM (550 —
 *   read-only server), REST (350 → full resend). Unknown commands → 502; the
 *   control connection is never dropped on a bad command.
 *
 * Only JPEG files are delivered (magic bytes FF D8 FF — RAW/ARW/MP4 siblings
 * of the same shot are discarded). Files spool to cacheDir under a quota;
 * decoded downscaled (long edge ≤ 2560) and re-encoded q95 — identical to the
 * PTP engine path.
 */
class FtpTransferManager(private val context: Context) {

    companion object {
        private const val TAG = "FtpTransfer"

        /** FTP control port. Android apps can't bind <1024, so 2121 it is —
         *  set the same port in the camera's FTP server registration. */
        const val FTP_PORT = 2121

        private const val DECODE_MAX_EDGE = 2560               // same as PTP engine
        private const val JPEG_QUALITY = 95                    // same as PTP engine
        private const val MAX_FILE_BYTES = 128L * 1024 * 1024  // per-file cap
        private const val MAX_DRAIN_BYTES = 512L * 1024 * 1024 // oversize: drain bound
        private const val SPOOL_QUOTA_BYTES = 256L * 1024 * 1024 // dir cap
        private const val RECENT_MS = 15_000L                  // (filename,size) dedupe window
        private const val RECENT_MAX = 32                      // dedupe list cap
        private const val CONTROL_SO_TIMEOUT_MS = 60_000
        private const val DATA_CONNECT_TIMEOUT_MS = 15_000
        private const val DATA_ACCEPT_TIMEOUT_MS = 20_000
    }

    // ══════════════════════════════════════════════════════════════
    // Public state
    // ══════════════════════════════════════════════════════════════

    /** True while the FTP server socket is listening. */
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** True while at least one control connection is open (the camera has
     *  found us and is talking). */
    private val _clientConnected = MutableStateFlow(false)
    val clientConnected: StateFlow<Boolean> = _clientConnected.asStateFlow()

    /** Best local address as "ip:port" (what to type into the camera), or
     *  null while not running / no network. Prefers hotspot interfaces. */
    private val _serverAddress = MutableStateFlow<String?>(null)
    val serverAddress: StateFlow<String?> = _serverAddress.asStateFlow()

    /** Last accepted JPEG filename (status card). */
    private val _lastFile = MutableStateFlow<String?>(null)
    val lastFile: StateFlow<String?> = _lastFile.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Photos received from the camera over WiFi, as base64 JPEG data URLs —
     * the exact same payload shape as PTPCameraManager.externalShutterPhotos,
     * so screens deliver them through the identical pipeline.
     */
    private val _ftpPhotos = MutableSharedFlow<String>(
        extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val ftpPhotos: SharedFlow<String> = _ftpPhotos.asSharedFlow()

    // ══════════════════════════════════════════════════════════════
    // Internals
    // ══════════════════════════════════════════════════════════════

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val openConnections = AtomicInteger(0)

    /** Single-threaded processor: decode + emit completed transfers one at a
     *  time (the camera may open 2 parallel connections; serializing keeps
     *  dedupe state and memory bounded). */
    private val processExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "FtpProcess") }

    /** (filename, size, at) — dedupe against retry re-sends. */
    private val recentFiles = ArrayDeque<Triple<String, Long, Long>>()

    private fun spoolDir(): File {
        val dir = File(context.cacheDir, "ftp_receive")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ══════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════

    /** Start the FTP server. Idempotent. */
    fun start() {
        if (running.get()) return
        val ss: ServerSocket = try {
            ServerSocket(FTP_PORT, 8, InetAddress.getByName("0.0.0.0"))
        } catch (e: Exception) {
            _lastError.value = "Port $FTP_PORT tidak tersedia: ${e.message}"
            Log.e(TAG, "start: bind failed — ${e.message}")
            return
        }
        if (!running.compareAndSet(false, true)) {
            try { ss.close() } catch (_: IOException) {} // raced with another start
            return
        }
        serverSocket = ss
        _isRunning.value = true
        _lastError.value = null
        refreshServerAddress()
        Log.i(TAG, "FTP server listening on $FTP_PORT — address=${_serverAddress.value}")

        acceptThread = Thread({
            while (running.get()) {
                val client: Socket = try {
                    serverSocket?.accept() ?: break
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "accept: ${e.message}")
                    break
                }
                // Hotspot may have come up after start — refresh what we advertise.
                refreshServerAddress()
                openConnections.incrementAndGet()
                _clientConnected.value = true
                Log.i(TAG, "Client connected (${client.inetAddress?.hostAddress})")
                Thread({ serveClient(client) }, "FtpConn").start()
            }
        }, "FtpAccept")
        acceptThread?.start()
    }

    /** Stop the FTP server. Idempotent. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        _isRunning.value = false
        _clientConnected.value = false
        _serverAddress.value = null
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        acceptThread = null
        Log.i(TAG, "FTP server stopped")
    }

    /** Called by the ViewModel when the screen goes away for good. */
    fun destroy() {
        stop()
        processExecutor.shutdown()
    }

    // ══════════════════════════════════════════════════════════════
    // Address discovery — what to type into the camera
    // ══════════════════════════════════════════════════════════════

    private fun refreshServerAddress() {
        try {
            val addr = bestLocalIPv4()
            _serverAddress.value = if (addr != null) "${addr.hostAddress}:$FTP_PORT" else null
        } catch (e: Exception) {
            Log.w(TAG, "refreshServerAddress: ${e.message}")
        }
    }

    /** Prefer hotspot/soft-AP interfaces (the phone shares its connection to
     *  the camera), then any other up IPv4. */
    private fun bestLocalIPv4(): InetAddress? {
        var apCandidate: InetAddress? = null
        var anyCandidate: InetAddress? = null
        val nis: java.util.Enumeration<NetworkInterface> =
            try { NetworkInterface.getNetworkInterfaces() } catch (e: Exception) { null }
                ?: return null
        for (ni in nis) {
            try {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                val isAp = name.contains("ap") || name.contains("swlan") || name.contains("softap")
                for (ia in ni.inetAddresses) {
                    if (ia.isLoopbackAddress) continue
                    if (ia.hostAddress?.contains('.') != true) continue // IPv4 only
                    if (isAp && apCandidate == null) apCandidate = ia
                    if (anyCandidate == null) anyCandidate = ia
                }
            } catch (_: Exception) {
                // interface went away between calls — skip it
            }
        }
        return apCandidate ?: anyCandidate
    }

    // ══════════════════════════════════════════════════════════════
    // Control connection — one thread per client
    // ══════════════════════════════════════════════════════════════

    private fun serveClient(control: Socket) {
        var transferType = 'I'
        // PORT-mode target (camera asks us to connect out to it)
        var portHost: InetAddress? = null
        var portPort = -1
        // PASV-mode listener we opened (camera connects in to us)
        var passiveListener: ServerSocket? = null

        try {
            control.soTimeout = CONTROL_SO_TIMEOUT_MS
            control.tcpNoDelay = true
            val out = control.getOutputStream()
            val reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.ISO_8859_1), 4096)

            fun reply(line: String) {
                out.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
                out.flush()
                Log.d(TAG, ">> $line")
            }

            fun closePassive() {
                try { passiveListener?.close() } catch (_: IOException) {}
                passiveListener = null
            }

            reply("220 Saatiril FTP ready (${_serverAddress.value ?: "phone"}) — port $FTP_PORT")

            while (running.get()) {
                val line = try {
                    reader.readLine() ?: break        // EOF — camera closed
                } catch (e: java.net.SocketTimeoutException) {
                    reply("221 Timeout — goodbye"); break
                } catch (e: Exception) {
                    break                            // connection reset
                }
                if (line.isBlank()) continue
                Log.d(TAG, "<< $line")

                val upper = line.uppercase()
                val arg = line.substringAfter(' ', "").trim()

                when {
                    // vsftpd-style flow: 331 after USER, 230 after PASS — the
                    // shape every camera/FTP client in the field is built against.
                    // NOTE: startsWith everywhere — an equals-check on the whole line would
                    // also match the argument ("USER anonymous" != "USER") and 502 the
                    // login. Caught by the FTP protocol harness.
                    upper.startsWith("USER") -> reply("331 User OK — send password")
                    upper.startsWith("PASS") -> reply("230 OK — logged in")
                    upper.startsWith("ACCT") -> reply("230 OK")
                    upper.startsWith("SYST") -> reply("215 UNIX Type: L8")
                    upper.startsWith("FEAT") -> reply("211-Features:\r\n UTF8\r\n PASV\r\n PORT\r\n APPE\r\n REST\r\n211 End")
                    upper.startsWith("OPTS") -> reply("200 OK")
                    upper.startsWith("PWD") || upper.startsWith("XPWD") -> reply("257 \"/\" is current directory")
                    upper.startsWith("CWD") || upper.startsWith("XCWD") -> reply("250 OK — any directory accepted")
                    upper.startsWith("CDUP") || upper.startsWith("XCUP") -> reply("250 OK")
                    upper.startsWith("TYPE") -> {
                        transferType = if (arg.startsWith("A")) 'A' else 'I'
                        reply("200 Type set to $transferType")
                    }
                    upper.startsWith("MODE") -> reply("200 S")
                    upper.startsWith("STRU") -> reply("200 F")
                    upper.startsWith("NOOP") -> reply("200 OK")
                    upper.startsWith("QUIT") -> { reply("221 Bye"); break }

                    upper.startsWith("PASV") -> {
                        closePassive()
                        val local = control.localAddress ?: InetAddress.getByName("127.0.0.1")
                        try {
                            passiveListener = ServerSocket(0, 1, local)
                            val p = passiveListener!!.localPort
                            val ip = local.address
                            reply("227 Entering Passive Mode (" +
                                "${ip[0].toInt() and 0xFF},${ip[1].toInt() and 0xFF}," +
                                "${ip[2].toInt() and 0xFF},${ip[3].toInt() and 0xFF}," +
                                "${p / 256},${p % 256})")
                        } catch (e: Exception) {
                            passiveListener = null
                            reply("425 Cannot open passive port: ${e.message}")
                        }
                    }

                    upper.startsWith("PORT") -> {
                        closePassive()
                        val parts = arg.split(',')
                        if (parts.size == 6) {
                            try {
                                val ip = InetAddress.getByName(parts.slice(0..3).joinToString("."))
                                val p = parts[4].toInt() * 256 + parts[5].toInt()
                                if (p in 1..65535) {
                                    portHost = ip; portPort = p
                                    reply("200 PORT command successful")
                                } else reply("501 Bad port")
                            } catch (e: Exception) { reply("501 Bad address") }
                        } else reply("501 Syntax error")
                    }

                    upper.startsWith("STOR") || upper.startsWith("APPE") -> {
                        val isAppend = upper.startsWith("APPE")
                        if (arg.isBlank()) { reply("501 Missing path"); continue }
                        val data: Socket? = when {
                            passiveListener != null -> {
                                try {
                                    passiveListener!!.soTimeout = DATA_ACCEPT_TIMEOUT_MS
                                    val s = passiveListener!!.accept()
                                    closePassive()
                                    s
                                } catch (e: Exception) {
                                    Log.w(TAG, "PASV accept failed: ${e.message}")
                                    closePassive()
                                    null
                                }
                            }
                            portHost != null && portPort > 0 -> {
                                try {
                                    val s = Socket()
                                    try { s.bind(InetSocketAddress(control.localAddress, 0)) } catch (_: Exception) {}
                                    s.connect(InetSocketAddress(portHost, portPort), DATA_CONNECT_TIMEOUT_MS)
                                    s
                                } catch (e: Exception) {
                                    Log.w(TAG, "PORT connect failed: ${e.message}")
                                    null
                                }
                            }
                            else -> null
                        }
                        if (data == null) { reply("425 Can't open data connection"); continue }

                        val filename = arg.substringAfterLast('/').substringAfter('\\').trim()
                        if (filename.isBlank()) {
                            try { data.close() } catch (_: IOException) {}
                            reply("550 Invalid filename")
                            continue
                        }
                        reply("150 Ok to send data ($filename)")
                        try {
                            data.soTimeout = 60_000
                            val received = spoolStream(data, filename, isAppend)
                            try { data.close() } catch (_: IOException) {}
                            if (received >= 0) {
                                reply("226 Transfer complete ($received bytes)")
                                Log.i(TAG, "STOR/APPE $filename — $received bytes")
                                scheduleProcessing(filename)
                            } else {
                                reply("552 Aborted — exceeds ${MAX_FILE_BYTES / (1024 * 1024)}MB limit")
                            }
                        } catch (e: Exception) {
                            try { data.close() } catch (_: IOException) {}
                            reply("426 Connection closed — transfer failed: ${e.message}")
                        }
                    }

                    upper.startsWith("MKD") || upper.startsWith("XMKD") -> reply("257 OK — directory assumed")
                    upper.startsWith("RMD") || upper.startsWith("XRMD") -> reply("250 OK")
                    upper.startsWith("DELE") -> reply("250 OK")
                    upper.startsWith("LIST") || upper.startsWith("NLST") ->
                        reply("150 Opening data connection\r\n226 Done (read-only server)")
                    upper.startsWith("RETR") -> reply("550 Read-only server")
                    upper.startsWith("SIZE") -> reply("550 Not available")
                    upper.startsWith("MDTM") -> reply("550 Not available")
                    upper.startsWith("REST") -> reply("350 Restarting at 0 — full resend expected")
                    upper.startsWith("ABOR") -> reply("225 No transfer to abort")
                    upper.startsWith("AUTH") || upper.startsWith("PBSZ") || upper.startsWith("PROT") ->
                        reply("534 Plain FTP only — set camera security to FTP (not FTPS)")
                    upper.startsWith("SITE") -> reply("200 OK")
                    else -> reply("502 Command not implemented: ${line.take(20)}")
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.w(TAG, "serveClient: ${e.message}")
                _lastError.value = "Koneksi kamera terputus: ${e.message}"
            }
        } finally {
            val n = openConnections.decrementAndGet()
            if (n <= 0) _clientConnected.value = false
            Log.i(TAG, "Client gone, open=$n")
            try { control.close() } catch (_: IOException) {}
        }
    }

    /**
     * Stream the data connection to cacheDir/ftp_receive/<filename>.
     * STOR truncates; APPE appends (resumed transfer). Returns bytes written,
     * or -1 if the file exceeded [MAX_FILE_BYTES] (spool file is deleted).
     */
    private fun spoolStream(data: Socket, filename: String, append: Boolean): Long {
        enforceSpoolQuota()
        val safe = sanitize(filename)
        val target = File(spoolDir(), safe)
        val out = if (append) java.io.FileOutputStream(target, true) else java.io.FileOutputStream(target)
        val input = BufferedInputStream(data.getInputStream(), 64 * 1024)
        val buf = ByteArray(64 * 1024)
        var total = if (append) target.length() else 0L
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    out.write(buf, 0, n)
                    total += n
                    if (total > MAX_FILE_BYTES) {
                        out.close()
                        // Drain what the sender still has in flight (bounded) —
                        // stopping the read here would block the sender's writes on
                        // full socket buffers and stall its control connection.
                        var drained = 0L
                        while (drained < MAX_DRAIN_BYTES) {
                            val dn = input.read(buf)
                            if (dn < 0) break
                            drained += dn
                        }
                        target.delete()
                        return -1L
                    }
                }
            }
            out.flush()
            return total
        } finally {
            try { out.close() } catch (_: IOException) {}
        }
    }

    /** Delete oldest spool files until the directory is under quota. */
    private fun enforceSpoolQuota() {
        try {
            val dir = spoolDir()
            val files = dir.listFiles()?.toList() ?: return
            var total = files.sumOf { it.length() }
            if (total <= SPOOL_QUOTA_BYTES) return
            val sorted = files.sortedBy { it.lastModified() }
            for (f in sorted) {
                if (total <= SPOOL_QUOTA_BYTES) break
                total -= f.length()
                f.delete()
            }
        } catch (_: Exception) {
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Received-file processing — single executor thread
    // ══════════════════════════════════════════════════════════════

    /** Runs after every successful STOR/APPE completion (226 already sent). */
    private fun scheduleProcessing(filename: String) {
        processExecutor.execute {
            try {
                processReceivedFile(filename)
            } catch (e: Exception) {
                Log.e(TAG, "process $filename: ${e.message}")
            }
        }
    }

    private fun processReceivedFile(rawName: String) {
        val filename = rawName.substringAfterLast('/').substringAfter('\\').trim()
        val f = File(spoolDir(), sanitize(filename))
        if (!f.exists() || f.length() < 4) {
            if (f.exists()) f.delete()
            Log.w(TAG, "process: $filename — missing/empty, skipped")
            return
        }

        // JPEG check — magic bytes FF D8 FF (discards ARW/RAW/MP4 siblings).
        val head = ByteArray(3)
        java.io.RandomAccessFile(f, "r").use { it.readFully(head) }
        val magicOk = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        val nameOk = filename.uppercase().endsWith(".JPG") || filename.uppercase().endsWith(".JPEG")
        if (!magicOk || !nameOk) {
            val len = f.length()
            f.delete()
            Log.i(TAG, "process: $filename — not a JPEG (RAW/video sibling), deleted ($len B)")
            return
        }

        // Completion gate: a JPEG must end with the EOI marker (FF D9). A
        // transfer cut mid-file (WiFi drop) is left on disk UNTOUCHED so the
        // camera's APPE/STOR retry can complete it — a half photo is never
        // delivered, and the attempt is not dedupe-recorded either.
        if (f.length() >= 4) {
            val tail = ByteArray(2)
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(f.length() - 2)
                raf.readFully(tail)
            }
            if (!(tail[0] == 0xFF.toByte() && tail[1] == 0xD9.toByte())) {
                Log.i(TAG, "process: $filename — incomplete (no EOI), kept for resume (${f.length()} B)")
                return
            }
        }

        // Dedupe (filename, size) — a retry after a network drop can re-send
        // the same shot; also guards filename reuse after a card reformat.
        val size = f.length()
        val now = System.currentTimeMillis()
        synchronized(recentFiles) {
            while (recentFiles.isNotEmpty() &&
                (now - recentFiles.first().third > RECENT_MS || recentFiles.size > RECENT_MAX)) {
                recentFiles.removeFirst()
            }
            if (recentFiles.any { it.first == filename && it.second == size }) {
                f.delete()
                Log.i(TAG, "process: $filename — duplicate ($size B), deleted")
                return
            }
            recentFiles.addLast(Triple(filename, size, now))
        }

        // Read + decode downscaled (identical to the PTP path).
        val bytes = f.readBytes()
        f.delete()
        if (bytes.size < 4) return

        val bitmap = decodeDownscaled(bytes) ?: run {
            Log.w(TAG, "process: $filename — decode failed, skipped")
            return
        }
        val dataUrl = try { CameraCapture.bitmapToBase64(bitmap, JPEG_QUALITY) } finally { bitmap.recycle() }

        _lastFile.value = filename
        _lastError.value = null
        Log.i(TAG, "FTP photo delivered: $filename ($size B → ${dataUrl.length} chars)")
        _ftpPhotos.tryEmit(dataUrl)
    }

    /** Decode JPEG bytes with inSampleSize so the long edge is ≤ [DECODE_MAX_EDGE] —
     *  same algorithm as PTPCameraManager.decodeDownscaled. */
    private fun decodeDownscaled(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > DECODE_MAX_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._()-]"), "_").take(120)
}
