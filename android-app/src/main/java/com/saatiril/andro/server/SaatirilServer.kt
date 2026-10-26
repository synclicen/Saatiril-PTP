package com.saatiril.andro.server

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ═════════════════════════════════════════════════════════════════════════
 * SaatirilServer — the LAN Socket.io hub, running ON the Android phone.
 * ═════════════════════════════════════════════════════════════════════════
 *
 * This is the Android equivalent of the Electron app's bundled server
 * (`electron/main.ts` + `mini-services/saatiril-socket/index.ts`). It:
 *  - Listens on port 3003 (or the next free port up to 3010).
 *  - Speaks Engine.IO v3 (polling + websocket) so the existing
 *    `io.socket:socket.io-client:2.1.0` Operator APK connects cleanly.
 *  - Relays `lan-message` packets between authenticated clients (MC, Operator).
 *  - Enforces the admin-set session password.
 *  - Notifies the app (via [onLanMessage]) of app events (PHOTOS_SAVED, MC_CALL,
 *    REQUEST_STATE, …) so [com.saatiril.andro.data.AdminViewModel] can save
 *    photos, update the project DB, and respond with SYNC_DB / FRAME_DATA.
 *
 * The server runs as a foreground service ([ServerService]) so it survives
 * backgrounding during a live ceremony.
 */
object SaatirilServer {

    private const val TAG = "SaatirilServer"
    private const val DEFAULT_PORT = 3003
    private const val MAX_PORT_ATTEMPTS = 8
    private const val PING_INTERVAL_MS = 5000L
    private const val PING_TIMEOUT_MS = 15000L
    private const val SESSION_TIMEOUT_MS = 90_000L   // drop silent sessions
    private const val POLL_HOLD_MS = 25_000L         // long-poll hold
    private const val MAX_PAYLOAD = 20 * 1024 * 1024 // 20MB (matches Node server)
    private const val MAX_CONNECTIONS = 10           // #32: connection limit (matches Electron)
    private const val IDENTIFY_TIMEOUT_MS = 30_000L  // #31: disconnect unidentified clients after 30s

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: io.ktor.server.engine.ApplicationEngine? = null
    private var cleanupJob: Job? = null

    // ─── Public reactive state ──────────────────────────────────
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _lanIp = MutableStateFlow<String?>(null)
    val lanIp: StateFlow<String?> = _lanIp.asStateFlow()

    private val _clients = MutableStateFlow<List<ClientInfo>>(emptyList())
    val clients: StateFlow<List<ClientInfo>> = _clients.asStateFlow()

    private val _stats = MutableStateFlow(ServerStats())
    val stats: StateFlow<ServerStats> = _stats.asStateFlow()

    // ─── Internal session store ─────────────────────────────────
    private val sessions = ConcurrentHashMap<String, ClientSession>()
    // #12: atomic counters — increments previously raced across handler threads
    private val totalConnections = AtomicLong(0)
    private val totalMessagesRelayed = AtomicLong(0)
    @Volatile private var sessionPasswordHash: String? = null

    /** App-level callback for lan-message events (set by AdminViewModel). */
    @Volatile
    var onLanMessage: ((event: String, data: JsonElement?, senderSid: String?) -> Unit)? = null

    /** App-level callback for BLE trigger requests POSTed to /api/ble-trigger
     *  (set by AdminViewModel — wired to the same handler as native BLE triggers). */
    @Volatile
    var onBleTrigger: ((action: String, studentId: String?) -> Unit)? = null

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start the ktor server on the first free port from 3003 upward.
     * Safe to call repeatedly (no-op if already running).
     *
     * @throws RuntimeException if the server fails to bind any port or ktor
     *   initialization fails (e.g. missing class after R8 shrinking). The
     *   caller ([com.saatiril.andro.data.AdminViewModel]) catches this and
     *   surfaces it to the user instead of crashing.
     */
    fun start(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (_running.value) {
            Log.w(TAG, "Server already running on port ${_port.value}")
            return
        }
        val ip = detectLanIp()
        _lanIp.value = ip
        Log.i(TAG, "Starting Saatiril LAN server. LAN IP: $ip")

        var boundPort = DEFAULT_PORT
        var lastError: Throwable? = null
        for (attempt in 0 until MAX_PORT_ATTEMPTS) {
            val tryPort = DEFAULT_PORT + attempt
            try {
                // Use the CIO factory OBJECT directly (not the string-based
                // embeddedServer that relies on ServiceLoader). On Android with
                // R8, the ServiceLoader file
                // `META-INF/services/io.ktor.server.engine.ApplicationEngineFactory`
                // can be stripped/empty → "Array is empty" error when ktor calls
                // `ServiceLoader.iterator().next()` on an empty loader.
                // Passing `io.ktor.server.cio.CIO` directly bypasses that lookup.
                engine = io.ktor.server.engine.embeddedServer(
                    factory = io.ktor.server.cio.CIO,
                    port = tryPort,
                    host = "0.0.0.0",
                    watchPaths = emptyList()
                ) {
                    install(WebSockets)
                    install(ContentNegotiation) { gson() }
                    routing {
                        get("/") { handlePollingGet(call) }
                        post("/") { handlePollingPost(call) }
                        webSocket("/") { handleWebSocket(this) }
                        // #5: BLE trigger bridge — the Electron admin's embedded
                        // /admin-ble page (AdminBleHtml.kt) forwards MC
                        // PANGGIL/NEXT/RESET triggers here via fetch(). Routed to
                        // the same AdminViewModel handler as native BLE triggers.
                        post("/api/ble-trigger") { handleBleTrigger(call) }
                        // #29: /health endpoint for monitoring (matches Electron index.ts:46-62)
                        get("/health") {
                            call.respondText(
                                """{"status":"ok","uptime":${System.currentTimeMillis() - startedAt},"connectedClients":${sessions.count { it.value.authenticated }},"totalConnections":${totalConnections.get()},"totalMessagesRelayed":${totalMessagesRelayed.get()},"maxConnections":$MAX_CONNECTIONS,"sessionPasswordActive":${sessionPasswordHash != null}}""",
                                ContentType.Application.Json
                            )
                        }
                        // MC web page — served at /mc?channel=1
                        // MC scans QR code → browser opens this page → connects via WebSocket
                        get("/mc") {
                            call.respondText(
                                com.saatiril.andro.server.web.MC_HTML,
                                ContentType.Text.Html
                            )
                        }
                        // Operator web page — served at /operator?channel=1
                        // Operator scans QR code → browser opens this page → camera + shutter
                        // Supports: front/back camera, USB capture card, hand trigger (MediaPipe)
                        get("/operator") {
                            call.respondText(
                                com.saatiril.andro.server.web.OPERATOR_HTML,
                                ContentType.Text.Html
                            )
                        }
                        // MC BLE Remote web page — uses Web Bluetooth API
                        // MC opens this in Chrome/Edge → connects via BLE to Admin
                        get("/mc-ble") {
                            call.respondText(
                                com.saatiril.andro.server.web.MC_BLE_HTML,
                                ContentType.Text.Html
                            )
                        }
                        // Admin BLE Client web page — Electron connects to MC-Only APK (BLE Server)
                        get("/admin-ble") {
                            call.respondText(
                                com.saatiril.andro.server.web.ADMIN_BLE_HTML,
                                ContentType.Text.Html
                            )
                        }
                    }
                }.also { it.start(wait = false) }
                boundPort = tryPort
                lastError = null
                break
            } catch (e: Throwable) {
                // Catch Throwable (not just Exception) so NoClassDefFoundError
                // and NoSuchMethodError (common after R8 shrinking) are caught
                // and reported, rather than crashing the app silently.
                Log.w(TAG, "Port $tryPort bind/init failed: ${e.javaClass.simpleName}: ${e.message}", e)
                lastError = e
            }
        }

        if (lastError != null && engine == null) {
            Log.e(TAG, "Failed to bind any port or init ktor", lastError)
            _running.value = false
            throw RuntimeException("SaatirilServer init failed: ${lastError.message}", lastError)
        }

        _port.value = boundPort
        _running.value = true
        startedAt = System.currentTimeMillis()
        startCleanupJob()
        Log.i(TAG, "Server listening on 0.0.0.0:$boundPort (LAN IP $ip)")
    }

    /** Stop the server and drop all sessions. */
    fun stop() {
        Log.i(TAG, "Stopping Saatiril LAN server")
        // #30: Broadcast SERVER_SHUTDOWN to all clients before closing
        // (matches Electron index.ts:300-305 — clients get clean disconnect message)
        try {
            broadcastLanMessage("SERVER_SHUTDOWN", null)
        } catch (e: Exception) { Log.w(TAG, "SERVER_SHUTDOWN broadcast failed: ${e.message}") }
        // BUG #13 fix: brief delay to let the broadcast flush. Thread.sleep
        // blocks the calling thread — and on Android this is often called
        // from a coroutine on Dispatchers.IO, where blocking the thread
        // prevents other coroutines (including the broadcast's own send
        // coroutine) from making progress on the same dispatcher. Using
        // runBlocking { delay(500) } suspends instead, yielding the thread
        // to other coroutines so the broadcast actually flushes within the
        // 500ms window. (runBlocking is safe here because stop() is a
        // one-shot teardown, not on the main thread.)
        try { kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(500) } } catch (_: Exception) {}

        cleanupJob?.cancel()
        cleanupJob = null
        try { engine?.stop(1000, 3000) } catch (e: Exception) { Log.w(TAG, "stop error: ${e.message}") }
        engine = null
        sessions.values.forEach { it.close() }
        sessions.clear()
        _running.value = false
        _clients.value = emptyList()
        updateStats()
    }

    /** Set / clear the session password (admin action). Broadcasts auth-requirement to all clients. */
    fun setSessionPasswordHash(hash: String?) {
        sessionPasswordHash = hash
        updateStats()
        // Notify ALL connected clients that the password requirement changed
        // (matches Electron index.ts lines 154, 165 — io.emit('auth-requirement', ...))
        val payload = JsonObject().apply { addProperty("passwordRequired", hash != null) }
        broadcastRaw(EngineIO.encodeSioEvent("auth-requirement", payload), excludeSid = null)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Broadcast helpers (used by AdminViewModel for MC actions, SYNC_DB, …)
    // ═══════════════════════════════════════════════════════════════

    /** Broadcast a `lan-message` (wrapped socket.io event) to ALL authenticated clients. */
    fun broadcastLanMessage(event: String, data: Any?) {
        val payload = JsonObject().apply {
            addProperty("event", event)
            add("data", if (data == null) com.google.gson.JsonNull.INSTANCE
                else com.google.gson.Gson().toJsonTree(data))
        }
        val packet = EngineIO.encodeSioEvent("lan-message", payload)
        broadcastRaw(packet, excludeSid = null)
    }

    /** Send a `lan-message` to ONE client (used for REQUEST_STATE / FRAME_DATA responses). */
    fun sendLanMessageToClient(sid: String, event: String, data: Any?) {
        val session = sessions[sid] ?: return
        val payload = JsonObject().apply {
            addProperty("event", event)
            add("data", if (data == null) com.google.gson.JsonNull.INSTANCE
                else com.google.gson.Gson().toJsonTree(data))
        }
        val packet = EngineIO.encodeSioEvent("lan-message", payload)
        sendToSession(session, packet)
    }

    /** Broadcast a raw socket.io event to all authenticated clients (not wrapped in lan-message). */
    fun broadcastEvent(eventName: String, vararg args: Any?) {
        val packet = EngineIO.encodeSioEvent(eventName, *args)
        broadcastRaw(packet, excludeSid = null)
    }

    private fun broadcastRaw(eioPacket: String, excludeSid: String?) {
        sessions.values.toList().forEach { s ->
            if (s.sid != excludeSid && s.authenticated) {
                sendToSession(s, eioPacket)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Polling transport (HTTP GET + POST on path "/")
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handlePollingGet(call: ApplicationCall) {
        setCorsHeaders(call)
        val transport = call.request.queryParameters["transport"]
        val eio = call.request.queryParameters["EIO"]
        val sid = call.request.queryParameters["sid"]

        if (eio != "3" || transport != "polling") {
            call.respondText("Engine.IO v3 polling expected", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }

        if (sid == null) {
            // #32: Connection limit enforcement (matches Electron index.ts:90-96)
            if (sessions.size >= MAX_CONNECTIONS) {
                Log.w(TAG, "Connection rejected — limit reached (${sessions.size}/$MAX_CONNECTIONS)")
                call.respondText("Connection limit reached", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
                return
            }
            // ── New session handshake ──
            val newSid = EngineIO.newSid()
            val session = ClientSession(newSid).apply { this.transport = "polling" }
            sessions[newSid] = session
            totalConnections.incrementAndGet()
            Log.i(TAG, "New polling session: $newSid (total=${sessions.size}/$MAX_CONNECTIONS)")
            val openPacket = EngineIO.encodeEioPacket(EngineIO.TYPE_OPEN,
                """{"sid":"$newSid","upgrades":["websocket"],"pingInterval":$PING_INTERVAL_MS,"pingTimeout":$PING_TIMEOUT_MS,"maxPayload":$MAX_PAYLOAD}""")
            updateClients()
            updateStats()
            call.respondText(openPacket, ContentType.Text.Plain)
            return
        }

        // ── Long-poll for outgoing packets ──
        val session = sessions[sid]
        if (session == null) {
            call.respondText("unknown sid", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }
        session.lastSeen = System.currentTimeMillis()
        // Only one poll at a time per session
        if (session.activePoll) {
            call.respondText(EngineIO.encodeEioPacket(EngineIO.TYPE_NOOP), ContentType.Text.Plain)
            return
        }
        session.activePoll = true
        try {
            val first = withTimeoutOrNull(POLL_HOLD_MS) { session.outgoingQueue.receive() }
            val packets = mutableListOf<String>()
            if (first != null) packets.add(first)
            // drain any additional queued packets
            while (true) {
                val extra = session.outgoingQueue.tryReceive().getOrNull() ?: break
                packets.add(extra)
            }
            if (packets.isEmpty()) {
                // nothing to send — flush with noop
                call.respondText(EngineIO.encodeEioPacket(EngineIO.TYPE_NOOP), ContentType.Text.Plain)
            } else {
                call.respondText(EngineIO.encodePollingPayload(packets), ContentType.Text.Plain)
            }
        } finally {
            session.activePoll = false
        }
    }

    private suspend fun handlePollingPost(call: ApplicationCall) {
        setCorsHeaders(call)
        val transport = call.request.queryParameters["transport"]
        val eio = call.request.queryParameters["EIO"]
        val sid = call.request.queryParameters["sid"]

        if (eio != "3" || transport != "polling") {
            call.respondText("Engine.IO v3 polling expected", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }
        if (sid == null) {
            call.respondText("missing sid", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }
        val session = sessions[sid]
        if (session == null) {
            call.respondText("unknown sid", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return
        }
        session.lastSeen = System.currentTimeMillis()
        val body = call.receiveText()  // reads the POST body (polling payload)
        val packets = EngineIO.decodePollingPayload(body)
        for (p in packets) {
            handleEnginePacket(session, p)
        }
        call.respondText("ok", ContentType.Text.Plain)
    }

    /**
     * #5: POST /api/ble-trigger — body `{"action":"PANGGIL"|"NEXT"|"RESET",
     * "studentId":"..."?}`. Forwards to [onBleTrigger] (AdminViewModel wires
     * it to the same handler used by native BLE triggers). 200 {"ok":true}
     * on success, 400 on parse errors.
     */
    private suspend fun handleBleTrigger(call: ApplicationCall) {
        setCorsHeaders(call)
        try {
            val obj = JsonParser.parseString(call.receiveText()).asJsonObject
            val action = obj.get("action")?.takeIf { !it.isJsonNull }?.asString
            if (action.isNullOrBlank()) {
                call.respondText("""{"error":"missing action"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return
            }
            val studentId = obj.get("studentId")?.takeIf { !it.isJsonNull && it.asString.isNotBlank() }?.asString
            try {
                onBleTrigger?.invoke(action, studentId)
            } catch (e: Exception) {
                Log.e(TAG, "onBleTrigger handler error for $action: ${e.message}", e)
            }
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        } catch (e: Exception) {
            Log.w(TAG, "/api/ble-trigger bad request: ${e.message}")
            call.respondText("""{"error":"bad request"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        }
    }

    private fun setCorsHeaders(call: ApplicationCall) {
        call.response.headers.append("Access-Control-Allow-Origin", "*")
        call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        call.response.headers.append("Access-Control-Allow-Headers", "Content-Type")
        call.response.headers.append("Cache-Control", "no-store, no-cache, must-revalidate")
    }

    // ═══════════════════════════════════════════════════════════════
    //  WebSocket transport (path "/")
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handleWebSocket(ws: io.ktor.server.websocket.DefaultWebSocketServerSession) {
        val call = ws.call
        val transport = call.request.queryParameters["transport"]
        val eio = call.request.queryParameters["EIO"]
        val sid = call.request.queryParameters["sid"]
        if (eio != "3" || transport != "websocket") {
            ws.close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "EIO3 websocket expected"))
            return
        }

        val session: ClientSession = if (sid == null) {
            // ── New WS-only session (no polling) ──
            // BUG FIX (audit BUG #3): enforce MAX_CONNECTIONS for WS-only sessions
            // too. Previously only the polling path checked the limit — all real
            // clients (Android SocketManager + Electron socket.io-client with
            // transports=['websocket']) take this WS-only path + bypassed the
            // limit. A single misbehaving device could open hundreds of WS
            // sessions → OOM-crash the admin phone mid-ceremony.
            if (sessions.size >= MAX_CONNECTIONS) {
                Log.w(TAG, "WS-only connection rejected — MAX_CONNECTIONS ($MAX_CONNECTIONS) reached (total=${sessions.size})")
                ws.close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Connection limit reached"))
                return
            }
            val newSid = EngineIO.newSid()
            val s = ClientSession(newSid).apply { this.transport = "websocket" }
            sessions[newSid] = s
            totalConnections.incrementAndGet()
            s.wsSession = ws
            s.upgraded = true  // WS is the active transport immediately
            startWsWriter(s, ws)
            // send open packet (first frame on this WS — nothing queued yet)
            val openPacket = EngineIO.encodeEioPacket(EngineIO.TYPE_OPEN,
                """{"sid":"$newSid","upgrades":[],"pingInterval":$PING_INTERVAL_MS,"pingTimeout":$PING_TIMEOUT_MS,"maxPayload":$MAX_PAYLOAD}""")
            ws.send(Frame.Text(openPacket))
            Log.i(TAG, "New WS-only session: $newSid (total=${sessions.size})")
            updateClients(); updateStats()
            s
        } else {
            // ── Upgrade existing polling session to WS ──
            val existing = sessions[sid]
            if (existing == null) {
                ws.close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "unknown sid"))
                return
            }
            existing.wsSession = ws
            existing.transport = "websocket"
            // Note: upgraded stays false until we receive the `5` upgrade packet.
            startWsWriter(existing, ws)
            Log.i(TAG, "WS upgrade for session: $sid")
            existing
        }

        try {
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                val raw = frame.readText()
                session.lastSeen = System.currentTimeMillis()
                handleEnginePacket(session, raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WS session ${session.sid} ended: ${e.message}")
        } finally {
            // #2: only tear the session down if this WS was (or became) the
            // ACTIVE transport (`upgraded` is true for WS-only sessions and
            // after a completed polling→WS upgrade). If this was a FAILED
            // upgrade attempt (client attached the WS but never completed the
            // probe/upgrade — `upgraded` still false), the polling session is
            // still live: just detach this WS so sends fall back to the poll
            // queue and keep the session registered.
            val wasActiveTransport = session.upgraded
            stopWsWriter(session)
            session.wsSession = null
            if (wasActiveTransport) {
                session.close()
                sessions.remove(session.sid)
                Log.i(TAG, "Session ${session.sid} removed (total=${sessions.size})")
            } else {
                session.transport = "polling"
                Log.i(TAG, "WS for session ${session.sid} closed before upgrade — polling transport kept alive")
            }
            updateClients(); updateStats()
        }
    }

    // ── Per-session serialized WS writer (#11) ─────────────────────────
    // sendToSession used to launch one coroutine per packet on Dispatchers.IO,
    // so two ordered packets (e.g. the "40" CONNECT ack followed by an event)
    // could race and arrive swapped. Each WS now gets ONE writer coroutine
    // draining a dedicated channel — strict FIFO per session, mirroring the
    // outgoingQueue pattern used for polling.

    private fun startWsWriter(session: ClientSession, ws: io.ktor.websocket.WebSocketSession) {
        // Fresh channel per WS connection (a previous failed upgrade attempt
        // leaves a closed channel behind — channels cannot be reopened).
        session.wsWriterJob?.cancel()
        val oldChannel = session.wsSendChannel
        val channel = Channel<String>(Channel.UNLIMITED)
        session.wsSendChannel = channel
        oldChannel.close()
        session.wsWriterJob = scope.launch {
            for (packet in channel) {
                try {
                    ws.send(Frame.Text(packet))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "WS send failed for ${session.sid}: ${e.message}")
                    break
                }
            }
        }
    }

    private fun stopWsWriter(session: ClientSession) {
        session.wsWriterJob?.cancel()
        session.wsWriterJob = null
        session.wsSendChannel.close()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Engine.IO + Socket.IO packet handling
    // ═══════════════════════════════════════════════════════════════

    private fun handleEnginePacket(session: ClientSession, raw: String) {
        val (eioType, eioData) = EngineIO.decodeEioPacket(raw)
        when (eioType) {
            EngineIO.TYPE_PING -> {
                // Heartbeat: client pings, server responds pong.
                if (eioData == "probe") {
                    // #1 (CRITICAL): polling→WS upgrade probe — the `3probe` reply
                    // MUST travel on the WebSocket being probed. sendToSession()
                    // would divert it to the polling queue because `upgraded` is
                    // still false at probe time, so the client would never see the
                    // pong and would abort the upgrade. Route it through the WS
                    // writer channel directly.
                    if (session.wsSession != null) {
                        session.wsSendChannel.trySend(EngineIO.encodeEioPacket(EngineIO.TYPE_PONG, "probe"))
                    } else {
                        sendToSession(session, EngineIO.encodeEioPacket(EngineIO.TYPE_PONG, "probe"))
                    }
                } else {
                    sendToSession(session, EngineIO.encodeEioPacket(EngineIO.TYPE_PONG))
                }
            }
            EngineIO.TYPE_UPGRADE -> {
                // Client completed the polling→WS upgrade. WS is now active.
                session.upgraded = true
                Log.i(TAG, "Session ${session.sid} upgraded to websocket")
                // Flush any held polling GET with a noop so it returns immediately.
                session.outgoingQueue.trySend(EngineIO.encodeEioPacket(EngineIO.TYPE_NOOP))
            }
            EngineIO.TYPE_MESSAGE -> {
                // Socket.io packet inside.
                val sio = EngineIO.decodeSioPacket(eioData)
                if (sio == null) {
                    Log.w(TAG, "Unparseable SIO packet: $eioData")
                    return
                }
                handleSocketIoPacket(session, sio)
            }
            EngineIO.TYPE_CLOSE -> {
                Log.i(TAG, "Client ${session.sid} sent close")
                session.close()
                sessions.remove(session.sid)
                updateClients(); updateStats()
            }
            else -> { /* ignore unknown */ }
        }
    }

    private fun handleSocketIoPacket(session: ClientSession, sio: EngineIO.SioPacket) {
        when (sio) {
            is EngineIO.SioPacket.Connect -> {
                // Client connected to namespace "/". Acknowledge + send auth-requirement
                // so the client knows immediately whether a password is needed.
                // (matches Electron index.ts lines 129-131)
                sendToSession(session, EngineIO.encodeSioConnect())
                val authReq = JsonObject().apply { addProperty("passwordRequired", sessionPasswordHash != null) }
                sendToSession(session, EngineIO.encodeSioEvent("auth-requirement", authReq))
                Log.i(TAG, "SIO connect from ${session.sid} (passwordRequired=${sessionPasswordHash != null})")
            }
            is EngineIO.SioPacket.Disconnect -> {
                Log.i(TAG, "SIO disconnect from ${session.sid}")
                session.close()
                sessions.remove(session.sid)
                updateClients(); updateStats()
            }
            is EngineIO.SioPacket.Event -> handleSocketIoEvent(session, sio.name, sio.args)
            is EngineIO.SioPacket.Error -> Log.w(TAG, "SIO error from ${session.sid}: ${sio.message}")
        }
    }

    private fun handleSocketIoEvent(session: ClientSession, name: String, args: JsonElement?) {
        when (name) {
            "identify" -> handleIdentify(session, args)
            "saatiril-ping" -> {
                // App-level ping: echo back as pong with same timestamp
                val ts = args?.let { if (it.isJsonPrimitive) it.asString else it.toString() } ?: System.currentTimeMillis()
                sendToSession(session, EngineIO.encodeSioEvent("saatiril-pong", ts))
            }
            "lan-message" -> handleLanMessage(session, args)
            "SET_SESSION_PASSWORD", "CLEAR_SESSION_PASSWORD" -> {
                // Only admin may set/clear. On Android the admin sets it directly
                // via setSessionPasswordHash(); ignore external requests.
                Log.i(TAG, "Ignoring $name from ${session.sid} (admin-only, direct API)")
            }
            "server-stats" -> {
                sendToSession(session, EngineIO.encodeSioEvent("server-stats", stats.value.toJsonObject()))
            }
            else -> Log.d(TAG, "Unhandled SIO event '$name' from ${session.sid}")
        }
    }

    private fun handleIdentify(session: ClientSession, args: JsonElement?) {
        val obj = args as? JsonObject ?: JsonObject()
        val role = obj.get("role")?.asString ?: "unknown"
        val channel = obj.get("channel")?.takeIf { !it.isJsonNull }?.asInt ?: 1
        val clientPwHash = obj.get("sessionPasswordHash")?.takeIf { !it.isJsonNull }?.asString

        session.role = role
        session.channel = channel

        val pwRequired = sessionPasswordHash != null
        if (pwRequired && clientPwHash != sessionPasswordHash) {
            session.authenticated = false
            session.pendingAuth = true
            sendToSession(session, EngineIO.encodeSioEvent("auth-failed",
                JsonObject().apply { addProperty("reason", "session_password_required") }))
            Log.i(TAG, "Auth FAILED for ${session.sid} (role=$role ch=$channel) — wrong/no password")
        } else {
            session.authenticated = true
            session.pendingAuth = false
            sendToSession(session, EngineIO.encodeSioEvent("auth-success",
                JsonObject().apply {
                    addProperty("role", role)
                    addProperty("channel", channel)
                }))
            Log.i(TAG, "Auth OK for ${session.sid} (role=$role ch=$channel)")
            // Notify others of the new client
            broadcastEvent("server-stats", stats.value.toJsonObject())
        }
        updateClients(); updateStats()
    }

    private fun handleLanMessage(session: ClientSession, args: JsonElement?) {
        // #3: only authenticated (identified) clients may inject lan-messages —
        // otherwise any un-identified LAN device could emit PHOTOS_SAVED /
        // MC_CALL / STUDENT_DONE into the relay.
        if (!session.authenticated) {
            Log.w(TAG, "Dropping lan-message from unauthenticated session ${session.sid}")
            return
        }
        val obj = args as? JsonObject
        val event = obj?.get("event")?.asString ?: return
        val data = obj.get("data")
        totalMessagesRelayed.incrementAndGet()

        // 1) Relay to all OTHER authenticated clients (dumb relay, matches Node server)
        val relayPacket = EngineIO.encodeSioEvent("lan-message", obj)
        sessions.values.toList().forEach { s ->
            if (s.sid != session.sid && s.authenticated) {
                sendToSession(s, relayPacket)
            }
        }

        // 2) Notify the app (AdminViewModel) so it can save photos / update DB / respond
        try {
            onLanMessage?.invoke(event, data, session.sid)
        } catch (e: Exception) {
            Log.e(TAG, "onLanMessage callback error for $event: ${e.message}", e)
        }
        updateStats()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Send helpers
    // ═══════════════════════════════════════════════════════════════

    private fun sendToSession(session: ClientSession, eioPacket: String) {
        val ws = session.wsSession
        if (ws != null && session.upgraded) {
            // #11: route through the per-session writer channel so frames are
            // written in order (a fresh coroutine per packet on Dispatchers.IO
            // gave no ordering guarantee).
            session.wsSendChannel.trySend(eioPacket)
        } else {
            // Enqueue for polling
            session.outgoingQueue.trySend(eioPacket)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Cleanup & state
    // ═══════════════════════════════════════════════════════════════

    private fun startCleanupJob() {
        cleanupJob = scope.launch {
            while (true) {
                delay(15_000) // check every 15s
                val now = System.currentTimeMillis()
                val toRemove = mutableListOf<ClientSession>()
                for (s in sessions.values) {
                    val idle = now - s.lastSeen
                    // Drop silent sessions (>90s idle)
                    if (idle > SESSION_TIMEOUT_MS) {
                        Log.i(TAG, "Reaping inactive session ${s.sid} (idle ${idle}ms)")
                        toRemove.add(s)
                    }
                    // #31: Drop unidentified sessions after 30s (matches Electron index.ts:133-142)
                    if (!s.authenticated && !s.pendingAuth && idle > IDENTIFY_TIMEOUT_MS) {
                        Log.i(TAG, "Reaping unidentified session ${s.sid} (no identify after ${idle}ms)")
                        toRemove.add(s)
                    }
                }
                toRemove.forEach { s -> s.close(); sessions.remove(s.sid) }
                if (toRemove.isNotEmpty()) { updateClients(); updateStats() }
            }
        }
    }

    private fun updateClients() {
        _clients.value = sessions.values.map { s ->
            ClientInfo(
                sid = s.sid,
                role = s.role ?: "unknown",
                channel = s.channel,
                authenticated = s.authenticated,
                transport = s.transport,
                connectedAt = s.connectedAt
            )
        }
    }

    private fun updateStats() {
        _stats.value = ServerStats(
            uptimeMs = if (_running.value) System.currentTimeMillis() - startedAt else 0,
            connectedClients = sessions.count { it.value.authenticated },
            totalConnections = totalConnections.get(),
            totalMessagesRelayed = totalMessagesRelayed.get(),
            maxConnections = 10,
            sessionPasswordActive = sessionPasswordHash != null
        )
    }

    @Volatile private var startedAt: Long = 0

    /** Detect the device's LAN IPv4 address (for QR codes / display). */
    private fun detectLanIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            var fallback: String? = null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                // BUG #14 fix: prefer WiFi / hotspot interfaces. The original
                // code only matched wlan*/ap* — on many OEM Android forks the
                // hotspot/hotspot-secondary interfaces are named differently:
                //  - swlan*   (Samsung — "softAP wlan" hybrid)
                //  - p2p*     (Wi-Fi Direct / Miracast — same radio)
                //  - softap*  (MediaTek / older Qualcomm)
                //  - wifix*   (some Xiaomi / Redmi MIUI variants)
                // On those devices the original matcher fell through to the
                // first non-loopback IPv4 (often cellular rmnet*) and the QR
                // code pointed operators at an unreachable IP. The expanded
                // list below catches all known hotspot/wifi variants.
                val name = intf.name?.lowercase() ?: ""
                val isLan = name.contains("wlan") || name.contains("ap") ||
                            name.contains("swlan") || name.contains("p2p") ||
                            name.contains("softap") || name.contains("wifix")
                for (a in intf.inetAddresses.toList()) {
                    if (!a.isLoopbackAddress && a.hostAddress?.contains(':') == false) {
                        if (isLan) return a.hostAddress
                        if (fallback == null) fallback = a.hostAddress
                    }
                }
            }
            // No wlan/ap interface found — fall back to any non-loopback IPv4.
            fallback
        } catch (e: Exception) { null }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Session model
// ═══════════════════════════════════════════════════════════════

internal class ClientSession(val sid: String) {
    @Volatile var role: String? = null
    @Volatile var channel: Int = 1
    @Volatile var authenticated: Boolean = false
    @Volatile var pendingAuth: Boolean = false
    @Volatile var transport: String = "polling"
    @Volatile var lastSeen: Long = System.currentTimeMillis()
    @Volatile var connectedAt: Long = System.currentTimeMillis()
    @Volatile var wsSession: io.ktor.websocket.WebSocketSession? = null
    @Volatile var upgraded: Boolean = false
    @Volatile var activePoll: Boolean = false
    val outgoingQueue = Channel<String>(Channel.UNLIMITED)
    // #11: dedicated serialized send path for the websocket transport — one
    // writer coroutine per session drains this channel (see startWsWriter).
    @Volatile var wsSendChannel: Channel<String> = Channel(Channel.UNLIMITED)
    @Volatile var wsWriterJob: Job? = null

    fun close() {
        try { outgoingQueue.close() } catch (_: Exception) {}
        // #11: also stop the WS writer so no frame is sent after close().
        wsWriterJob?.cancel()
        wsWriterJob = null
        try { wsSendChannel.close() } catch (_: Exception) {}
        // Note: the WebSocketSession is closed by the handleWebSocket() finally block
        // when the channel completes; we just null the reference here.
        wsSession = null
    }
}

data class ClientInfo(
    val sid: String,
    val role: String,
    val channel: Int,
    val authenticated: Boolean,
    val transport: String,
    val connectedAt: Long
)

data class ServerStats(
    val uptimeMs: Long = 0,
    val connectedClients: Int = 0,
    val totalConnections: Long = 0,
    val totalMessagesRelayed: Long = 0,
    val maxConnections: Int = 10,
    val sessionPasswordActive: Boolean = false
) {
    fun toJsonObject(): JsonObject = JsonObject().apply {
        addProperty("status", "ok")
        addProperty("uptime", uptimeMs)
        addProperty("connectedClients", connectedClients)
        addProperty("totalConnections", totalConnections)
        addProperty("totalMessagesRelayed", totalMessagesRelayed)
        addProperty("maxConnections", maxConnections)
        addProperty("sessionPasswordActive", sessionPasswordActive)
    }
}
