/**
 * SAATIRIL — Electron Main Process
 *
 * This is the entry point for the Electron desktop application.
 * It starts:
 * 1. Next.js static export server (HTTP on dynamic port)
 * 2. Socket.io relay server (on dynamic port)
 * 3. Splash loading screen (immediate visual feedback)
 * 4. Electron BrowserWindow loading the Next.js app
 *
 * IPC handlers exposed to the renderer:
 * - selectFolder: Open native folder picker dialog
 * - createFolder: Create a directory on disk
 * - savePhoto: Save base64 photo data to disk
 * - getLanInfo: Get LAN IP addresses and ports
 * - getLicenseStatus: Check current license status
 * - activateLicense: Activate with an activation code
 * - getMachineId: Get the Machine ID for this computer
 */

import { app, BrowserWindow, ipcMain, dialog, shell, powerSaveBlocker, screen } from 'electron'
import * as path from 'path'
import * as https from 'https'
import * as fs from 'fs'
import * as os from 'os'
import { createServer } from 'http'
import { Server as SocketIOServer } from 'socket.io'
import { checkLicenseStatus, activateLicense, getMachineId, getDisplayMachineId, generateLicenseCode } from './license'

// ─── Configuration ─────────────────────────────────────────────────────────
const DEFAULT_HTTP_PORT = 3000
const DEFAULT_SOCKET_PORT = 3003
const MAX_HTTP_BUFFER = 20e6 // 20MB for large photo payloads
const isDev = process.env.SAATIRIL_DEV === '1'

// ─── GitHub Releases (for APK/Portable download) ───────────────────────────
const GITHUB_REPO = 'synclicen/Saatiril-Fullset'
const RELEASE_TAG = 'latest'

interface ReleaseAssetInfo {
  url: string
  browserUrl: string
  size: number
  sizeMB: string
  lastModified: string
  assetName: string
}

interface CachedRelease {
  apk: ReleaseAssetInfo | null
  portable: ReleaseAssetInfo | null
}

let cachedRelease: CachedRelease | null = null
let cacheTime = 0
const CACHE_TTL = 5 * 60 * 1000 // 5 minutes

async function getGitHubToken(): Promise<string> {
  // Try env variable first
  if (process.env.GITHUB_TOKEN) return process.env.GITHUB_TOKEN

  // Try to get token from git remote in the project directory
  try {
    const { execSync } = await import('child_process')
    // Try multiple possible project directories
    const possibleDirs = [
      path.join(__dirname, '..'),
      process.cwd(),
    ]
    for (const dir of possibleDirs) {
      try {
        const remoteUrl = execSync('git remote get-url origin', {
          encoding: 'utf-8',
          cwd: dir,
        }).trim()
        const match = remoteUrl.match(/:\/\/[^:]*:([^@]*)@/)
        if (match?.[1]) return match[1]
      } catch {
        // No git repo in this directory, try next
      }
    }
  } catch {
    // child_process not available
  }

  return ''
}

async function fetchLatestReleaseInfo(): Promise<CachedRelease> {
  const now = Date.now()
  if (cachedRelease && (now - cacheTime) < CACHE_TTL) {
    return cachedRelease
  }

  try {
    const token = await getGitHubToken()
    const headers: Record<string, string> = {
      Accept: 'application/vnd.github+json',
      'User-Agent': 'Saatiril-Electron-App',
    }
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }

    const res = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/tags/${RELEASE_TAG}`, { headers })

    if (!res.ok) {
      throw new Error(`GitHub API returned ${res.status}`)
    }

    const release = await res.json() as { assets?: Array<{ name: string; url: string; browser_download_url: string; size: number; updated_at: string }>; published_at?: string }
    const assets = release.assets || []

    const apkAsset = assets.find((a: { name: string }) => a.name.endsWith('.apk'))
    const portableAsset = assets.find((a: { name: string }) =>
      a.name.endsWith('-portable.exe') || a.name === 'saatiril-portable.exe'
    )

    const toAssetInfo = (a: { url: string; browser_download_url: string; size: number; updated_at: string; name: string }): ReleaseAssetInfo => ({
      url: a.url,
      browserUrl: a.browser_download_url,
      size: a.size,
      sizeMB: (a.size / (1024 * 1024)).toFixed(1),
      lastModified: a.updated_at || release.published_at || '',
      assetName: a.name,
    })

    cachedRelease = {
      apk: apkAsset ? toAssetInfo(apkAsset) : null,
      portable: portableAsset ? toAssetInfo(portableAsset) : null,
    }
    cacheTime = now

    console.log(`[SAATIRIL] GitHub Releases: APK=${cachedRelease.apk ? cachedRelease.apk.assetName : 'none'}, Portable=${cachedRelease.portable ? cachedRelease.portable.assetName : 'none'}`)
  } catch (err: any) {
    console.error('[SAATIRIL] Failed to fetch GitHub Releases:', err.message)
    // Return empty cache if fetch fails
    if (!cachedRelease) {
      cachedRelease = { apk: null, portable: null }
    }
  }

  return cachedRelease
}

// Actual ports (may differ from defaults if ports are in use)
let httpPort = DEFAULT_HTTP_PORT
let socketPort = DEFAULT_SOCKET_PORT

// ─── API Route Handlers (for Electron portable build) ─────────────────────
// In the Electron portable version, the app is served as a static export.
// Next.js API routes don't exist in the static export, so we must handle
// them here in the Electron main process's HTTP server.

/**
 * Handle /api/apk-download requests
 * GET  → Returns release info (APK + Portable availability, size, etc.)
 * POST → Proxies the binary download from GitHub Releases
 */
function handleApkDownloadApi(
  req: import('http').IncomingMessage,
  res: import('http').ServerResponse,
  urlQuery: string,
) {
  if (req.method === 'GET') {
    // Return release info
    fetchLatestReleaseInfo()
      .then((info) => {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({
          apk: info.apk ? {
            available: true,
            sizeMB: info.apk.sizeMB,
            assetName: info.apk.assetName,
            lastModified: info.apk.lastModified,
          } : {
            available: false,
            error: 'No APK asset found in latest release',
          },
          portable: info.portable ? {
            available: true,
            sizeMB: info.portable.sizeMB,
            assetName: info.portable.assetName,
            lastModified: info.portable.lastModified,
          } : {
            available: false,
            error: 'No Portable asset found in latest release',
          },
        }))
      })
      .catch((err: any) => {
        const message = err instanceof Error ? err.message : 'Unknown error'
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({
          apk: { available: false, error: message },
          portable: { available: false, error: message },
        }))
      })
    return
  }

  if (req.method === 'POST') {
    // Proxy binary download from GitHub Releases
    let body = ''
    req.on('data', (chunk: Buffer) => { body += chunk.toString() })
    req.on('end', () => {
      let type = 'apk'
      try {
        const parsed = JSON.parse(body)
        type = parsed.type || 'apk'
      } catch { /* default to apk */ }

      // Also check URL query param for type (e.g. ?type=portable)
      if (type === 'apk' && urlQuery.includes('type=portable')) {
        type = 'portable'
      }

      fetchLatestReleaseInfo()
        .then(async (info) => {
          const assetInfo = type === 'portable' ? info.portable : info.apk
          if (!assetInfo) {
            res.writeHead(404, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({
              error: `${type === 'portable' ? 'Portable' : 'APK'} not available in latest release`,
            }))
            return
          }

          const token = await getGitHubToken()
          const headers: Record<string, string> = {
            Accept: 'application/octet-stream',
            'User-Agent': 'Saatiril-Electron-App',
          }
          if (token) {
            headers.Authorization = `Bearer ${token}`
          }

          const response = await fetch(assetInfo.url, { headers })

          if (!response.ok) {
            throw new Error(`GitHub download returned ${response.status}`)
          }

          const buffer = await response.arrayBuffer()

          const contentType = type === 'portable'
            ? 'application/x-msdownload'
            : 'application/vnd.android.package-archive'
          const filename = type === 'portable'
            ? 'saatiril-portable.exe'
            : 'saatiril-operator.apk'

          res.writeHead(200, {
            'Content-Type': contentType,
            'Content-Disposition': `attachment; filename="${filename}"`,
            'Content-Length': buffer.byteLength.toString(),
          })
          res.end(Buffer.from(buffer))
        })
        .catch((err: any) => {
          const message = err instanceof Error ? err.message : 'Download failed'
          console.error('[SAATIRIL] APK download proxy error:', message)
          res.writeHead(500, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ error: message }))
        })
    })
    return
  }

  // Method not allowed
  res.writeHead(405, { 'Content-Type': 'text/plain' })
  res.end('Method Not Allowed')
}

/**
 * Handle /api/generate-license requests
 * POST → Generates a license activation code (same logic as Next.js API route)
 */
function handleGenerateLicenseApi(
  req: import('http').IncomingMessage,
  res: import('http').ServerResponse,
) {
  if (req.method !== 'POST') {
    res.writeHead(405, { 'Content-Type': 'text/plain' })
    res.end('Method Not Allowed')
    return
  }

  let body = ''
  req.on('data', (chunk: Buffer) => { body += chunk.toString() })
  req.on('end', () => {
    try {
      const { machineId, adminKey } = JSON.parse(body)
      const result = generateLicenseCode(machineId, adminKey)
      res.writeHead(result.success ? 200 : 403, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify(result))
    } catch (err: any) {
      res.writeHead(500, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ success: false, error: 'Invalid request body' }))
    }
  })
}

/**
 * Handle /api/ble-trigger requests — HTTP fallback for admin-ble.html.
 * POST { action: 'PANGGIL'|'NEXT'|'RESET', studentId?: string } → emits a
 * lan-message { event: 'BLE_TRIGGER', data: { action, studentId } } that the
 * admin dashboard handles by looking up the next student and emitting a
 * proper MC_CALL with { student, channel }. Mirrors Android SaatirilServer
 * POST /api/ble-trigger after the fix in d07fdeb.
 *
 * BUG #11 fix (audit Remote/Mirror batch 2): restrict to loopback only.
 * Previously this endpoint accepted requests from ANY LAN client — anyone
 * could POST { action: 'NEXT' } and trigger an unsolicited student advance.
 * The admin-ble.html page runs on the admin laptop itself (it talks to MC
 * via BLE then forwards to the local Electron server), so loopback is
 * sufficient. Non-loopback requests are rejected with 403.
 */
function handleBleTriggerApi(
  req: import('http').IncomingMessage,
  res: import('http').ServerResponse,
) {
  // BUG #11 fix: loopback-only check. req.socket.remoteAddress may be
  // '127.0.0.1', '::1', '::ffff:127.0.0.1', or undefined (for unix sockets —
  // treat as loopback since unix sockets are local by definition).
  const remoteAddr = req.socket.remoteAddress || ''
  const isLoopback =
    remoteAddr === '127.0.0.1' ||
    remoteAddr === '::1' ||
    remoteAddr === '::ffff:127.0.0.1' ||
    remoteAddr === ''
  if (!isLoopback) {
    console.warn(`[SAATIRIL] /api/ble-trigger rejected from non-loopback address: ${remoteAddr}`)
    res.writeHead(403, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ success: false, error: 'Forbidden: loopback only' }))
    return
  }

  if (req.method !== 'POST') {
    res.writeHead(405, { 'Content-Type': 'text/plain' })
    res.end('Method Not Allowed')
    return
  }
  let body = ''
  req.on('data', (chunk: Buffer) => { body += chunk.toString() })
  req.on('end', () => {
    try {
      const { action, studentId } = JSON.parse(body || '{}')
      // Re-emit as lan-message → admin dashboard's BLE_TRIGGER handler
      // converts to a proper MC_CALL with { student, channel }.
      socketServer?.emit('lan-message', {
        event: 'BLE_TRIGGER',
        data: { action, studentId },
      })
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ success: true, action, studentId }))
    } catch (err: any) {
      res.writeHead(400, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ success: false, error: 'Invalid JSON body' }))
    }
  })
}

// ─── Resource path resolution ──────────────────────────────────────────────
// With asar:true, files are inside resources/app.asar
//   __dirname → /path/to/resources/app.asar/electron
//   path.join(__dirname, '..', 'out') → /path/to/resources/app.asar/out
//   Electron's fs module reads transparently from asar archives.
// With asar:false (fallback), files are in resources/app/
function getResourcePath(relativePath: string): string {
  // Primary: files inside app (asar or directory)
  const appPath = path.join(__dirname, '..', relativePath)
  if (fs.existsSync(appPath)) {
    return appPath
  }
  // Fallback: extraResources at process.resourcesPath
  const extraPath = path.join(process.resourcesPath, relativePath)
  if (fs.existsSync(extraPath)) {
    return extraPath
  }
  return appPath
}

// ─── Static file server for Next.js export ─────────────────────────────────
let httpServer: ReturnType<typeof createServer> | null = null
let socketServer: SocketIOServer | null = null

// In-memory path cache to avoid repeated fs.existsSync/fs.statSync calls
const filePathCache = new Map<string, string | null>()

function startStaticServer(outDir: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const mimeTypes: Record<string, string> = {
      '.html': 'text/html',
      '.js': 'text/javascript',
      '.mjs': 'text/javascript',
      '.css': 'text/css',
      '.json': 'application/json',
      '.png': 'image/png',
      '.jpg': 'image/jpeg',
      '.jpeg': 'image/jpeg',
      '.gif': 'image/gif',
      '.svg': 'image/svg+xml',
      '.ico': 'image/x-icon',
      '.woff': 'font/woff',
      '.woff2': 'font/woff2',
      '.ttf': 'font/ttf',
      '.webp': 'image/webp',
      '.map': 'application/json',
    }

    // Pre-warm the cache: scan the outDir for all files at startup
    try {
      const scanDir = (dir: string, base: string) => {
        try {
          const entries = fs.readdirSync(dir, { withFileTypes: true })
          for (const entry of entries) {
            const fullPath = path.join(dir, entry.name)
            const relPath = '/' + path.relative(outDir, fullPath).replace(/\\/g, '/')
            if (entry.isDirectory()) {
              scanDir(fullPath, base)
            } else {
              filePathCache.set(relPath, fullPath)
              // Also cache without leading slash
              filePathCache.set(relPath.slice(1), fullPath)
            }
          }
        } catch { /* ignore scan errors for individual dirs */ }
      }
      scanDir(outDir, outDir)
      console.log(`[SAATIRIL] File path cache: ${filePathCache.size} entries`)
    } catch (err: any) {
      console.warn('[SAATIRIL] Failed to pre-cache file paths:', err.message)
    }

    // ── GitHub Release asset type ──────────────────────────────────
    // BUG #2 cross-cutting fix: removed the unused GhAsset/GhRelease
    // interfaces that were only referenced by the dead duplicate
    // /api/apk-download handler (now also removed). The first handler uses
    // the proper fetchLatestReleaseInfo() + CachedRelease types defined at
    // the module level — no need for these local interfaces.

    httpServer = createServer((req, res) => {
      // ── CORS headers for all responses ──────────────────────────────────
      res.setHeader('Access-Control-Allow-Origin', '*')
      res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type')

      // Handle OPTIONS preflight for API routes
      if (req.method === 'OPTIONS') {
        res.writeHead(200)
        res.end()
        return
      }

      let urlPath = req.url?.split('?')[0] || '/'
      const urlQuery = req.url?.split('?')[1] || ''

      // ── API Routes (dynamic — NOT in static export) ─────────────────────
      // These must be handled here because the static export doesn't include
      // Next.js API routes. The Electron portable version runs a simple
      // HTTP file server, so we intercept API requests and handle them directly.
      if (urlPath === '/api/apk-download') {
        handleApkDownloadApi(req, res, urlQuery)
        return
      }

      if (urlPath === '/api/generate-license') {
        handleGenerateLicenseApi(req, res)
        return
      }

      // ── POST /api/ble-trigger — admin-ble.html HTTP fallback for
      //    PANGGIL/NEXT/RESET actions. The page also emits via socket.io
      //    (primary path); this HTTP route is the redundant backup.
      //    We re-emit as a lan-message so the admin dashboard's existing
      //    BLE_TRIGGER handler converts it into a proper MC_CALL with
      //    { student, channel }.
      if (urlPath === '/api/ble-trigger') {
        handleBleTriggerApi(req, res)
        return
      }

      if (urlPath === '/') urlPath = '/index.html'

      // ── /mc + /operator → redirect to root route with ?role= param ──────
      // UNIFIED: browser pages now load the SAME Next.js React panel
      // (mc-panel.tsx / operator-panel.tsx) as the Electron apps, so display
      // + data sync are identical across MC page, MC App, Operator page,
      // Operator App, and the Portable's MC/Operator panels. The old standalone
      // mc.html / operator.html had drifted from the React panels (different
      // layout, desynced data). This redirect preserves any existing query
      // (channel, socketPort, v) and just prepends role=<mc|operator>.
      if (urlPath === '/mc' || urlPath === '/operator') {
        const role = urlPath === '/mc' ? 'mc' : 'operator'
        const newQuery = urlQuery ? `role=${role}&${urlQuery}` : `role=${role}`
        res.writeHead(302, {
          Location: `/?${newQuery}`,
          'Cache-Control': 'no-cache',
        })
        res.end()
        return
      }

      // BUG #3 cross-cutting fix (audit Remote/Mirror batch 2): 301 redirect
      // the legacy .html variants (/mc.html, /operator.html) to the React app
      // — same destination as the /mc + /operator redirects above. Without
      // this, anyone who bookmarked or QR-coded the old .html URL would still
      // get the stale standalone HTML (via the static file server's
      // `urlPath + '.html'` fallback further down), bypassing the unified
      // React panel. 301 (permanent) so browsers cache the redirect + don't
      // re-hit the legacy URL on every load. We DO NOT redirect /mirror.html,
      // /admin-ble.html, /mc-ble.html — those have no React equivalent and
      // must still be served as standalone HTML.
      if (urlPath === '/mc.html' || urlPath === '/operator.html') {
        const role = urlPath === '/mc.html' ? 'mc' : 'operator'
        // Strip the password query param if present (BUG #9 fix — no password
        // in URLs). Other params (channel, socketPort, v) are preserved.
        const params = new URLSearchParams(urlQuery)
        params.delete('password')
        params.set('role', role)
        const newQuery = params.toString()
        res.writeHead(301, {
          Location: `/?${newQuery}`,
          'Cache-Control': 'public, max-age=86400',
        })
        res.end()
        return
      }

      // ── Version endpoint — lightweight probe for monitoring/diagnostics ──
      // Returns the Saatiril build version (v24-client-sync-fix). MC + operator HTML
      // also expose this via <meta name="saatiril-version"> + console.log.
      if (urlPath === '/version' || urlPath === '/api/version') {
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ version: 'v24-client-sync-fix', component: 'saatiril-server' }))
        return
      }

      // ── MC BLE Remote web page — uses Web Bluetooth API ──────────────
      if (urlPath === '/mc-ble') {
        const mcBleHtmlPath = getResourcePath('public/mc-ble.html')
        if (fs.existsSync(mcBleHtmlPath)) {
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
          fs.createReadStream(mcBleHtmlPath).pipe(res)
          return
        }
      }

      // ── Live Mirror page — fullscreen camera + countdown for the
      //    participant display (separate screen / tablet / projector).
      //    The operator keeps their full Operator panel on the main laptop;
      //    this page is opened on a SEPARATE device or 2nd monitor so
      //    participants can see themselves (mirror) + countdown + flash.
      //    Query params:
      //      ?channel=N   — filter frames by operator channel (default 1)
      //      ?flip=0      — disable horizontal mirror (default: flipped)
      //      ?ip=hostname — override the socket.io server IP (default: this host)
      //    The page connects via socket.io (role=mirror) and listens for
      //    MIRROR_FRAME / MIRROR_COUNTDOWN / MIRROR_CAPTURE lan-messages.
      if (urlPath === '/mirror') {
        const mirrorHtmlPath = getResourcePath('public/mirror.html')
        if (fs.existsSync(mirrorHtmlPath)) {
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
          fs.createReadStream(mirrorHtmlPath).pipe(res)
          return
        }
      }

      // ── Admin BLE Client web page — Electron connects to MC-Only APK ──
      // Admin opens this → scans for MC HP (BLE Server) → connects

      // ── API: BLE trigger from MC (via /admin-ble page) ──────────────
      // The admin-ble.html page forwards MC's PANGGIL/NEXT/RESET triggers
      // to this endpoint. We emit a 'BLE_TRIGGER' lan-message that the
      // admin dashboard handles by looking up the next student and
      // emitting a proper MC_CALL with the correct {student, channel} format.

      // ── API: BLE project data (for /admin-ble page) ────────────────
      // GET: returns current project data so admin-ble.html can push it to MC
      // POST: admin-dashboard.tsx pushes project data here (more reliable than
      //       relying on SYNC_DB capture, which only fires for socket relay)

      // BUG #2 cross-cutting fix (audit Remote/Mirror batch 2): removed the
      // dead duplicate /api/apk-download handler that previously lived here.
      // The first /api/apk-download handler (above, around line 446) calls
      // handleApkDownloadApi() — a proper function with the same GET/POST
      // behavior + caching via fetchLatestReleaseInfo. The duplicate below
      // was UNREACHABLE because the first handler returns immediately, but
      // it was a maintenance hazard (two implementations of the same route
      // could drift silently). Also removed the now-unused GhAsset/GhRelease
      // interfaces that were only referenced by the dead handler.

      // Check cache first (O(1) lookup instead of 3× fs.existsSync)
      const cachedPath = filePathCache.get(urlPath)
      if (cachedPath) {
        const ext = path.extname(cachedPath).toLowerCase()
        const contentType = mimeTypes[ext] || 'application/octet-stream'
        res.writeHead(200, { 'Content-Type': contentType })
        fs.createReadStream(cachedPath).pipe(res)
        return
      }

      // Cache miss: try with .html extension and /index.html for SPA routing
      const tryPaths = [
        urlPath + '.html',
        urlPath.endsWith('/') ? urlPath + 'index.html' : urlPath + '/index.html',
      ]

      for (const tryPath of tryPaths) {
        const cachedTry = filePathCache.get(tryPath)
        if (cachedTry) {
          const ext = path.extname(cachedTry).toLowerCase()
          const contentType = mimeTypes[ext] || 'application/octet-stream'
          res.writeHead(200, { 'Content-Type': contentType })
          fs.createReadStream(cachedTry).pipe(res)
          return
        }
      }

      // Final fallback to index.html for SPA routing
      const indexCached = filePathCache.get('/index.html')
      if (indexCached) {
        res.writeHead(200, { 'Content-Type': 'text/html' })
        fs.createReadStream(indexCached).pipe(res)
        return
      }

      res.writeHead(404, { 'Content-Type': 'text/plain' })
      res.end('Not Found')
    })

    httpServer.on('error', (err: any) => {
      if (err.code === 'EADDRINUSE') {
        console.warn(`[SAATIRIL] Port ${httpPort} in use, trying ${httpPort + 1}...`)
        httpPort++
        httpServer!.close()
        httpServer!.listen(httpPort, '0.0.0.0', () => {
          console.log(`[SAATIRIL] Static file server running on http://0.0.0.0:${httpPort} (accessible from LAN)`)
          // BUG FIX (audit Remote BUG #5): add firewall rule for the
          // ACTUAL port we're listening on (may be auto-incremented past
          // the default 3000 if 3000 was in use). Without this, LAN
          // clients get ERR_CONNECTION_REFUSED from Windows Firewall.
          // addFirewallRule is idempotent (checks for existing rule first).
          addFirewallRule(httpPort)
          resolve()
        })
      } else {
        console.error('[SAATIRIL] HTTP server error:', err.message)
        reject(err)
      }
    })

    httpServer.listen(httpPort, '0.0.0.0', () => {
      console.log(`[SAATIRIL] Static file server running on http://0.0.0.0:${httpPort} (accessible from LAN)`)
      // BUG FIX (audit Remote BUG #5): also add firewall rule for the
      // initial port (in case it didn't hit EADDRINUSE). Idempotent.
      addFirewallRule(httpPort)
      resolve()
    })
  })
}

// ─── Socket.io Relay Server ────────────────────────────────────────────────
// Session password hash storage (shared with embedded server)
let sessionPasswordHash: string | null = null

/**
 * Broadcast auth-requirement to ALL connected clients.
 * Called when the session password is set or cleared by admin.
 * This ensures existing clients are notified about the auth requirement change.
 */
function broadcastAuthRequirement(server: SocketIOServer) {
  const payload = { passwordRequired: sessionPasswordHash !== null }
  server.emit('auth-requirement', payload)
  console.log(`[SAATIRIL] Broadcast auth-requirement: passwordRequired=${payload.passwordRequired}`)
}

function startSocketServer(): Promise<void> {
  return new Promise((resolve, reject) => {
    const httpForSocket = createServer()

    socketServer = new SocketIOServer(httpForSocket, {
      // Use path '/' — matches APK ktor server and socket.io-client v2.1.0
      // This allows APK MC/Operator to connect to Electron server
      path: '/',
      cors: { origin: '*', methods: ['GET', 'POST'] },
      pingInterval: 5000,
      pingTimeout: 15000,
      maxHttpBufferSize: MAX_HTTP_BUFFER,
      connectionStateRecovery: { maxDisconnectionDuration: 5 * 60 * 1000 },
      transports: ['websocket', 'polling'],
      allowUpgrades: true,
      // CRITICAL: Allow Engine.IO v3 clients (Android APK + vanilla JS web pages)
      // Without this, the APK's raw WebSocket EIO3 clients CANNOT connect.
      allowEIO3: true,
    })

    // Connection limit
    const MAX_CONNECTIONS = 10
    const IDENTIFICATION_TIMEOUT_MS = 15000
    socketServer.use((socket, next) => {
      const current = socketServer!.sockets.sockets.size
      if (current >= MAX_CONNECTIONS) {
        next(new Error('Connection limit reached'))
        return
      }
      next()
    })

    // Client tracking — role can be 'unknown' | 'admin' | 'mc' | 'operator' | 'pending_auth'
    const clientRegistry = new Map<string, { role: string; channel: number }>()

    socketServer.on('connection', (socket) => {
      clientRegistry.set(socket.id, { role: 'unknown', channel: 0 })

      // Send auth requirement on connect
      socket.emit('auth-requirement', { passwordRequired: sessionPasswordHash !== null })

      // Identification timeout — disconnect if not identified within timeout
      // CRITICAL: 'pending_auth' clients are NOT disconnected — they're waiting
      // for the user to enter the session password. Only truly 'unknown' clients
      // (that never sent an identify at all) are disconnected.
      const identificationTimeout = setTimeout(() => {
        const info = clientRegistry.get(socket.id)
        if (info && info.role === 'unknown') {
          console.warn(`[SAATIRIL] Client ${socket.id} disconnected: failed to identify within ${IDENTIFICATION_TIMEOUT_MS}ms`)
          socket.disconnect(true)
        }
      }, IDENTIFICATION_TIMEOUT_MS)

      // Admin sets session password
      socket.on('SET_SESSION_PASSWORD', (data: { passwordHash: string }) => {
        const info = clientRegistry.get(socket.id)
        if (info && info.role === 'admin') {
          sessionPasswordHash = data.passwordHash
          console.log('[SAATIRIL] Session password set by admin — broadcasting to all clients')
          // Broadcast auth-requirement to ALL clients so they know password is now required
          broadcastAuthRequirement(socketServer!)
        }
      })

      // Admin clears session password
      socket.on('CLEAR_SESSION_PASSWORD', () => {
        const info = clientRegistry.get(socket.id)
        if (info && info.role === 'admin') {
          sessionPasswordHash = null
          console.log('[SAATIRIL] Session password cleared — broadcasting to all clients')
          // Broadcast auth-requirement to ALL clients so they know password is no longer required
          broadcastAuthRequirement(socketServer!)
        }
      })

      // Client identification with session password validation
      socket.on('identify', (data: { role: string; channel: number; sessionPasswordHash?: string }) => {
        const info = clientRegistry.get(socket.id)
        if (!info) return

        // BUG FIX (audit Remote BUG #1): admin role requires loopback origin.
        // Previously any LAN client could claim role='admin' with no verification
        // → full admin powers (set/clear session password, unrestricted relay).
        // Now admin is only allowed from 127.0.0.1 / ::1 (the admin's own laptop).
        // Remote clients must use 'mc' / 'operator' / 'mirror' roles.
        if (data.role === 'admin') {
          const addr = socket.handshake.address || ''
          const isLoopback = addr === '127.0.0.1' || addr === '::1' || addr === '::ffff:127.0.0.1'
          if (!isLoopback) {
            console.warn(`[SAATIRIL] Client ${socket.id} rejected: admin role from non-loopback address ${addr}`)
            info.role = 'pending_auth'
            info.channel = data.channel
            socket.emit('auth-failed', { reason: 'admin_role_requires_loopback' })
            return
          }
        }

        // Validate session password for non-admin when password is set.
        // BUG #4 (audit-electron-side): role='mirror' is EXEMPT from the
        // session password requirement. The mirror is display-only (no
        // state mutations — it only emits MIRROR_REQUEST to ask the operator
        // for MIRROR_CONFIG). Without this exemption, when a session password
        // is set, the mirror's identify is rejected → role becomes
        // 'pending_auth' → its MIRROR_REQUEST lan-message is blocked by the
        // relay auth check (line ~850) → the operator never receives the
        // request → MIRROR_CONFIG is never sent until the operator's 10s
        // periodic re-emit kicks in → 10s black screen on the projector.
        // Mirrors cannot inject malicious events: the operator only acts on
        // MIRROR_REQUEST (responding with read-only MIRROR_CONFIG); any other
        // lan-message they emit is still subject to the receiver-side
        // merge/sync logic which doesn't regress statuses.
        if (data.role !== 'admin' && data.role !== 'mirror' && sessionPasswordHash) {
          // BUG FIX (audit Remote BUG #6): timing-safe comparison.
          // Previously used !== which short-circuits on first byte mismatch
          // → timing attack could recover the hash byte-by-byte.
          const clientHash = data.sessionPasswordHash || ''
          const expected = sessionPasswordHash
          // Use a constant-time comparison via Buffer.compare on equal-length
          // buffers (both are 64-char SHA-256 hex strings). If lengths differ,
          // still do a comparison to keep timing constant.
          const a = Buffer.from(clientHash.padEnd(expected.length, '0'))
          const b = Buffer.from(expected)
          const matches = Buffer.compare(a, b) === 0 && clientHash.length === expected.length
          if (!matches) {
            console.warn(`[SAATIRIL] Client ${socket.id} rejected: invalid session password (role: ${data.role})`)
            info.role = 'pending_auth'
            info.channel = data.channel
            socket.emit('auth-failed', { reason: 'session_password_required' })
            return
          }
        }

        info.role = data.role
        info.channel = data.channel
        console.log(`[SAATIRIL] Client: ${socket.id} → ${data.role} Ch.${data.channel}`)
        socket.emit('auth-success', { role: data.role, channel: data.channel })
      })

      socket.on('saatiril-ping', (timestamp: number) => {
        socket.emit('saatiril-pong', timestamp)
      })

      socket.on('lan-message', (payload: { event: string; data: any }) => {
        const info = clientRegistry.get(socket.id)
        if (!info || info.role === 'unknown' || info.role === 'pending_auth') {
          // Only fully authenticated clients can relay messages
          if (info?.role === 'pending_auth') {
            console.warn(`[SAATIRIL] Pending-auth client ${socket.id} tried to relay message — ignoring (needs password)`)
          }
          return
        }
        // BUG #5 (audit-electron-side): filter the broadcast by role so
        // clients only receive events they actually need. Previously
        // `socket.broadcast.emit('lan-message', payload)` sent EVERY event
        // to EVERY socket — including unauthenticated + wrong-role clients.
        // The mirror (display-only) was receiving 5MB PHOTOS_SAVED payloads
        // + full SYNC_DB databases it discards, causing CPU/memory pressure
        // on low-power projector devices. Unauthenticated clients on the LAN
        // also received all student data + photo base64 (the session password
        // only blocked RELAY, not RECEIVING).
        //  - MIRROR_* events → only 'mirror' role clients (display-only).
        //  - All other events (SYNC_DB, MC_CALL, PHOTOS_SAVED, STUDENT_DONE,
        //    STUDENT_RESET, OP_PROGRESS, BLE_TRIGGER, …) → only 'admin',
        //    'mc', 'operator' roles (state-bearing clients). 'pending_auth'
        //    + 'unknown' are excluded (they get data via the periodic 3s
        //    SYNC_DB from admin-dashboard once they authenticate).
        const ev = payload?.event || ''
        const isMirrorEvent = typeof ev === 'string' && ev.indexOf('MIRROR_') === 0
        const statefulRoles = new Set(['admin', 'mc', 'operator'])
        const mirrorRoles = new Set(['mirror'])
        const targetRoles = isMirrorEvent ? mirrorRoles : statefulRoles
        for (const [sid, sinfo] of clientRegistry.entries()) {
          if (sid === socket.id) continue  // don't echo back to sender
          if (!targetRoles.has(sinfo.role)) continue
          const targetSocket = socketServer!.sockets.sockets.get(sid)
          if (targetSocket) {
            targetSocket.emit('lan-message', payload)
          }
        }
      })

      socket.on('server-stats', (callback: (stats: any) => void) => {
        if (typeof callback === 'function') {
          callback({
            connectedClients: socketServer!.sockets.sockets.size,
            clients: Array.from(clientRegistry.values()),
            sessionPasswordActive: sessionPasswordHash !== null,
          })
        }
      })

      socket.on('disconnect', () => {
        clearTimeout(identificationTimeout)
        clientRegistry.delete(socket.id)
      })
    })

    httpForSocket.on('error', (err: any) => {
      if (err.code === 'EADDRINUSE') {
        console.warn(`[SAATIRIL] Socket port ${socketPort} in use, trying ${socketPort + 1}...`)
        socketPort++
        httpForSocket.close()
        httpForSocket.listen(socketPort, '0.0.0.0', () => {
          console.log(`[SAATIRIL] Socket.io relay server running on port ${socketPort}`)
          // BUG FIX (audit Remote BUG #5): add firewall rule for the actual
          // socket port (may be auto-incremented past 3003).
          addFirewallRule(socketPort)
          resolve()
        })
      } else {
        console.error('[SAATIRIL] Socket server error:', err.message)
        reject(err)
      }
    })

    httpForSocket.listen(socketPort, () => {
      console.log(`[SAATIRIL] Socket.io relay server running on port ${socketPort}`)
      // BUG FIX (audit Remote BUG #5): also add firewall rule for the
      // initial socket port (in case it didn't hit EADDRINUSE).
      addFirewallRule(socketPort)
      resolve()
    })
  })
}

// ─── Windows Firewall: allow incoming on HTTP + Socket ports ──────────────
// Without this, Windows Firewall blocks LAN clients from connecting to
// the HTTP server (port 3000) and Socket.io server (port 3003).
// We use `netsh advfirewall` to add a rule — requires admin privileges
// (Electron portable runs with user privileges by default, but netsh
// firewall add works without elevation for per-user rules in Windows 10+).
function addFirewallRule(port: number): void {
  if (process.platform !== 'win32') return
  const ruleName = `Saatiril Port ${port}`
  try {
    const { execSync } = require('child_process')
    // Check if rule already exists
    try {
      execSync(`netsh advfirewall firewall show rule name="${ruleName}"`, { stdio: 'pipe' })
      // Rule exists — skip
      return
    } catch {
      // Rule doesn't exist — create it
    }
    // Add firewall rule to allow inbound TCP on this port
    execSync(`netsh advfirewall firewall add rule name="${ruleName}" dir=in action=allow protocol=TCP localport=${port}`, { stdio: 'pipe' })
    console.log(`[SAATIRIL] Firewall rule added: ${ruleName} (TCP port ${port})`)
  } catch (err: any) {
    console.warn(`[SAATIRIL] Could not add firewall rule for port ${port}: ${err.message}`)
    console.warn(`[SAATIRIL] Manual fix: Run as admin, or add firewall rule manually:`)
    console.warn(`[SAATIRIL]   netsh advfirewall firewall add rule name="Saatiril Port ${port}" dir=in action=allow protocol=TCP localport=${port}`)
  }
}

// ─── Get LAN IP addresses ─────────────────────────────────────────────────
function getLanIPs(): { name: string; address: string }[] {
  const interfaces = os.networkInterfaces()
  const results: { name: string; address: string }[] = []

  for (const [name, nets] of Object.entries(interfaces)) {
    if (!nets) continue
    for (const net of nets) {
      // Skip internal and non-IPv4
      if (net.family === 'IPv4' && !net.internal) {
        results.push({ name, address: net.address })
      }
    }
  }

  return results
}

// ─── IPC Handlers ──────────────────────────────────────────────────────────
function registerIpcHandlers() {
  // Select folder dialog
  ipcMain.handle('select-folder', async (_event, defaultPath: string) => {
    const result = await dialog.showOpenDialog({
      defaultPath: defaultPath || undefined,
      properties: ['openDirectory', 'createDirectory'],
    })
    if (result.canceled) return null
    return result.filePaths[0] || null
  })

  // Create folder
  ipcMain.handle('create-folder', async (_event, folderPath: string) => {
    try {
      fs.mkdirSync(folderPath, { recursive: true })
      return { success: true, path: folderPath }
    } catch (err: any) {
      return { success: false, error: err.message }
    }
  })

  // Save photo to disk
  ipcMain.handle('save-photo', async (_event, data: { base64Data: string; filename: string; targetFolder: string; backupFolder?: string | null; projectName?: string | null }) => {
    try {
      const { base64Data, filename, targetFolder, backupFolder, projectName } = data

      // Ensure target folder exists
      fs.mkdirSync(targetFolder, { recursive: true })

      // Strip data URL prefix if present
      const base64 = base64Data.replace(/^data:image\/\w+;base64,/, '')
      const buffer = Buffer.from(base64, 'base64')

      const filePath = path.join(targetFolder, filename)
      fs.writeFileSync(filePath, buffer)

      console.log(`[SAATIRIL] Photo saved: ${filePath} (${(buffer.length / 1024).toFixed(1)}KB)`)

      // ── Google Drive / cloud backup (PER-PROJECT, opt-in) ──
      // Copies the saved photo to THIS project's backup folder (e.g. Google
      // Drive desktop folder) if the admin explicitly set one for this
      // project. NOT auto-connected — the admin picks a folder per-project
      // via the Admin tab's Google Drive section. If driveFolder is null/
      // undefined, no backup (the local copy is still safe). Each project
      // can have its own folder (or none).
      //
      // MIRRORS the local folder structure: backupFolder/projectName/filename
      // (not flat backupFolder/filename). The projectName is sanitized the
      // same way as local save (strip non-alphanumeric, spaces → underscores)
      // so the folder name matches local exactly.
      try {
        // Prefer the per-project backupFolder passed in the savePhoto call.
        // Fall back to the legacy global getBackupFolder() for backward
        // compat with older callers that don't pass backupFolder.
        const backupFolderToUse = backupFolder ?? getBackupFolder()
        if (backupFolderToUse) {
          // Determine the subfolder path: backupFolder/projectName/filename.
          // If projectName is not provided, fall back to flat (legacy behavior).
          let backupPath: string
          if (projectName && projectName.trim()) {
            // Sanitize projectName the SAME way as local save (project-setup.tsx):
            // strip non-alphanumeric (keep _, -, space), then spaces → underscores.
            const sanitizedName = projectName.trim()
              .replace(/[^a-zA-Z0-9_\-\s]/g, '')
              .replace(/\s+/g, '_')
            if (sanitizedName) {
              const projectSubfolder = path.join(backupFolderToUse, sanitizedName)
              // Ensure the project subfolder exists (mkdir -p). Idempotent —
              // won't fail if it already exists.
              fs.mkdirSync(projectSubfolder, { recursive: true })
              backupPath = path.join(projectSubfolder, filename)
              console.log(`[SAATIRIL] Photo backup (project subfolder): ${backupPath}`)
            } else {
              // Sanitized name is empty — fall back to flat.
              backupPath = path.join(backupFolderToUse, filename)
              console.log(`[SAATIRIL] Photo backup (flat — sanitized name empty): ${backupPath}`)
            }
          } else {
            // No projectName — flat backup (legacy behavior).
            backupPath = path.join(backupFolderToUse, filename)
            console.log(`[SAATIRIL] Photo backup (flat — no projectName): ${backupPath}`)
          }
          fs.writeFileSync(backupPath, buffer)
        }
      } catch (backupErr: any) {
        // Backup failure is non-fatal — local photo is already saved
        console.warn(`[SAATIRIL] Photo backup failed (non-fatal): ${backupErr.message}`)
      }

      return filePath
    } catch (err: any) {
      console.error('[SAATIRIL] Failed to save photo:', err.message)
      return null
    }
  })

  // Count photos on disk (for the Disk badge — persists across project
  // close/reopen + app restart, unlike the in-memory diskSaveStats counter
  // which reset to 0). Called on AdminDashboard mount + after each save.
  ipcMain.handle('count-photos', async (_event, data: { targetFolder: string }) => {
    try {
      const { targetFolder } = data
      if (!targetFolder || !fs.existsSync(targetFolder)) return 0
      const files = fs.readdirSync(targetFolder)
      const photos = files.filter(f => /\.(jpg|jpeg|png)$/i.test(f))
      return photos.length
    } catch (err: any) {
      console.error('[SAATIRIL] count-photos failed:', err.message)
      return 0
    }
  })

  // BUG #7 (audit-electron-side): read a photo file from disk + return it as a
  // base64 data URL. Used by the admin gallery to LAZILY rehydrate thumbnails
  // after an app restart — saveProjectsToStorage strips photo base64 from
  // photoHistory (to stay under localStorage quotas), so after a restart the
  // gallery showed entries with NO thumbnails (just the ImageIcon placeholder).
  // The admin can now see the actual photos again without re-capturing. The
  // renderer calls this on-demand for each visible gallery item whose `photos`
  // array is empty. Returns null if the file is missing (e.g. operator saved
  // to a different folder) — caller falls back to the placeholder.
  ipcMain.handle('read-photo', async (_event, data: { filename: string; targetFolder: string }) => {
    try {
      const { filename, targetFolder } = data
      if (!targetFolder || !filename) return null
      const filePath = path.join(targetFolder, filename)
      if (!fs.existsSync(filePath)) return null
      const buffer = fs.readFileSync(filePath)
      // Convert to base64 data URL (matches the format the operator emits in
      // PHOTOS_SAVED, so the renderer's <img src> handling is identical).
      const base64 = buffer.toString('base64')
      const ext = path.extname(filename).toLowerCase()
      const mime = ext === '.png' ? 'image/png' : 'image/jpeg'
      return `data:${mime};base64,${base64}`
    } catch (err: any) {
      console.error('[SAATIRIL] read-photo failed:', err.message)
      return null
    }
  })

  // ── Google Drive / cloud backup folder ────────────────────────────────
  // Admin picks a folder (e.g. G:\My Drive\Saatiril\ via Google Drive for
  // Desktop, or any cloud-synced folder). Photos are copied there after
  // saving locally. Cloud app handles the actual upload + retry.

  // Config file path for persisting backup folder setting
  const backupConfigPath = path.join(app.getPath('userData'), 'backup-config.json')

  function getBackupFolder(): string | null {
    try {
      if (!fs.existsSync(backupConfigPath)) return null
      const config = JSON.parse(fs.readFileSync(backupConfigPath, 'utf-8'))
      const folder = config.backupFolder
      if (!folder || !fs.existsSync(folder)) return null
      return folder
    } catch {
      return null
    }
  }

  function setBackupFolderInternal(folder: string | null) {
    try {
      const config = { backupFolder: folder }
      fs.writeFileSync(backupConfigPath, JSON.stringify(config, null, 2))
    } catch (err: any) {
      console.error('[SAATIRIL] Failed to save backup config:', err.message)
    }
  }

  // Select backup folder (native folder picker)
  ipcMain.handle('select-backup-folder', async () => {
    const result = await dialog.showOpenDialog({
      title: 'Pilih Folder Google Drive / Cloud Backup',
      properties: ['openDirectory', 'createDirectory'],
    })
    if (result.canceled) return null
    const folder = result.filePaths[0] || null
    if (folder) {
      setBackupFolderInternal(folder)
      console.log(`[SAATIRIL] Backup folder set: ${folder}`)
    }
    return folder
  })

  // Get current backup folder (or null if not set)
  ipcMain.handle('get-backup-folder', async () => {
    return getBackupFolder()
  })

  // Clear backup folder (disable backup)
  ipcMain.handle('clear-backup-folder', async () => {
    setBackupFolderInternal(null)
    console.log('[SAATIRIL] Backup folder cleared')
    return true
  })

  // Get backup stats: count .jpg files in backup folder
  ipcMain.handle('get-backup-stats', async () => {
    const folder = getBackupFolder()
    if (!folder) return { connected: false, totalFiles: 0 }
    try {
      const files = fs.readdirSync(folder).filter(f => f.endsWith('.jpg') || f.endsWith('.jpeg'))
      return { connected: true, totalFiles: files.length }
    } catch {
      return { connected: false, totalFiles: 0 }
    }
  })

  // Open URL in default browser (Chrome/Edge) — used for Web Bluetooth
  // Electron doesn't support navigator.bluetooth, so /admin-ble must open
  // in the user's default browser which DOES support Web Bluetooth.
  ipcMain.handle('open-in-browser', async (_event: any, url: string) => {
    try {
      await shell.openExternal(url)
      console.log(`[SAATIRIL] Opened in default browser: ${url}`)
      return true
    } catch (err: any) {
      console.error(`[SAATIRIL] Failed to open URL in browser: ${err.message}`)
      return false
    }
  })

  // Get LAN info
  ipcMain.handle('get-lan-info', async () => {
    return {
      httpPort,
      socketPort,
      ips: getLanIPs(),
    }
  })

  // ── Live Mirror projector window ──────────────────────────────────────
  // Opens a SEPARATE BrowserWindow on the external display (HDMI) showing
  // ONLY the /mirror page (live camera + countdown + flash). The operator's
  // full Operator panel stays on the main laptop display. Uses localhost
  // socket.io (no network latency) → smooth, no WiFi streaming jank.
  //
  // User setup:
  //   1. Connect projector via HDMI cable to laptop.
  //   2. Windows + P → "Extend" (NOT Duplicate — Duplicate would show the
  //      full Operator panel on the projector, including all UI clutter).
  //   3. In Operator panel, click "Buka di Projector" button.
  //
  // Returns: { success, reason?, displays? } so the React side can show a
  // helpful toast if no external display is detected.
  ipcMain.handle('open-mirror-on-projector', async (_event, channel: number) => {
    try {
      // Find an external display (one that's NOT the primary).
      const displays = screen.getAllDisplays()
      const primaryDisplay = screen.getPrimaryDisplay()
      const externalDisplay = displays.find((d) => d.id !== primaryDisplay.id)

      if (!externalDisplay) {
        return {
          success: false,
          reason: 'NO_EXTERNAL_DISPLAY',
          displays: displays.length,
        }
      }

      // If the window already exists, just focus it (user clicked the button
      // twice — don't open a duplicate window).
      if (mirrorWindow && !mirrorWindow.isDestroyed()) {
        mirrorWindow.focus()
        return { success: true, reason: 'ALREADY_OPEN', displays: displays.length }
      }

      // Build the /mirror URL via localhost (super fast — no network latency).
      // Same httpPort + socketPort as the main window uses.
      const mirrorUrl = `http://localhost:${httpPort}/mirror?channel=${channel}&socketPort=${socketPort}`

      // Compute window bounds on the external display. Add a small margin so
      // the window doesn't bleed onto the primary display edge.
      const { x, y, width, height } = externalDisplay.bounds
      const winX = x + 8
      const winY = y + 8
      const winW = Math.max(640, width - 16)
      const winH = Math.max(480, height - 16)

      mirrorWindow = new BrowserWindow({
        x: winX,
        y: winY,
        width: winW,
        height: winH,
        fullscreen: true,        // Cover the entire external display.
        frame: false,           // No title bar — clean projector view.
        transparent: false,
        alwaysOnTop: true,      // Stay above other windows on the projector.
        skipTaskbar: true,      // Don't show in taskbar (it's a display window).
        backgroundColor: '#000000',
        title: `SAATIRIL Mirror — Ch.${channel}`,
        webPreferences: {
          preload: path.join(__dirname, 'preload.js'),
          contextIsolation: true,
          nodeIntegration: false,
          sandbox: false,
          webSecurity: false,    // Allow camera access on HTTP localhost.
        },
      })

      // When the mirror window closes, clear the reference so a new one can
      // be opened later.
      mirrorWindow.on('closed', () => {
        mirrorWindow = null
      })

      console.log(`[SAATIRIL] Opening Live Mirror on external display (Ch.${channel}): ${mirrorUrl}`)
      mirrorWindow.loadURL(mirrorUrl)

      // F11-style fullscreen as backup (some Electron versions need both).
      mirrorWindow.once('ready-to-show', () => {
        if (mirrorWindow && !mirrorWindow.isDestroyed()) {
          try {
            mirrorWindow.setFullScreen(true)
          } catch (e) {
            // ignore — fullscreen:true in constructor usually suffices.
          }
        }
      })

      return {
        success: true,
        reason: 'OPENED',
        displays: displays.length,
      }
    } catch (err: any) {
      console.error('[SAATIRIL] open-mirror-on-projector failed:', err)
      return { success: false, reason: 'ERROR: ' + (err?.message || 'unknown'), displays: 0 }
    }
  })

  // Close the Live Mirror projector window (called by the React side when
  // the user toggles Live Mirror OFF, or when changing channels to re-open
  // on the new channel).
  ipcMain.handle('close-mirror-projector', async () => {
    try {
      if (mirrorWindow && !mirrorWindow.isDestroyed()) {
        mirrorWindow.close()
      }
      mirrorWindow = null
      return { success: true }
    } catch (err: any) {
      return { success: false, reason: 'ERROR: ' + (err?.message || 'unknown') }
    }
  })

  // ── Mirror frame IPC fast path ────────────────────────────────────────
  // The operator panel sends the JPEG Blob directly to the mirror window
  // via IPC (structured clone — no base64, no JSON serialization, no
  // socket.io). This is the FAST path for the projector scenario (Buka di
  // Projector button opens a separate Electron BrowserWindow on the HDMI
  // display). Much faster than the socket.io path:
  //   - No FileReader.readAsDataURL (base64 encoding) — eliminated
  //   - No JSON serialization of ~10KB base64 string — eliminated
  //   - No socket.io wire transfer — eliminated
  //   - No base64 decode on mirror side — eliminated (Blob → object URL)
  // The only remaining overhead: canvas drawImage (GPU) + toBlob JPEG
  // encoding (async, off-thread) + IPC structured clone (~1ms) +
  // URL.createObjectURL (instant) + img.src decode (GPU).
  // The mirror window's preload.ts exposes onMirrorFrame(callback) which
  // listens for this IPC channel.
  //
  // If no mirror window is open (user hasn't clicked 'Buka di Projector'
  // or the window was closed), the frame is silently dropped — the
  // operator side should fall back to socket.io for cross-device scenarios.
  ipcMain.on('send-mirror-frame', (_event, blob: Blob) => {
    if (mirrorWindow && !mirrorWindow.isDestroyed()) {
      try {
        mirrorWindow.webContents.send('mirror-frame', blob)
      } catch (err) {
        // Window might have been destroyed mid-send — silently drop.
      }
    }
    // If no mirror window, silently drop (operator side handles fallback).
  })

  // Query whether the mirror projector window is currently open — the
  // operator panel uses this to decide whether to use the IPC fast path
  // (mirror window open → IPC) or the socket.io slow path (no mirror
  // window → for cross-device tablet/phone streaming).
  ipcMain.handle('is-mirror-window-open', async () => {
    return !!(mirrorWindow && !mirrorWindow.isDestroyed())
  })

  // ── License IPC handlers ──────────────────────────────────────────────

  // Get current license status
  ipcMain.handle('get-license-status', async () => {
    return checkLicenseStatus()
  })

  // Activate with an activation code
  ipcMain.handle('activate-license', async (_event, activationCode: string) => {
    return activateLicense(activationCode)
  })

  // Get Machine ID (full + display)
  ipcMain.handle('get-machine-id', async () => {
    const machineId = getMachineId()
    return {
      machineId,
      displayMachineId: getDisplayMachineId(machineId),
    }
  })

  // Generate license code (for admin/developer use)
  ipcMain.handle('generate-license-code', async (_event, machineId: string, adminKey: string) => {
    return generateLicenseCode(machineId, adminKey)
  })

  // ── Release info IPC ──────────────────────────────────────────────────
  // Fetches GitHub Release info from the main process (Node.js) to avoid
  // CORS / network issues when the renderer tries to call api.github.com
  // directly (which fails in portable Electron builds on some networks).
  ipcMain.handle('get-release-info', async () => {
    try {
      const ghRes = await fetch('https://api.github.com/repos/synclicen/Saatiril-Fullset/releases/tags/latest', {
        headers: { Accept: 'application/vnd.github+json' },
      })
      if (!ghRes.ok) throw new Error(`GitHub API returned ${ghRes.status}`)
      const release: any = await ghRes.json()
      const assets: any[] = release.assets || []

      const apkAsset = assets.find((a: any) => a.name.endsWith('.apk'))
      const portableAsset = assets.find((a: any) => a.name.endsWith('-portable.exe') || a.name === 'saatiril-portable.exe')

      const toInfo = (a: any) =>
        a ? { available: true, sizeMB: (a.size / (1024 * 1024)).toFixed(1), assetName: a.name, lastModified: a.updated_at, downloadUrl: a.browser_download_url }
          : { available: false, error: 'Not found in latest release' }

      return { apk: toInfo(apkAsset), portable: toInfo(portableAsset) }
    } catch (err: any) {
      return { apk: { available: false, error: err.message || 'GitHub API error' }, portable: { available: false, error: err.message || 'GitHub API error' } }
    }
  })
}

// ─── Splash/Loading Window ────────────────────────────────────────────────
let splashWindow: BrowserWindow | null = null

function createSplashWindow() {
  splashWindow = new BrowserWindow({
    width: 500,
    height: 350,
    frame: false,
    transparent: true,
    resizable: false,
    center: true,
    backgroundColor: '#00000000',
    alwaysOnTop: true,
    skipTaskbar: true,
    show: true,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
    },
  })

  // Load a simple splash screen HTML
  splashWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(splashHTML)}`)

  splashWindow.on('closed', () => {
    splashWindow = null
  })
}

const splashHTML = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      width: 500px;
      height: 350px;
      background: linear-gradient(135deg, #1a0b2e 0%, #2d1b69 50%, #1a0b2e 100%);
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
      color: white;
      border-radius: 16px;
      overflow: hidden;
    }
    .logo {
      font-size: 42px;
      font-weight: 800;
      letter-spacing: 6px;
      margin-bottom: 8px;
      background: linear-gradient(90deg, #a78bfa, #7c3aed, #a78bfa);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      background-size: 200% auto;
      animation: shimmer 2s linear infinite;
    }
    .subtitle {
      font-size: 13px;
      color: rgba(255,255,255,0.6);
      letter-spacing: 2px;
      margin-bottom: 40px;
    }
    .loader {
      width: 40px;
      height: 40px;
      border: 3px solid rgba(255,255,255,0.15);
      border-top-color: #7c3aed;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
    .status {
      margin-top: 20px;
      font-size: 12px;
      color: rgba(255,255,255,0.5);
      letter-spacing: 1px;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
    @keyframes shimmer { to { background-position: 200% center; } }
  </style>
</head>
<body>
  <div class="logo">SAATIRIL</div>
  <div class="subtitle">MANAJEMEN ACARA FOTO</div>
  <div class="loader"></div>
  <div class="status">Mempersiapkan aplikasi...</div>
</body>
</html>
`

// ─── Create Electron Window ────────────────────────────────────────────────
let mainWindow: BrowserWindow | null = null
// Separate BrowserWindow for the Live Mirror projector view. Opened on the
// external display (HDMI) so participants see ONLY the live camera + countdown
// — the operator's full Operator panel stays on the main laptop display.
// Uses localhost socket.io (no network latency) → smooth at 10 FPS, no WiFi
// streaming jank. Closed automatically when the main window closes.
let mirrorWindow: BrowserWindow | null = null
// BUG #14 fix (audit Remote/Mirror batch 2): store the powerSaveBlocker ID
// so we can stop it on app quit (see createWindow's 'closed' handler + the
// app 'before-quit' event). Previously the ID was discarded → the display-
// sleep blocker leaked past app shutdown on Windows.
let powerSaveBlockerId: number | null = null
const MAX_LOAD_RETRIES = 5
const LOAD_RETRY_DELAY = 1500 // ms
let currentRetryCount = 0

function createWindow() {
  const iconPath = getResourcePath('public/logo.svg')
  // Fallback to .ico on Windows
  const finalIconPath = process.platform === 'win32'
    ? (fs.existsSync(getResourcePath('public/icon.ico')) ? getResourcePath('public/icon.ico') : iconPath)
    : iconPath

  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 800,
    minHeight: 600,
    title: 'SAATIRIL — Sistem Auto Track Input, Raw Into Live',
    icon: finalIconPath,
    backgroundColor: '#1a0b2e',
    show: false, // Show when ready
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      webSecurity: false, // Allow camera access on HTTP LAN
    },
  })

  // Load Next.js app
  const socketPortParam = `socketPort=${socketPort}`
  const loadUrl = isDev
    ? `http://localhost:3000/?${socketPortParam}`
    : `http://localhost:${httpPort}/?${socketPortParam}`

  console.log(`[SAATIRIL] Loading URL: ${loadUrl}`)
  currentRetryCount = 0
  mainWindow.loadURL(loadUrl)

  // Show window when ready — close splash first
  // CRITICAL: Show main window ASAP — minimize splash time to prevent
  // users from confusing splash with license page
  mainWindow.once('ready-to-show', () => {
    // Close splash and show main window
    if (splashWindow && !splashWindow.isDestroyed()) {
      splashWindow.close()
    }
    mainWindow?.show()
    mainWindow?.focus()
  })

  // Open DevTools in dev mode
  if (isDev) {
    mainWindow.webContents.openDevTools()
  }

  // Handle page load failure with retry logic
  mainWindow.webContents.on('did-fail-load', (_event, errorCode, errorDesc, validatedURL) => {
    console.error(`[SAATIRIL] Page load failed: ${errorCode} - ${errorDesc} (URL: ${validatedURL})`)
    // Only retry if it's a connection error (server not ready yet)
    if (errorCode === -102 || errorCode === -101 || errorCode === -105) {
      // ERR_CONNECTION_REFUSED, ERR_CONNECTION_RESET, ERR_NAME_NOT_RESOLVED
      currentRetryCount++
      if (currentRetryCount <= MAX_LOAD_RETRIES) {
        console.log(`[SAATIRIL] Retrying page load in ${LOAD_RETRY_DELAY}ms (attempt ${currentRetryCount}/${MAX_LOAD_RETRIES})...`)
        setTimeout(() => {
          if (mainWindow && !mainWindow.isDestroyed()) {
            mainWindow.loadURL(loadUrl)
          }
        }, LOAD_RETRY_DELAY)
      } else {
        console.error(`[SAATIRIL] Failed to load app after ${MAX_LOAD_RETRIES} retries. Giving up.`)
      }
    }
  })

  // Open external links + /admin-ble in system browser (Chrome/Edge).
  // CRITICAL: Electron doesn't support Web Bluetooth. When the renderer
  // calls window.open('/admin-ble'), we redirect to shell.openExternal
  // so it opens in Chrome/Edge which DOES support Web Bluetooth.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    console.log(`[SAATIRIL] setWindowOpenHandler: ${url}`)
    // Always open in external browser — this handles /admin-ble and any other URL
    if (url.startsWith('http://') || url.startsWith('https://')) {
      shell.openExternal(url).catch((err: any) => {
        console.error(`[SAATIRIL] Failed to open external URL ${url}: ${err.message}`)
      })
    }
    return { action: 'deny' }
  })

  // Anti-minimize: warn user when trying to minimize.
  // CRITICAL: restore+show+focus SYNCHRONOUSLY at the START of the handler —
  // BEFORE the async dialog. On Windows, the OS minimize button can minimize
  // the window BEFORE e.preventDefault() takes effect (an Electron race). If
  // we restore only inside the dialog's async .then(), the window stays
  // minimized while the dialog is open AND may get re-minimized after restore.
  // Restoring synchronously brings the window back ASAP. The dialog then asks;
  // if the user picks "Minimize Saja", we re-minimize; if "Tetap Buka", nothing
  // more (already restored) — so the app NEVER stays minimized against the
  // user's choice.
  function handleMinimize(e: Electron.Event) {
    e.preventDefault()
    // Defer the restore slightly (setImmediate) so the minimize event fully
    // processes — calling restore()/show() SYNCHRONOUSLY can conflict with the
    // OS minimize on Windows and cause a BLANK screen (the GPU re-renders
    // blank). Only restore if the window IS minimized (if e.preventDefault()
    // worked, the window is still visible — calling restore/show on a visible
    // window causes a blank flash on Windows). Don't call show() — restore()
    // is enough to un-minimize, and show() on a visible window is what caused
    // the blank screen the user reported.
    setImmediate(() => {
      if (mainWindow && !mainWindow.isDestroyed() && mainWindow.isMinimized()) {
        mainWindow.restore()
        mainWindow.focus()
      }
    })
    // @ts-ignore — mainWindow is checked for null before this
    dialog.showMessageBox(mainWindow as any, {
      type: 'warning', title: 'Jangan Minimize!',
      message: 'Aplikasi Saatiril sedang berjalan!',
      detail: 'Meminimize dapat mengganggu prosesi.\n\nLanjutkan minimize?',
      buttons: ['Tetap Buka', 'Minimize Saja'], defaultId: 0, cancelId: 0,
    }).then(({ response }) => {
      if (response === 1) {
        // Minimize Saja — re-minimize + re-attach handler on restore
        mainWindow?.removeAllListeners('minimize')
        mainWindow?.minimize()
        // @ts-ignore — minimize event exists at runtime
        mainWindow?.once('restore', () => { if (mainWindow) mainWindow.on('minimize', handleMinimize) })
      }
      // else: Tetap Buka — if the OS minimized (race), the setImmediate restore
      // above already brought it back. If e.preventDefault() worked, it was
      // never minimized. No blank screen (we never called show() on a visible
      // window).
    })
  }
  // @ts-ignore — minimize event exists at runtime
  mainWindow.on('minimize', handleMinimize)
  // Prevent screen sleep
  // powerSaveBlocker imported at top
  // BUG #14 fix (audit Remote/Mirror batch 2): store the powerSaveBlocker ID
  // so we can stop it on app quit. Previously the ID was discarded → the
  // blocker leaked past app shutdown on Windows (display stayed awake even
  // after Saatiril closed). Now we stop it explicitly in the 'closed'
  // handler + the app 'before-quit' event (whichever fires first).
  powerSaveBlockerId = powerSaveBlocker.start('prevent-display-sleep')

  mainWindow.on('closed', () => {
    mainWindow = null
    // BUG #14 fix: stop the powerSaveBlocker + release the display-sleep lock.
    if (powerSaveBlockerId !== null && powerSaveBlocker.isStarted(powerSaveBlockerId)) {
      try { powerSaveBlocker.stop(powerSaveBlockerId) } catch (e) {}
      powerSaveBlockerId = null
    }
    // Also close the Live Mirror projector window (if open) — it doesn't
    // make sense to keep the projector view alive without the operator's
    // main window.
    if (mirrorWindow && !mirrorWindow.isDestroyed()) {
      try { mirrorWindow.close() } catch (e) {}
    }
    mirrorWindow = null
  })
}

// ─── App lifecycle ─────────────────────────────────────────────────────────
app.whenReady().then(async () => {
  // Add firewall rules for incoming connections (uses existing addFirewallRule function)
  addFirewallRule(3000)
  addFirewallRule(3003)
  const startTime = Date.now()
  console.log('[SAATIRIL] ═══════════════════════════════════════════════════════════')
  console.log('[SAATIRIL]  SAATIRIL Electron App Starting...')
  console.log(`[SAATIRIL]  Version: ${app.getVersion()}`)
  console.log(`[SAATIRIL]  isDev: ${isDev}`)
  console.log(`[SAATIRIL]  isPackaged: ${app.isPackaged}`)
  console.log(`[SAATIRIL]  __dirname: ${__dirname}`)
  console.log(`[SAATIRIL]  resourcesPath: ${process.resourcesPath}`)
  console.log('[SAATIRIL] ═══════════════════════════════════════════════════════════')

  // Register IPC handlers
  registerIpcHandlers()

  // ── Add Windows Firewall rules for ports 3000 and 3003 ──
  // This allows LAN clients (MC/Operator) to connect from other devices.
  // Without this, Windows Firewall blocks incoming connections by default.
  addFirewallRule(DEFAULT_HTTP_PORT)
  addFirewallRule(DEFAULT_SOCKET_PORT)

  // Show splash screen immediately — gives visual feedback during startup
  createSplashWindow()

  // Start servers (only in production; in dev they run separately)
  if (!isDev) {
    const outDir = getResourcePath('out')
    console.log(`[SAATIRIL] Looking for static export at: ${outDir}`)

    if (fs.existsSync(outDir)) {
      const fileCount = fs.readdirSync(outDir).length
      console.log(`[SAATIRIL] Static export found (${fileCount} top-level items)`)
    } else {
      console.error('[SAATIRIL] ❌ No static export found at', outDir)
    }

    // Start both servers IN PARALLEL instead of sequentially
    // This saves 2-5 seconds of startup time
    // Also pre-warm GitHub Releases cache in the background so APK info
    // is available by the time the admin dashboard loads
    try {
      await Promise.all([
        fs.existsSync(outDir)
          ? startStaticServer(outDir).then(() => { console.log('[SAATIRIL] HTTP server is ready.') })
          : Promise.reject(new Error('No out/ directory')),
        startSocketServer().then(() => { console.log('[SAATIRIL] Socket server is ready.') }),
      ])
      // Pre-warm GitHub Releases cache (non-blocking — don't wait for it)
      fetchLatestReleaseInfo().then(() => {
        console.log('[SAATIRIL] GitHub Releases cache pre-warmed.')
      }).catch(() => {
        console.warn('[SAATIRIL] GitHub Releases cache pre-warm failed (will retry on first request).')
      })
    } catch (err: any) {
      console.error('[SAATIRIL] Server startup error:', err.message)
    }
  }

  // Create main window (servers are now ready, page will load successfully)
  createWindow()

  const elapsed = Date.now() - startTime
  console.log(`[SAATIRIL] Startup completed in ${elapsed}ms`)

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

app.on('before-quit', () => {
  console.log('[SAATIRIL] App shutting down...')
  httpServer?.close()
  socketServer?.close()
  // BUG #14 fix (audit Remote/Mirror batch 2): stop the powerSaveBlocker on
  // quit so the display-sleep lock is released. The 'closed' handler on the
  // main window already does this, but if the user quits via Ctrl+C / tray
  // menu / app.quit() without first closing the main window, 'closed' may
  // not fire before the process exits. This is the catch-all safety net.
  if (powerSaveBlockerId !== null && powerSaveBlocker.isStarted(powerSaveBlockerId)) {
    try { powerSaveBlocker.stop(powerSaveBlockerId) } catch (e) {}
    powerSaveBlockerId = null
  }
})
