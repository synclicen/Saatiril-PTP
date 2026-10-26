'use client'

import { io, Socket } from 'socket.io-client'

// ─── Types ────────────────────────────────────────────────────────────────
export type LocalNetworkCallback = (data: any) => void

// ─── Module-level state ───────────────────────────────────────────────────
let socket: Socket | null = null
const listeners: Record<string, LocalNetworkCallback[]> = {}

// BUG #12 (audit-electron-side): guard against double-connect race. Tracks
// whether a connect attempt is currently in flight (between io() call and the
// first 'connect' / 'connect_error' / 'disconnect' event). If a second
// connectSocket() call happens while this is true, we return the existing
// in-flight socket instead of discarding it mid-handshake.
let isConnecting: boolean = false

// BUG #13 (audit-electron-side): module-level handle for the manual reconnect
// interval started by the 'reconnect_failed' handler. Storing it here lets us
// clear a previous interval before starting a new one — prevents interval
// stacking when reconnect_failed fires repeatedly on flaky WiFi.
let manualRetryInterval: ReturnType<typeof setInterval> | null = null

// ─── Session password (hash) ───────────────────────────────────────────────
// Stored locally so we can re-identify on reconnect
let currentSessionPasswordHash: string | null = null

// ─── Auth state ─────────────────────────────────────────────────────────────
let isAuthenticated: boolean = false
let authRequiredByServer: boolean = false

// ─── SHA-256 hash helper (browser native with fallback) ────────────────────
// crypto.subtle is not available in insecure contexts (HTTP non-localhost).
// LAN clients (MC/Operator) connect via HTTP LAN IP, so we need a fallback.
async function sha256(text: string): Promise<string> {
  const encoder = new TextEncoder()
  const data = encoder.encode(text)

  // Try native crypto.subtle first (available in secure contexts & modern browsers)
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    try {
      const hashBuffer = await crypto.subtle.digest('SHA-256', data)
      const hashArray = Array.from(new Uint8Array(hashBuffer))
      return hashArray.map(b => b.toString(16).padStart(2, '0')).join('')
    } catch {
      // Fall through to pure JS implementation
    }
  }

  // Pure JavaScript SHA-256 fallback for insecure HTTP contexts
  // This ensures LAN clients can hash passwords even without crypto.subtle
  return sha256Fallback(data)
}

/**
 * Pure JavaScript SHA-256 implementation for insecure contexts.
 * Used when crypto.subtle is not available (e.g., HTTP LAN connections).
 */
function sha256Fallback(data: Uint8Array): string {
  // SHA-256 constants
  const K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ])

  // Pre-processing: adding padding bits
  const msgLen = data.length
  const bitLen = msgLen * 8
  // Calculate padded length: message + 1 (0x80) + padding zeros + 8 bytes for length
  // Must be multiple of 64 bytes (512 bits)
  let paddedLen = msgLen + 1
  while (paddedLen % 64 !== 56) paddedLen++
  paddedLen += 8

  const padded = new Uint8Array(paddedLen)
  padded.set(data)
  padded[msgLen] = 0x80
  // Append original length in bits as 64-bit big-endian
  const view = new DataView(padded.buffer)
  view.setUint32(paddedLen - 8, 0, false) // high 32 bits
  view.setUint32(paddedLen - 4, bitLen, false) // low 32 bits

  // Initialize hash values
  let h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a
  let h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19

  // Process each 64-byte chunk
  for (let offset = 0; offset < paddedLen; offset += 64) {
    const w = new Uint32Array(64)
    for (let i = 0; i < 16; i++) {
      w[i] = view.getUint32(offset + i * 4, false)
    }
    for (let i = 16; i < 64; i++) {
      const s0 = rotR(w[i - 15], 7) ^ rotR(w[i - 15], 18) ^ (w[i - 15] >>> 3)
      const s1 = rotR(w[i - 2], 17) ^ rotR(w[i - 2], 19) ^ (w[i - 2] >>> 10)
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0
    }

    let a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, hh = h7
    for (let i = 0; i < 64; i++) {
      const S1 = rotR(e, 6) ^ rotR(e, 11) ^ rotR(e, 25)
      const ch = (e & f) ^ (~e & g)
      const temp1 = (hh + S1 + ch + K[i] + w[i]) | 0
      const S0 = rotR(a, 2) ^ rotR(a, 13) ^ rotR(a, 22)
      const maj = (a & b) ^ (a & c) ^ (b & c)
      const temp2 = (S0 + maj) | 0
      hh = g; g = f; f = e; e = (d + temp1) | 0
      d = c; c = b; b = a; a = (temp1 + temp2) | 0
    }

    h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0
    h4 = (h4 + e) | 0; h5 = (h5 + f) | 0; h6 = (h6 + g) | 0; h7 = (h7 + hh) | 0
  }

  // Produce the final hash value (big-endian)
  const hashArray = new Uint8Array(32)
  const hashView = new DataView(hashArray.buffer)
  hashView.setUint32(0, h0, false); hashView.setUint32(4, h1, false)
  hashView.setUint32(8, h2, false); hashView.setUint32(12, h3, false)
  hashView.setUint32(16, h4, false); hashView.setUint32(20, h5, false)
  hashView.setUint32(24, h6, false); hashView.setUint32(28, h7, false)

  return Array.from(hashArray).map(b => b.toString(16).padStart(2, '0')).join('')
}

function rotR(x: number, n: number): number {
  return (x >>> n) | (x << (32 - n))
}

// ─── Connection health tracking ───────────────────────────────────────────
let connectTime: number | null = null
let lastEventTime: number | null = null
let reconnectCount = 0
let isReconnecting = false

// ─── Latency tracking ─────────────────────────────────────────────────────
let currentLatencyMs: number = -1   // -1 = unknown
let latencyHistory: number[] = []   // Last 20 ping samples
const MAX_LATENCY_HISTORY = 20
let pingIntervalId: ReturnType<typeof setInterval> | null = null
let pendingPingTimestamp: number | null = null

export interface ConnectionHealth {
  connected: boolean
  connectTime: number | null
  lastEventTime: number | null
  reconnectCount: number
  socketId: string | null
  uptime: number // seconds since connect
  latencyMs: number  // -1 = unknown
  avgLatencyMs: number // -1 = unknown
  networkQuality: 'excellent' | 'good' | 'fair' | 'poor' | 'unknown'
  queuedEvents: number  // events waiting in offline queue
}

/**
 * Determine network quality based on average latency.
 * For LAN: excellent <5ms, good <15ms, fair <30ms, poor >=30ms
 */
function classifyNetworkQuality(avgLatency: number): ConnectionHealth['networkQuality'] {
  if (avgLatency < 0) return 'unknown'
  if (avgLatency < 5) return 'excellent'
  if (avgLatency < 15) return 'good'
  if (avgLatency < 30) return 'fair'
  return 'poor'
}

export function getConnectionHealth(): ConnectionHealth {
  const avgLatency = latencyHistory.length > 0
    ? latencyHistory.reduce((a, b) => a + b, 0) / latencyHistory.length
    : -1

  return {
    connected: socket?.connected ?? false,
    connectTime,
    lastEventTime,
    reconnectCount,
    socketId: socket?.id ?? null,
    uptime: connectTime ? Math.round((Date.now() - connectTime) / 1000) : 0,
    latencyMs: currentLatencyMs,
    avgLatencyMs: Math.round(avgLatency),
    networkQuality: classifyNetworkQuality(avgLatency),
    queuedEvents: eventQueue.length,
  }
}

/** Subscribe to latency updates — called every 5s after ping measurement */
type LatencyCallback = (health: ConnectionHealth) => void
const latencyListeners: LatencyCallback[] = []

export function onLatencyUpdate(cb: LatencyCallback) {
  latencyListeners.push(cb)
  return () => {
    const idx = latencyListeners.indexOf(cb)
    if (idx !== -1) latencyListeners.splice(idx, 1)
  }
}

function notifyLatencyListeners() {
  const health = getConnectionHealth()
  for (const cb of latencyListeners) {
    try { cb(health) } catch {}
  }
}

// ─── Ping/pong measurement ────────────────────────────────────────────────
function startPingMeasurement() {
  stopPingMeasurement()
  // Measure latency every 5 seconds
  pingIntervalId = setInterval(() => {
    if (!socket?.connected) return
    pendingPingTimestamp = Date.now()
    socket.emit('saatiril-ping', pendingPingTimestamp)
  }, 5000)
  // Do first ping immediately
  if (socket?.connected) {
    pendingPingTimestamp = Date.now()
    socket.emit('saatiril-ping', pendingPingTimestamp)
  }
}

function stopPingMeasurement() {
  if (pingIntervalId) {
    clearInterval(pingIntervalId)
    pingIntervalId = null
  }
  pendingPingTimestamp = null
}

function handlePong(timestamp: number) {
  if (pendingPingTimestamp && pendingPingTimestamp === timestamp) {
    const latency = Date.now() - timestamp
    currentLatencyMs = latency
    latencyHistory.push(latency)
    if (latencyHistory.length > MAX_LATENCY_HISTORY) {
      latencyHistory.shift()
    }
    pendingPingTimestamp = null
    notifyLatencyListeners()
  }
}

/**
 * Get the Socket.io server URL.
 *
 * Connection modes:
 *
 * 1. Electron desktop (admin):
 *    - Read socketPort from URL query parameter (passed by Electron main process)
 *    - Connect directly to localhost:PORT (always HTTP)
 *
 * 2. LAN device (MC or Operator):
 *    - socketPort is the HTTP Socket.io port (3003)
 *    - Connect via http://hostname:socketPort
 *    - All connections use HTTP (no HTTPS server)
 *    - Operator needs Chrome Flag for camera access
 *
 * 3. Web/sandbox mode (development):
 *    - Use XTransformPort=3003 for Caddy gateway routing
 */
function getSocketUrl(): string {
  if (typeof window === 'undefined') return '/'

  // Check if running in Electron
  const isElectron = !!(window as any).saatirilAPI?.isElectron
  const params = new URLSearchParams(window.location.search)
  const socketPortParam = params.get('socketPort')

  if (isElectron) {
    // Electron app: connect to admin's socket.io server
    // - Admin (portable.exe): no host param → connect to localhost:3003
    // - MC/Operator Electron: host param in URL → connect to ADMIN_IP:3003
    const port = socketPortParam || '3003'
    const hostParam = params.get('host')
    if (hostParam) {
      return `http://${hostParam}:${port}`
    }
    return `http://localhost:${port}`
  }

  // LAN device: always use HTTP to connect to Socket.io server
  if (socketPortParam) {
    const hostname = window.location.hostname
    return `http://${hostname}:${socketPortParam}`
  }

  // Web/sandbox mode: use Caddy gateway with XTransformPort
  // The socket.io client needs the query param in its transport requests
  // so Caddy can route them to port 3003. We pass it via the `query` option
  // in connectSocket() and use the current origin as the base URL.
  return window.location.origin
}

export function getSocket(): Socket | null {
  return socket
}

// ─── Critical event queue ─────────────────────────────────────────────────
// Events emitted while disconnected are queued and sent on reconnect
interface QueuedEvent {
  event: string
  data: any
  timestamp: number
  retries: number
}

const eventQueue: QueuedEvent[] = []
// NOTE: This queue is ONLY for relay reliability (re-emitting events after a
// WiFi drop/reconnect). It is NOT the source of truth for photos — disk saves
// (admin's handlePhotosSaved + operator's local redundancy) are the source of
// truth and are UNCAPPED.
//
// BUG #6 (audit-electron-side): raised from 2000 → 10000. With dual-photoshoot
// (2 operators) emitting ~2 critical events per capture, 2000 only covered
// ~1000 captures (~16 min at 1 capture/sec) of WiFi outage — older PHOTOS_SAVED
// events were dropped from the queue. 10000 covers ~5000 captures (~80 min)
// which is far beyond any realistic WiFi outage during a ceremony. Worst-case
// memory at 10000 × ~50KB/event ≈ 500MB — acceptable for a ceremony laptop.
const MAX_QUEUE_SIZE = 10000
const MAX_RETRIES = 5     // More retries for unreliable WiFi
const CRITICAL_EVENTS = new Set(['PHOTOS_SAVED', 'MC_CALL', 'SYNC_DB', 'STUDENT_DONE', 'STUDENT_RESET'])

// ─── Pending session password ──────────────────────────────────────────────
// When setSessionPassword() is called while socket is not connected,
// we store the hash here and send it on the next connection BEFORE
// flushing the event queue. This ensures the server has the password
// set before any SYNC_DB events are relayed to other clients.
let pendingSessionPasswordHash: string | null = null

function queueEvent(event: string, data: any) {
  // Only queue critical events
  if (!CRITICAL_EVENTS.has(event)) return
  if (eventQueue.length >= MAX_QUEUE_SIZE) {
    // Remove oldest non-critical event
    const oldestIdx = eventQueue.findIndex(e => !CRITICAL_EVENTS.has(e.event))
    if (oldestIdx !== -1) {
      eventQueue.splice(oldestIdx, 1)
    } else {
      // All are critical — remove oldest
      eventQueue.shift()
    }
  }
  eventQueue.push({ event, data, timestamp: Date.now(), retries: 0 })
  console.log(`[SAATIRIL] Queued critical event: ${event} (queue: ${eventQueue.length})`)
}

function flushEventQueue() {
  if (!socket?.connected || eventQueue.length === 0) return

  // BUG #6 (audit-electron-side): don't clear the queue BEFORE emitting.
  // Previously the code did `const toSend = [...eventQueue]; eventQueue.length = 0`
  // then emitted each item. If the socket disconnected mid-flush, the in-flight
  // (un-acked) items were LOST — not re-queued. Now we emit each item, then
  // only drop it from the queue after the emit call returns. Items that we
  // couldn't emit (socket disconnected mid-flush) are kept in the queue for
  // the next flush. Items that exceeded MAX_RETRIES are dropped. (Server-side
  // acks would be even better, but the lan-message handler doesn't ack —
  // fire-and-forget is the existing contract; this fix at least plugs the
  // mid-flush disconnect hole.)
  const remaining: QueuedEvent[] = []
  for (const item of eventQueue) {
    if (item.retries >= MAX_RETRIES) {
      console.warn(`[SAATIRIL] Dropping event after ${MAX_RETRIES} retries: ${item.event}`)
      continue
    }
    if (!socket?.connected) {
      // Socket disconnected mid-flush — keep the item for the next flush.
      remaining.push(item)
      continue
    }
    item.retries++
    socket.emit('lan-message', { event: item.event, data: item.data })
    console.log(`[SAATIRIL] Flushed queued event: ${item.event} (attempt ${item.retries})`)
    // Item was emitted successfully — don't re-queue.
  }
  // Rebuild the queue with anything we couldn't emit (preserves order).
  // Note: new items pushed by emitLocal() during iteration land AFTER these
  // remaining items, which is fine — they're newer.
  eventQueue.length = 0
  for (const r of remaining) eventQueue.push(r)
}

// ─── Connect Socket ───────────────────────────────────────────────────────
export function connectSocket(): Socket {
  // BUG #12 (audit-electron-side): guard against double-connect race. If a
  // previous connect attempt is still in flight (isConnecting === true), OR
  // the socket is already connected, return the existing socket instead of
  // discarding it mid-handshake. Previously the check was only
  // `if (socket?.connected) return socket` — a still-CONNECTING socket would
  // be removeAllListeners()'d + disconnect()'d, losing any identify/auth
  // events already sent on it. The second socket then started fresh, causing
  // a brief auth glitch + wasted work.
  if (socket?.connected) return socket
  if (socket && isConnecting) return socket

  // Clean up existing disconnected socket before creating a new one
  if (socket) {
    socket.removeAllListeners()
    socket.disconnect()
    socket = null
  }
  // Clear any leftover manual-retry interval from a prior session.
  if (manualRetryInterval) {
    clearInterval(manualRetryInterval)
    manualRetryInterval = null
  }
  isConnecting = true

  const socketUrl = getSocketUrl()
  const isElectron = !!(window as any).saatirilAPI?.isElectron

  // Use path '/' — matches Electron server config and APK ktor server
  const socketOptions = {
    path: '/',
    transports: ['websocket', 'polling'],
    forceNew: true,
    reconnection: true,
    reconnectionAttempts: Infinity,    // Never give up during ceremony!
    reconnectionDelay: 500,            // Start at 500ms (faster for crowded WiFi)
    reconnectionDelayMax: 5000,        // Max 5s between retries (faster recovery)
    timeout: 10000,                    // 10s connection timeout (faster fail detection)
  }

  // For sandbox/web mode, add XTransformPort as a query parameter
  // so Caddy gateway can route requests to the correct port.
  const isSandboxMode = !isElectron && !new URLSearchParams(window.location.search).get('socketPort')
  const finalOptions = isSandboxMode
    ? { ...socketOptions, query: { ...socketOptions.query, XTransformPort: '3003' } }
    : socketOptions

  console.log('[SAATIRIL] Connecting to Socket.io server...', socketUrl, isSandboxMode ? '(sandbox mode with XTransformPort=3003)' : '')
  socket = io(socketUrl, finalOptions)

  // ── Connection lifecycle ──────────────────────────────────────────────
  socket.on('connect', () => {
    isConnecting = false
    connectTime = Date.now()
    isReconnecting = false
    console.log('[SAATIRIL] Socket connected:', socket?.id, `(reconnects: ${reconnectCount})`)

    // Reset auth state on new connection
    isAuthenticated = false

    // Identify ourselves to the server (with session password hash if available)
    // IMPORTANT: Default role is 'admin' when no URL param is present.
    // The admin accesses the app directly (without ?role=... URL param),
    // while MC/Operator always have ?role=mc or ?role=operator in their URLs.
    // Using 'unknown' as default would break the server's role-based access control:
    // - Server blocks lan-message relay from 'unknown' role clients
    // - Server rejects SET_SESSION_PASSWORD from non-'admin' role clients
    const identifyPayload: Record<string, any> = {
      role: typeof window !== 'undefined'
        ? new URLSearchParams(window.location.search).get('role') || 'admin'
        : 'admin',
      channel: typeof window !== 'undefined'
        ? parseInt(new URLSearchParams(window.location.search).get('channel') || '1', 10)
        : 1,
    }

    // Include session password hash for non-admin clients
    const role = identifyPayload.role
    if (role !== 'admin' && currentSessionPasswordHash) {
      identifyPayload.sessionPasswordHash = currentSessionPasswordHash
    }

    console.log(`[SAATIRIL] Identifying as role=${role}, channel=${identifyPayload.channel}, hasPasswordHash=${!!identifyPayload.sessionPasswordHash}`)

    socket?.emit('identify', identifyPayload)

    // Send pending session password BEFORE flushing event queue.
    // CRITICAL: This ensures the server has the password set before
    // any SYNC_DB events are relayed to other clients. Without this,
    // MC/Operator clients could receive project data without authenticating.
    if (pendingSessionPasswordHash && role === 'admin') {
      socket?.emit('SET_SESSION_PASSWORD', { passwordHash: pendingSessionPasswordHash })
      currentSessionPasswordHash = pendingSessionPasswordHash
      console.log('[SAATIRIL] Sent pending session password hash to server on connect')
      pendingSessionPasswordHash = null
    }

    // CRITICAL FIX: Flush event queue for admin role or when no password required.
    // For admin: always flush (admin is the source of truth).
    // For non-admin: only flush if no password is required; otherwise,
    // flushing is handled by the auth-success handler after authentication.
    if (role === 'admin' || !authRequiredByServer) {
      flushEventQueue()
    }

    // Start ping measurement for latency tracking
    startPingMeasurement()
  })

  socket.on('disconnect', (reason) => {
    isConnecting = false
    console.warn('[SAATIRIL] Socket disconnected. Reason:', reason)
    stopPingMeasurement()
    currentLatencyMs = -1
    notifyLatencyListeners()

    // If server initiated disconnect, we need manual reconnect
    if (reason === 'io server disconnect') {
      // Server kicked us — reconnect after delay
      setTimeout(() => {
        console.log('[SAATIRIL] Attempting manual reconnect...')
        socket?.connect()
      }, 2000)
    }
  })

  socket.on('connect_error', (error) => {
    isConnecting = false
    if (!isReconnecting) {
      isReconnecting = true
      reconnectCount++
    }
    console.warn('[SAATIRIL] Connection error (attempt #' + reconnectCount + '):', error.message)
  })

  socket.on('reconnect', (attemptNumber) => {
    console.log('[SAATIRIL] Reconnected after', attemptNumber, 'attempts')
    isReconnecting = false
  })

  socket.on('reconnect_error', (error) => {
    console.warn('[SAATIRIL] Reconnection error:', error.message)
  })

  socket.on('reconnect_failed', () => {
    console.error('[SAATIRIL] Reconnection failed — will keep trying manually')
    // BUG #13 (audit-electron-side): clear any PREVIOUS manual-retry interval
    // before starting a new one. On flaky WiFi, reconnect_failed can fire
    // repeatedly (each one starts a new 5s interval) → multiple intervals
    // stacking up, each calling socket.connect() on the same socket. With
    // reconnectionAttempts: Infinity (set above in socketOptions) socket.io
    // already retries forever, so this manual retry is belt-and-suspenders;
    // we keep it but ensure only ONE is ever running at a time.
    if (manualRetryInterval) {
      clearInterval(manualRetryInterval)
      manualRetryInterval = null
    }
    manualRetryInterval = setInterval(() => {
      if (socket?.connected) {
        if (manualRetryInterval) {
          clearInterval(manualRetryInterval)
          manualRetryInterval = null
        }
        return
      }
      console.log('[SAATIRIL] Manual reconnection attempt...')
      socket?.connect()
    }, 5000)
  })

  // ── Auth event handlers ────────────────────────────────────────────────
  socket.on('auth-requirement', (data: { passwordRequired: boolean }) => {
    authRequiredByServer = data.passwordRequired
    console.log(`[SAATIRIL] Server auth requirement: passwordRequired=${data.passwordRequired}`)
    // Notify listeners about auth requirement
    if (listeners['auth-requirement']) {
      listeners['auth-requirement'].forEach(cb => {
        try { cb(data) } catch (err) {
          console.error('[SAATIRIL] Error in auth-requirement listener:', err)
        }
      })
    }
  })

  socket.on('auth-success', (data: { role: string; channel: number }) => {
    isAuthenticated = true
    console.log(`[SAATIRIL] Auth success: role=${data.role}, channel=${data.channel}`)
    // Flush queued events now that we're authenticated
    flushEventQueue()
    // Notify listeners about auth success
    if (listeners['auth-success']) {
      listeners['auth-success'].forEach(cb => {
        try { cb(data) } catch (err) {
          console.error('[SAATIRIL] Error in auth-success listener:', err)
        }
      })
    }
  })

  socket.on('auth-failed', (data: { reason: string }) => {
    isAuthenticated = false
    console.warn(`[SAATIRIL] Auth failed: ${data.reason}`)
    // Notify listeners about auth failure
    if (listeners['auth-failed']) {
      listeners['auth-failed'].forEach(cb => {
        try { cb(data) } catch (err) {
          console.error('[SAATIRIL] Error in auth-failed listener:', err)
        }
      })
    }
  })

  // ── Ping/pong handler for latency measurement ────────────────────────
  socket.on('saatiril-pong', handlePong)

  // ── Server shutdown notification ──────────────────────────────────────
  socket.on('lan-message', (payload: { event: string; data: any }) => {
    const { event: evt, data } = payload
    lastEventTime = Date.now()

    if (evt === 'SERVER_SHUTDOWN') {
      console.warn('[SAATIRIL] Server is shutting down:', data)
      return
    }

    if (listeners[evt]) {
      listeners[evt].forEach(cb => {
        try {
          cb(data)
        } catch (err) {
          console.error(`[SAATIRIL] Error in listener for ${evt}:`, err)
        }
      })
    }
  })

  return socket
}

// ─── Emit with queue ──────────────────────────────────────────────────────
export function emitLocal(event: string, data: any) {
  if (socket?.connected) {
    socket.emit('lan-message', { event, data })
  } else if (CRITICAL_EVENTS.has(event)) {
    // Queue critical events for later delivery
    queueEvent(event, data)
  } else {
    console.warn(`[SAATIRIL] Event "${event}" lost — socket not connected and not critical`)
  }

  // Always trigger local listeners immediately (even if disconnected)
  if (listeners[event]) {
    listeners[event].forEach(cb => {
      try {
        cb(data)
      } catch (err) {
        console.error(`[SAATIRIL] Error in local listener for ${event}:`, err)
      }
    })
  }
}

// ─── Listener management ──────────────────────────────────────────────────
export function onLocal(event: string, callback: LocalNetworkCallback) {
  if (!listeners[event]) listeners[event] = []
  listeners[event].push(callback)
  return () => {
    listeners[event] = listeners[event].filter(cb => cb !== callback)
  }
}

export function offLocal(event: string, callback?: LocalNetworkCallback) {
  if (!listeners[event]) return
  if (callback) {
    listeners[event] = listeners[event].filter(cb => cb !== callback)
  } else {
    delete listeners[event]
  }
}

// ─── Session Password Management ──────────────────────────────────────────

/**
 * Admin: set the session password on the server.
 * Sends the SHA-256 hash so the password is never transmitted in plaintext.
 * Also saves the hash to a separate localStorage key (keyed by projectId)
 * so the admin can re-send it on reconnection.
 */
export async function setSessionPassword(password: string, projectId?: string): Promise<void> {
  const hash = await sha256(password)
  currentSessionPasswordHash = hash

  if (socket?.connected) {
    socket.emit('SET_SESSION_PASSWORD', { passwordHash: hash })
    console.log('[SAATIRIL] Session password set on server')
  } else {
    // Socket not connected yet — store the hash as pending so it's sent
    // on the next connection BEFORE any queued events are flushed.
    // This is critical for the admin flow: project-setup.tsx calls
    // setSessionPassword() before MainApp mounts and connects the socket.
    pendingSessionPasswordHash = hash
    console.log('[SAATIRIL] Socket not connected — session password hash queued as pending')
  }

  // Save hash to separate localStorage for reconnection
  if (projectId && typeof window !== 'undefined') {
    try {
      localStorage.setItem(`saatiril_pwdhash_${projectId}`, hash)
    } catch (e) {
      console.error('[SAATIRIL] Failed to save password hash to storage:', e)
    }
  }
}

/**
 * Admin: re-send a previously stored password hash to the server.
 * Used on reconnection when the password was already set in a previous session.
 */
export function resendSessionPasswordHash(hash: string): void {
  if (!socket?.connected) return
  currentSessionPasswordHash = hash
  socket.emit('SET_SESSION_PASSWORD', { passwordHash: hash })
  console.log('[SAATIRIL] Session password hash re-sent to server')
}

/**
 * Admin: clear the session password on the server.
 */
export function clearSessionPassword(): void {
  if (!socket?.connected) return
  currentSessionPasswordHash = null
  socket.emit('CLEAR_SESSION_PASSWORD')
  console.log('[SAATIRIL] Session password cleared on server')
}

/**
 * Non-admin: re-identify with a session password.
 * Called after the user enters the password in the prompt.
 * Hashes the password and sends it to the server for validation.
 */
export async function reidentifyWithPassword(password: string): Promise<{ success: boolean; error?: string }> {
  if (!socket?.connected) {
    console.error('[SAATIRIL] Cannot re-identify: socket not connected')
    return { success: false, error: 'Socket tidak terhubung' }
  }
  try {
    const hash = await sha256(password)
    currentSessionPasswordHash = hash

    // Same default role logic as connect handler: 'admin' when no URL param
    const role = typeof window !== 'undefined'
      ? new URLSearchParams(window.location.search).get('role') || 'admin'
      : 'admin'
    const channel = typeof window !== 'undefined'
      ? parseInt(new URLSearchParams(window.location.search).get('channel') || '1', 10)
      : 1

    socket.emit('identify', {
      role,
      channel,
      sessionPasswordHash: hash,
    })
    console.log(`[SAATIRIL] Re-identifying with session password (role: ${role})`)
    return { success: true }
  } catch (err) {
    console.error('[SAATIRIL] Failed to re-identify with password:', err)
    return { success: false, error: 'Gagal memproses password' }
  }
}

/**
 * Check if the current connection is authenticated.
 */
export function isSocketAuthenticated(): boolean {
  return isAuthenticated
}

/**
 * Get the current socket connection state snapshot.
 * Used by components on mount to sync their React state with the
 * actual socket module state (which may have been updated by events
 * that fired before the component's handlers were registered).
 */
export function getSocketAuthState(): {
  connected: boolean
  authenticated: boolean
  passwordRequired: boolean
  passwordHash: string | null
} {
  return {
    connected: socket?.connected ?? false,
    authenticated: isAuthenticated,
    passwordRequired: authRequiredByServer,
    passwordHash: currentSessionPasswordHash,
  }
}

/**
 * Check if the server requires a session password.
 */
export function isServerPasswordRequired(): boolean {
  return authRequiredByServer
}
