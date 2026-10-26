'use client'

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Camera,
  CheckCircle2,
  Clock,
  Loader2,

  Search,
  User,
  Video,
  VideoOff,
  Aperture,
  Frame,
  List,
  X,
  SwitchCamera,
  Brain,
  Sparkles,
  Zap,
  Timer,
  Hand,
  FlipHorizontal2,
  Grid3x3,
  Maximize,
  Minimize,
  Cast,
  Copy,
  Check,
  Monitor,
  PowerOff,
} from 'lucide-react'
import {
  useSaatirilStore,
  type Student,
  type StudentStatus,
  type PhotoHistoryItem,
  type CameraMode,
  mergeDatabases,
  stripFrameForSync,
  preserveFrameOnSync,
  preservePhotoHistoryOnSync,
  mergeCaptureVersions,
  isPhotoshootMode,
  isDualPhotoshootMode,
  channelCount,
} from '@/store/use-saatiril-store'
import { emitLocal, onLocal, offLocal, getSocket } from '@/lib/socket'
import { useIsMobile } from '@/hooks/use-mobile'
import { useAIDetection, type AIMomentEvent } from '@/hooks/use-ai-detection'
import { usePalmDetection } from '@/hooks/use-palm-detection'
import { useToast } from '@/hooks/use-toast'
import { ResizablePanelGroup, ResizablePanel, ResizableHandle } from '@/components/ui/resizable'

// ─── Theme tokens ───────────────────────────────────────────────────────────
const THEME = {
  bg: '#1a0b2e',
  panel: '#2a164a',
  card: '#3b2263',
  border: '#533485',
  gold: '#d4af37',
  muted: '#c4b5fd',
  red: '#ef4444',
} as const

// ─── Shutter mode types ────────────────────────────────────────────────────
type ShutterMode = 'manual' | 'timer-3' | 'timer-5' | 'timer-10' | 'ai'

const SHUTTER_MODES: { id: ShutterMode; label: string; shortLabel: string; icon: React.ReactNode; modesAllowed?: CameraMode[] }[] = [
  { id: 'manual', label: 'Manual', shortLabel: 'Manual', icon: <Camera className="size-3" /> },
  { id: 'timer-3', label: 'Timer 3 detik', shortLabel: '3s', icon: <Timer className="size-3" /> },
  { id: 'timer-5', label: 'Timer 5 detik', shortLabel: '5s', icon: <Timer className="size-3" /> },
  { id: 'timer-10', label: 'Timer 10 detik', shortLabel: '10s', icon: <Timer className="size-3" /> },
  { id: 'ai', label: 'AI Pintar', shortLabel: 'AI', icon: <Brain className="size-3" />, modesAllowed: ['single', 'dual'] },
]

function getTimerDuration(mode: ShutterMode): number {
  switch (mode) {
    case 'timer-3': return 3
    case 'timer-5': return 5
    case 'timer-10': return 10
    default: return 0
  }
}

function isTimerMode(mode: ShutterMode): boolean {
  return mode === 'timer-3' || mode === 'timer-5' || mode === 'timer-10'
}

// ─── Filter preset map ──────────────────────────────────────────────────────
const PRESET_FILTERS: Record<string, string> = {
  original: 'none',
  studio: 'brightness(1.1) contrast(1.05) saturate(1.1)',
  cinematic: 'sepia(0.15) contrast(1.1) brightness(0.95) saturate(1.3)',
  pro: 'contrast(1.25) brightness(1.05) saturate(1.15)',
  vivid: 'brightness(1.08) contrast(1.12) saturate(1.45) hue-rotate(5deg)',
  softPortrait: 'brightness(1.12) contrast(0.92) saturate(1.08) sepia(0.08)',
  classicFilm: 'brightness(1.02) contrast(1.15) saturate(0.85) sepia(0.2)',
  dramaticBW: 'brightness(1.05) contrast(1.35) saturate(0) grayscale(1)',
  warmSunset: 'brightness(1.06) contrast(1.08) saturate(1.3) sepia(0.18) hue-rotate(-10deg)',
}

// ─── Ratio parser ───────────────────────────────────────────────────────────
function parseRatio(ratioStr: string): number {
  const parts = ratioStr.split(':')
  if (parts.length === 2) {
    const w = parseFloat(parts[0])
    const h = parseFloat(parts[1])
    if (w > 0 && h > 0) return w / h
  }
  return 4 / 3
}

// ─── Helpers ────────────────────────────────────────────────────────────────
// DEFENSIVE: nama/nim can be undefined if a photoHistory entry got corrupted
// during reset+retake cycles. Never let .trim() crash the whole app.
// (matches Android FilenameUtils.kt after fix in d07fdeb — sanitize + cap
//  nama at 40 chars and fall back to studentId → clock-hex when both sanitize
//  empty so empty Excel rows no longer collide on filenames like "___1_Toga.jpg".)
const MAX_NAMA_LENGTH = 40

function sanitizeNama(nama: string | undefined | null): string {
  return (nama ?? '').trim().replace(/\s+/g, '_').replace(/[^a-zA-Z0-9_]/g, '').slice(0, MAX_NAMA_LENGTH)
}

function sanitizeNim(nim: string | undefined | null): string {
  return (nim ?? '').toString().trim().replace(/[^a-zA-Z0-9_-]/g, '')
}

/** Build the "NIM_Nama" identity part of a filename.
 *  - both present → "NIM_Nama"
 *  - only one present → that part alone (no dangling "_")
 *  - both empty → first 8 chars of studentId, else a clock-derived hex.
 */
function buildIdentityPart(nim: string | undefined, nama: string | undefined, studentId?: string): string {
  const nimPart = sanitizeNim(nim)
  const namaPart = sanitizeNama(nama)
  if (nimPart && namaPart) return `${nimPart}_${namaPart}`
  if (nimPart) return nimPart
  if (namaPart) return namaPart
  const idPart = sanitizeNim(studentId).slice(0, 8)
  return idPart || Date.now().toString(16).slice(-8)
}

/**
 * Build a versioned filename for standard mode (Toga + Ijazah).
 * version: 1 = first capture, 2+ = retake after MC reset.
 * v1 → `NIM_Nama_1_Toga.jpg` (no version suffix for backwards compat)
 * v2 → `NIM_Nama_1_Toga_v2.jpg`, v3 → `..._v3.jpg`, …
 */
function buildFilename(nim: string | undefined, nama: string | undefined, suffix: number, type: string, version: number = 1, studentId?: string): string {
  const base = `${buildIdentityPart(nim, nama, studentId)}_${suffix}_${type}`
  return version > 1 ? `${base}_v${version}.jpg` : `${base}.jpg`
}

/**
 * Build a versioned filename for photoshoot mode.
 * version: 1 = first capture, 2+ = retake after MC reset.
 * v1 → `NIM_Nama.jpg` or `NIM_Nama_Ch2.jpg` (no version suffix)
 * v2 → `NIM_Nama_v2.jpg` or `NIM_Nama_Ch2_v2.jpg`, …
 */
function buildPhotoshootFilename(nim: string | undefined, nama: string | undefined, channel: number, version: number = 1, studentId?: string): string {
  const base = buildIdentityPart(nim, nama, studentId)
  const withCh = channel > 1 ? `${base}_Ch${channel}` : base
  return version > 1 ? `${withCh}_v${version}.jpg` : `${withCh}.jpg`
}

function isActiveStatus(status: StudentStatus): boolean {
  return status.startsWith('active')
}

function getActiveChannel(status: StudentStatus): number | null {
  if (!isActiveStatus(status)) return null
  const ch = status.split('_')[1]
  return ch ? parseInt(ch, 10) : null
}

function statusLabel(status: StudentStatus): string {
  if (status === 'pending') return 'Menunggu'
  if (status === 'sent') return 'Dikirim'
  if (status === 'done') return 'Selesai'
  const ch = getActiveChannel(status)
  return ch != null ? `Foto Ch.${ch}` : 'Aktif'
}

function fitAspectRatio(
  availW: number,
  availH: number,
  ratio: number,
): { width: number; height: number } {
  const hFromW = availW / ratio
  if (hFromW <= availH) {
    return { width: availW, height: hFromW }
  }
  const wFromH = availH * ratio
  return { width: wFromH, height: availH }
}

// ─── Capture state machine ──────────────────────────────────────────────────
type CapturePhase = 'standby' | 'ready-1' | 'ready-2' | 'sending'

// ─── Video device info ──────────────────────────────────────────────────────
interface VideoDeviceInfo {
  deviceId: string
  label: string
}

// ─── Socket event data shapes ───────────────────────────────────────────────
interface McCallData {
  student: Student
  channel: number
}

interface SyncDbData {
  project: {
    id: string
    name: string
    config: {
      mode: CameraMode
      ratio: string
      preset: string
      targetFolder: string
      frame: string | null
    }
    database: Student[]
    photoHistory: PhotoHistoryItem[]
  }
}

// ─── Component ──────────────────────────────────────────────────────────────

// STABLE empty array for the per-channel opCapturedPhotos selector fallback.
// MUST be module-level (not `?? []` inline) so the selector returns the SAME
// reference every call. `useSyncExternalStore` (Zustand v4) compares snapshots
// via Object.is — a new `[]` each call triggers infinite re-render → React
// error #185 "Maximum update depth exceeded". This constant is never mutated.
const EMPTY_PHOTOS: string[] = []

export interface OperatorPanelProps {
  isAppFullscreen?: boolean
  onToggleAppFullscreen?: () => void
  /**
   * Whether the camera should be active (getUserMedia held). When false, the
   * camera tracks are released so ANOTHER app (e.g. the Operator Electron App
   * on the same laptop) can use the camera. The admin's portable.exe passes
   * cameraActive={activeView === 'live'} so its (always-mounted) OperatorPanel
   * only holds the camera when the admin is actually viewing the Operator —
   * freeing it for the Operator App while the admin is on the Admin/MC tab.
   * The Operator Electron App uses the default (true) since it's the main view.
   * Socket listeners + state (mcCallBuffer etc.) stay active regardless.
   */
  cameraActive?: boolean
  /**
   * Per-instance channel override. In dual-photo / dual-photoshoot mode, the
   * admin's live view renders 2 OperatorPanels side by side (50/50): the
   * left one with channel={1} (Jalur 1, camera 1) + the right one with
   * channel={2} (Jalur 2, camera 2). This overrides the store's myChannel
   * for ALL this instance's logic — channelStudents, opCurrentTarget,
   * handleMcCall (accepts only its channel's MC_CALL), camera
   * selectedDeviceId (auto-distributed per-channel), finalizeCapture emits
   * (STUDENT_DONE + PHOTOS_SAVED carry this channel). When omitted, the
   * store's myChannel is used (single-channel / default behaviour).
   */
  channel?: number
}

export function OperatorPanel({ isAppFullscreen = false, onToggleAppFullscreen, cameraActive = true, channel }: OperatorPanelProps) {
  const isMobile = useIsMobile()
  const { toast } = useToast()

  // ── Store ────────────────────────────────────────────────────────────────
  const currentProject = useSaatirilStore((s) => s.currentProject)
  // Boolean mirror — used as a dep in the camera useEffect so the camera
  // re-starts when the project loads (the <video> element isn't rendered while
  // !currentProject — the 'Belum ada proyek aktif' screen shows instead, so
  // videoRef.current is null + the stream can't attach). When the project
  // loads, hasProject flips false→true → the useEffect re-runs → startCamera
  // re-obtains the stream + attaches it to the now-rendered <video> element.
  // Without this, the camera showed black in wisuda mode (operator started
  // before the project loaded → stream obtained but unattached → video element
  // rendered later but the useEffect didn't re-run → stream stayed unattached).
  const hasProject = !!currentProject
  const storeChannel = useSaatirilStore((s) => s.myChannel)
  // Per-instance channel override (see OperatorPanelProps.channel). When
  // provided, this instance uses the override for ALL its logic. When
  // omitted, fall back to the store's myChannel (single-channel default).
  const myChannel = channel ?? storeChannel
  const setMyChannel = useSaatirilStore((s) => s.setMyChannel)
  // ── Per-channel operator state ──────────────────────────────────────
  // The store keys opCurrentTargets / opCapturedPhotosByChannel by channel so
  // that 2 OperatorPanel instances in the dual 50/50 layout (channel 1 +
  // channel 2) each get their OWN target + photos — when channel 1 captures,
  // channel 2's button + capturePhase stay unaffected (was the user's bug:
  // channel 2's foto button activated + followed channel 1's captures).
  const opCurrentTarget = useSaatirilStore((s) => s.opCurrentTargets[myChannel] ?? null)
  const opCapturedPhotos = useSaatirilStore((s) => s.opCapturedPhotosByChannel[myChannel] ?? EMPTY_PHOTOS)
  const _setOpCurrentTarget = useSaatirilStore((s) => s.setOpCurrentTarget)
  const _addOpCapturedPhoto = useSaatirilStore((s) => s.addOpCapturedPhoto)
  const _resetOpState = useSaatirilStore((s) => s.resetOpState)
  // Wrappers that auto-pass myChannel so existing call sites stay unchanged
  // (setOpCurrentTarget(target) / addOpCapturedPhoto(photo) / resetOpState()
  // all route to THIS channel's slice). useCallback keeps identity stable
  // across renders unless myChannel changes (then dependent useEffects
  // correctly re-sync to the new channel's slice).
  const setOpCurrentTarget = useCallback(
    (target: Student | null) => _setOpCurrentTarget(target, myChannel),
    [_setOpCurrentTarget, myChannel],
  )
  const addOpCapturedPhoto = useCallback(
    (photo: string) => _addOpCapturedPhoto(photo, myChannel),
    [_addOpCapturedPhoto, myChannel],
  )
  const resetOpState = useCallback(
    (channel?: number) => _resetOpState(channel ?? myChannel),
    [_resetOpState, myChannel],
  )
  const updateStudentStatus = useSaatirilStore((s) => s.updateStudentStatus)
  const updateCurrentProject = useSaatirilStore((s) => s.updateCurrentProject)
  const saveProjectsToStorageNow = useSaatirilStore((s) => s.saveProjectsToStorageNow)

  // ── Local state ──────────────────────────────────────────────────────────
  const [videoDevices, setVideoDevices] = useState<VideoDeviceInfo[]>([])
  const [selectedDeviceId, setSelectedDeviceId] = useState<string>('')
  const [cameraAvailable, setCameraAvailable] = useState(false)
  const [flashVisible, setFlashVisible] = useState(false)
  const [sending, setSending] = useState(false)
  const [cameraDims, setCameraDims] = useState({ width: 0, height: 0 })
  const [showQueueOnMobile, setShowQueueOnMobile] = useState(false)
  const [opSearchQuery, setOpSearchQuery] = useState('')
  // Buffer for MC_CALL events that arrive before the database updates via SYNC_DB
  const [mcCallBuffer, setMcCallBuffer] = useState<Student[]>([])
  const isCapturingRef = useRef(false)

  // ── Panel visibility state (toggleable sections) ────────────────────────
  const [showShutterPanel, setShowShutterPanel] = useState(false)
  const [showGridlinePanel, setShowGridlinePanel] = useState(false)
  const [showQueuePanel, setShowQueuePanel] = useState(true)
  const [monitorLocked, setMonitorLocked] = useState(false)

  // ── Gridline overlay state ─────────────────────────────────────────────────
  const [gridlineEnabled, setGridlineEnabled] = useState(true)
  const [gridlineType, setGridlineType] = useState<'thirds' | 'quarters' | 'crosshair' | 'diagonal'>('thirds')
  const [gridlineThickness, setGridlineThickness] = useState<1 | 2 | 3>(1)
  const [gridlineColor, setGridlineColor] = useState<'white' | 'yellow' | 'red' | 'cyan' | 'green'>('white')

  // ── Shutter mode state ───────────────────────────────────────────────────
  const [shutterMode, setShutterMode] = useState<ShutterMode>('manual')
  // ── Camera mirror (flip horizontal) ──
  // Toggles transform: scaleX(-1) on the <video> element so the live preview
  // is mirrored (useful for front-facing cameras OR when the operator prefers
  // a mirrored view). Does NOT affect the captured photo — the canvas draw
  // in handleCapture uses the un-mirrored video frame (so photos are saved
  // correctly oriented).
  const [mirror, setMirror] = useState(false)
  // ── Live Mirror (broadcast camera to /mirror display) ──────────────────
  // When ON, the operator's camera frames are streamed at ~10 FPS to the
  // /mirror page (opened on a separate device or 2nd monitor) so participants
  // can see themselves (mirror) + countdown + flash without disturbing the
  // operator's panel. Emits lan-message events MIRROR_FRAME / MIRROR_COUNTDOWN
  // / MIRROR_CAPTURE on this channel — the /mirror page filters by channel.
  const [liveMirror, setLiveMirror] = useState(false)
  const mirrorIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const liveMirrorChannelRef = useRef(myChannel)
  useEffect(() => { liveMirrorChannelRef.current = myChannel }, [myChannel])
  // Ref mirror of liveMirror so finalizeCapture (which has a useCallback with
  // a fixed deps array) can read the current value without going stale.
  const liveMirrorRef = useRef(false)
  useEffect(() => { liveMirrorRef.current = liveMirror }, [liveMirror])

  // ── LAN info (Electron IPC) + mirror URL state ─────────────────────────
  // When Live Mirror is ON, we build a copy-paste-ready URL with the laptop's
  // LAN IP so the operator can hand it to participants without manually
  // figuring out the IP. Falls back to window.location.hostname in browser
  // mode (non-Electron).
  const [lanInfo, setLanInfo] = useState<{ httpPort: number; socketPort: number; ips: { name: string; address: string }[] } | null>(null)
  const [mirrorUrl, setMirrorUrl] = useState<string>('')
  const [mirrorCopied, setMirrorCopied] = useState(false)

  useEffect(() => {
    const api = window.saatirilAPI
    if (api?.isElectron && api.getLanInfo) {
      api.getLanInfo().then((info: any) => {
        setLanInfo(info)
        console.log('[SAATIRIL OP] LAN info for mirror:', info)
      }).catch(() => {})
    }
  }, [])

  // Build the /mirror URL based on LAN info (or fallback to window.location).
  // Returns the full URL string including channel + socketPort query params.
  const buildMirrorUrl = useCallback(async () => {
    const api = window.saatirilAPI
    const isElectron = api?.isElectron
    const path = `/mirror`
    const channelParam = `channel=${myChannel}`

    if (isElectron) {
      try {
        const info = lanInfo || (await api.getLanInfo())
        const ips = info.ips
        const lanIP = ips.length > 0 ? ips[0].address : 'localhost'
        const url = `http://${lanIP}:${info.httpPort}${path}?${channelParam}&socketPort=${info.socketPort}`
        return url
      } catch {
        const hostname = window.location.hostname || 'localhost'
        const params = new URLSearchParams(window.location.search)
        const socketPort = params.get('socketPort') || '3003'
        return `http://${hostname}:81${path}?${channelParam}&socketPort=${socketPort}`
      }
    } else {
      // Browser mode — use window.location.origin (or detect LAN IP via WebRTC
      // if on localhost, mirrors the admin-dashboard.tsx copyLink pattern).
      const socketPort = new URLSearchParams(window.location.search).get('socketPort') || '3003'
      let origin = window.location.origin
      const hostname = window.location.hostname
      if (hostname === 'localhost' || hostname === '127.0.0.1') {
        try {
          const pc = new RTCPeerConnection({ iceServers: [] })
          pc.createDataChannel('')
          const offer = await pc.createOffer()
          await pc.setLocalDescription(offer)
          const lanIP = await new Promise<string | null>((resolve) => {
            const timeout = setTimeout(() => { pc.close(); resolve(null) }, 3000)
            pc.onicecandidate = (e) => {
              if (!e.candidate) return
              const parts = e.candidate.candidate.split(' ')
              const ip = parts[4]
              if (ip && /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(ip) && !ip.startsWith('0.') && ip !== '0.0.0.0') {
                clearTimeout(timeout)
                pc.close()
                resolve(ip)
              }
            }
          })
          if (lanIP) {
            const port = window.location.port || '81'
            origin = `http://${lanIP}:${port}`
          }
        } catch {
          // WebRTC not available — keep localhost URL
        }
      }
      return `${origin}${path}?${channelParam}&socketPort=${socketPort}`
    }
  }, [lanInfo, myChannel])

  // Rebuild the URL whenever Live Mirror is toggled ON, channel changes, or
  // LAN info arrives (Electron IPC is async — lanInfo may load after the
  // initial toggle). Note: we don't clear mirrorUrl when liveMirror goes
  // false — the JSX condition `liveMirror && mirrorUrl` hides the input.
  useEffect(() => {
    if (!liveMirror) return
    let cancelled = false
    buildMirrorUrl().then((url) => {
      if (!cancelled) setMirrorUrl(url)
    })
    return () => { cancelled = true }
  }, [liveMirror, buildMirrorUrl])

  // Copy the mirror URL to clipboard with toast + visual "Copied ✓" feedback.
  const copyMirrorLink = useCallback(async () => {
    if (!mirrorUrl) return
    try {
      if (navigator.clipboard) {
        await navigator.clipboard.writeText(mirrorUrl)
      } else {
        const textarea = document.createElement('textarea')
        textarea.value = mirrorUrl
        document.body.appendChild(textarea)
        textarea.select()
        document.execCommand('copy')
        document.body.removeChild(textarea)
      }
      setMirrorCopied(true)
      setTimeout(() => setMirrorCopied(false), 2000)
      toast({
        title: 'Link Live Mirror disalin!',
        description: `Salurkan ke perangkat peserta — ${mirrorUrl}`,
      })
    } catch {
      toast({
        title: 'Gagal menyalin',
        description: 'Tidak dapat menyalin link. Salin manual dari kolom URL.',
        variant: 'destructive',
      })
    }
  }, [mirrorUrl, toast])

  // ── Open Live Mirror on the external display (projector via HDMI) ──────
  // Calls the Electron IPC handler that creates a separate BrowserWindow on
  // the external display + loads the /mirror URL via localhost (no network
  // latency → smooth, no WiFi streaming jank).
  //
  // User setup:
  //   1. Connect projector via HDMI cable to the laptop.
  //   2. Windows + P → "Extend" (NOT Duplicate — Duplicate shows the full
  //      Operator panel on the projector).
  //   3. Click this button → Electron opens the mirror window on the
  //      projector, fullscreen, frameless.
  //
  // In browser mode (non-Electron), the button is hidden — there's no
  // Electron IPC to open a BrowserWindow. The user would use the URL
  // input + Copy button + manually open in a browser on the projector
  // laptop.
  const isElectronRuntime = typeof window !== 'undefined' && (window as any).saatirilAPI?.isElectron === true
  // Tracks whether the mirror projector BrowserWindow is open → use the
  // IPC fast path (Blob directly, no base64/socket.io) for smooth frames.
  const [useIpcForMirror, setUseIpcForMirror] = useState(false)
  const useIpcForMirrorRef = useRef(false)
  useEffect(() => { useIpcForMirrorRef.current = useIpcForMirror }, [useIpcForMirror])

  // Poll every 2s to check if the mirror window is open. If yes, use IPC.
  // If no (window closed), fall back to socket.io. This auto-detects when
  // the user clicks 'Buka di Projector' (window opens → IPC) or closes the
  // window (window closes → socket.io).
  useEffect(() => {
    if (!isElectronRuntime) return
    const api = (window as any).saatirilAPI
    if (!api?.isMirrorWindowOpen) return
    const check = async () => {
      try {
        const open = await api.isMirrorWindowOpen()
        setUseIpcForMirror(!!open)
      } catch {}
    }
    check() // immediate check on mount
    const interval = setInterval(check, 2000)
    return () => clearInterval(interval)
  }, [isElectronRuntime])
  const [projectorOpening, setProjectorOpening] = useState(false)
  const handleOpenOnProjector = useCallback(async () => {
    const api = (window as any).saatirilAPI
    if (!api?.isElectron || !api.openMirrorOnProjector) {
      toast({
        title: 'Tidak tersedia',
        description: 'Buka di Projector hanya tersedia di aplikasi Electron (portable.exe). Untuk browser, gunakan tombol Copy + buka URL di browser lain di laptop/projector.',
        variant: 'destructive',
      })
      return
    }
    setProjectorOpening(true)
    try {
      const result = await api.openMirrorOnProjector(myChannel)
      if (result.success) {
        if (result.reason === 'ALREADY_OPEN') {
          toast({
            title: 'Mirror sudah terbuka di projector',
            description: `Ch.${myChannel} — window sudah ada di external display. Difokuskan.`,
          })
        } else {
          toast({
            title: '✓ Live Mirror dibuka di projector',
            description: `Ch.${myChannel} — tampilan kamera live + countdown sudah di external display (HDMI). Operator panel tetap di laptop ini.`,
          })
        }
      } else if (result.reason === 'NO_EXTERNAL_DISPLAY') {
        toast({
          title: '⚠ Tidak ada layar eksternal',
          description: 'Hubungkan projector via HDMI + set Windows ke mode Extend (Win+P → Extend), lalu klik tombol ini lagi.',
          variant: 'destructive',
        })
      } else {
        toast({
          title: 'Gagal membuka di projector',
          description: result.reason,
          variant: 'destructive',
        })
      }
    } catch (err: any) {
      toast({
        title: 'Error',
        description: err?.message || 'unknown',
        variant: 'destructive',
      })
    } finally {
      setProjectorOpening(false)
    }
  }, [myChannel, toast])

  // ── Close Projector handler ─────────────────────────────────────────────
  // Closes the mirror projector BrowserWindow via Electron IPC. Also used
  // by the auto-close useEffect (when project closes or component unmounts).
  const [projectorClosing, setProjectorClosing] = useState(false)
  const handleCloseProjector = useCallback(async () => {
    const api = (window as any).saatirilAPI
    if (!api?.isElectron || !api.closeMirrorProjector) return
    setProjectorClosing(true)
    try {
      await api.closeMirrorProjector()
      toast({
        title: 'Projector ditutup',
        description: `Window Live Mirror di external display sudah ditutup.`,
      })
    } catch (err: any) {
      // Silent fail — the window might already be closed.
    } finally {
      setProjectorClosing(false)
    }
  }, [toast])

  // ── Auto-close projector when project closes ────────────────────────────
  // When the user goes back to the home/project-setup screen (currentProject
  // becomes null), the operator panel keeps rendering (it shows "Memuat
  // proyek..."). But the projector window should close — otherwise it stays
  // open showing the last frame forever. This useEffect watches currentProject
  // + closes the projector window when the project closes.
  useEffect(() => {
    if (!currentProject) {
      const api = (window as any).saatirilAPI
      if (api?.isElectron && api.closeMirrorProjector) {
        api.closeMirrorProjector().catch(() => {})
      }
      // Also turn off Live Mirror — no point streaming frames when there's
      // no project + no camera target.
      if (liveMirrorRef.current) {
        setLiveMirror(false)
      }
    }
  }, [currentProject])

  // ── Auto-close projector on component unmount ───────────────────────────
  // Safety net: if the operator panel unmounts for any reason (e.g. user
  // switches role, app closes), close the projector window too.
  useEffect(() => {
    return () => {
      const api = (window as any).saatirilAPI
      if (api?.isElectron && api.closeMirrorProjector) {
        api.closeMirrorProjector().catch(() => {})
      }
    }
  }, [])

  // Palm trigger: when ON, showing an open palm to the camera triggers the
  // selected shutter mode (manual → instant photo, timer → starts countdown).
  // This is a hands-free trigger, NOT a shutter mode itself.
  const [palmTriggerEnabled, setPalmTriggerEnabled] = useState(false)
  const [timerCountdown, setTimerCountdown] = useState<number>(0)
  const timerIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const timerActiveRef = useRef(false)

  // ── AI auto-capture ──────────────────────────────────────────────────────
  const ai = useAIDetection()

  // ── Palm detection (selfie-style shutter) ────────────────────────────────
  const palm = usePalmDetection()

  // ── Refs ─────────────────────────────────────────────────────────────────
  const videoRef = useRef<HTMLVideoElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const flashRef = useRef<HTMLDivElement>(null)
  const activeRowRef = useRef<HTMLDivElement>(null)
  const nextRowRef = useRef<HTMLDivElement>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const selectedDeviceRef = useRef<string>('')
  // BUG #7 fix (audit Remote/Mirror batch 2): generation token so in-flight
  // startCamera can detect that the camera was released during getUserMedia's
  // await and bail out before assigning streamRef. Incremented each time the
  // camera effect starts; cleanup sets it to -1 to invalidate any in-flight
  // call.
  const cameraGenRef = useRef<number>(0)
  const frameImgRef = useRef<HTMLImageElement | null>(null)
  const cameraZoneRef = useRef<HTMLDivElement>(null)

  // ── Derived config ───────────────────────────────────────────────────────
  const config = currentProject?.config
  const aspectRatio = config?.ratio ? parseRatio(config.ratio) : 4 / 3
  const cssFilter = config?.preset ? PRESET_FILTERS[config.preset] ?? 'none' : 'none'
  const frameData = useMemo(() => {
    if (!config?.frame) return null
    if (config.frame !== '__FRAME_SAVED__') return config.frame
    // Frame was stripped for sync — try to restore from separate localStorage key.
    // This is a safety net: updateCurrentProject should have already restored it,
    // but in case of a race condition or localStorage timing issue, we try here too.
    try {
      const projectId = currentProject?.id
      if (projectId) {
        const saved = localStorage.getItem(`saatiril_frame_${projectId}`)
        if (saved) return saved
      }
    } catch {}
    return null
  }, [config?.frame, currentProject?.id])

  // ── Mirror config broadcast (filter + frame overlay) ────────────────────
  // Sends the project's CSS filter (e.g. grayscale for B&W) + frame overlay
  // data URL to the /mirror page so it can apply them via CSS (GPU-accelerated,
  // no per-frame CPU cost). This is the KEY to applying effects WITHOUT
  // lagging: the filter is applied once on the <img> element via
  // img.style.filter, then the browser's GPU handles it for every rendered
  // frame. The MIRROR_FRAME wire payload stays minimal (just the raw JPEG).
  //
  // IMPORTANT: This block MUST be after the `cssFilter` + `frameData`
  // declarations above (lines 662-677). Otherwise the deps array
  // `[cssFilter, frameData]` throws a TDZ error: "Cannot access 'e2' before
  // initialization" (e2 = minified cssFilter/frameData variable name).
  //
  // Sent when:
  // - liveMirror toggles ON (initial config send)
  // - cssFilter changes (e.g. operator switches project preset)
  // - frameData changes (e.g. operator loads a different frame)
  // - myChannel changes (rare, but covers the case)
  // - mirror client sends MIRROR_REQUEST (on its connect — gets the
  //   current config immediately)
  const sendMirrorConfig = useCallback(() => {
    if (!liveMirrorRef.current) return
    console.log('[SAATIRIL OP] sending MIRROR_CONFIG: cssFilter=', cssFilter, 'hasFrame=', !!frameData, 'deviceId=', selectedDeviceId, 'ch=', liveMirrorChannelRef.current)
    emitLocal('MIRROR_CONFIG', {
      cssFilter: cssFilter,
      frameData: frameData || null,
      // Native camera path: send the deviceId + aspectRatio + mirror so the
      // mirror window can call getUserMedia itself → native 30 FPS video
      // (no JPEG encoding/transfer). Chromium/Electron allows multiple
      // renderer processes to share the same camera.
      deviceId: selectedDeviceId || null,
      aspectRatio: aspectRatio,
      mirror: mirror,
      channel: liveMirrorChannelRef.current,
    })
  }, [cssFilter, frameData, selectedDeviceId, aspectRatio, mirror])

  // Watch config changes + emit MIRROR_CONFIG when they change (and
  // liveMirror is ON).
  useEffect(() => {
    if (!liveMirror) return
    sendMirrorConfig()
  }, [liveMirror, sendMirrorConfig])

  // Listen for MIRROR_REQUEST from mirror clients (sent on their connect)
  // → respond with the current MIRROR_CONFIG so they get the filter + frame
  // immediately, not just on the next config change.
  useEffect(() => {
    const handleMirrorRequest = (data: { channel?: number }) => {
      console.log('[SAATIRIL OP] received MIRROR_REQUEST:', data, 'my channel=', liveMirrorChannelRef.current)
      // Only respond to requests for OUR channel (the operator's channel).
      if (data.channel && data.channel !== liveMirrorChannelRef.current) return
      sendMirrorConfig()
    }
    onLocal('MIRROR_REQUEST', handleMirrorRequest)
    return () => { offLocal('MIRROR_REQUEST', handleMirrorRequest) }
  }, [sendMirrorConfig])

  // Periodic re-emit MIRROR_CONFIG every 10 seconds as a fallback — ensures
  // mirror clients always get the config within 10s of connecting, even if
  // they missed the initial MIRROR_REQUEST → MIRROR_CONFIG handshake (e.g.
  // socket reconnect, component mount timing). The cssFilter + frameData
  // are small enough (frameData is a base64 string, ~50-200KB) that sending
  // them every 10s is fine.
  useEffect(() => {
    if (!liveMirror) return
    const interval = setInterval(() => {
      sendMirrorConfig()
    }, 10000)
    return () => clearInterval(interval)
  }, [liveMirror, sendMirrorConfig])

  // ── Resize Observer: calculate camera dimensions ─────────────────────────
  useEffect(() => {
    const zone = cameraZoneRef.current
    if (!zone) return

    const updateSize = () => {
      const rect = zone.getBoundingClientRect()
      const padding = isMobile ? 8 : 16
      const availW = rect.width - padding
      const availH = rect.height - padding
      if (availW > 0 && availH > 0) {
        setCameraDims(fitAspectRatio(availW, availH, aspectRatio))
      }
    }

    updateSize()
    const observer = new ResizeObserver(updateSize)
    observer.observe(zone)
    return () => observer.disconnect()
  }, [aspectRatio, isMobile, hasProject])

  // ── Preload frame image ──────────────────────────────────────────────────
  useEffect(() => {
    if (frameData) {
      const img = new Image()
      img.crossOrigin = 'anonymous'
      img.onload = () => { frameImgRef.current = img }
      img.onerror = () => { frameImgRef.current = null }
      img.src = frameData
    } else {
      frameImgRef.current = null
    }
  }, [frameData])

  // ── Derived data ─────────────────────────────────────────────────────────
  const mode = currentProject?.config.mode ?? 'single'
  const photoshoot = isPhotoshootMode(mode)
  const dualPhotoshoot = isDualPhotoshootMode(mode)

  // AI mode is only allowed in single/dual mode
  const aiAllowed = mode === 'single' || mode === 'dual'

  // Compute effective shutter mode — if AI is selected but not allowed, fallback to manual
  const effectiveShutterMode: ShutterMode = (shutterMode === 'ai' && !aiAllowed) ? 'manual' : shutterMode

  const channelStudents = useMemo<Student[]>(() => {
    if (!currentProject) return []
    if (photoshoot) {
      // In photoshoot modes, show all students
      return currentProject.database
    }
    return currentProject.database.filter((s) => s.assignedChannel === myChannel)
  }, [currentProject, myChannel, photoshoot])

  // ── Operator queue: derived from database + MC_CALL buffer ──────────────────
  const opQueue = useMemo<Student[]>(() => {
    if (!currentProject) return []
    const alreadyPhotographed = new Set(
      currentProject.photoHistory
        .filter((h) => h.channel === myChannel)
        .map((h) => h.student.id)
    )
    const doneIds = new Set(
      currentProject.database.filter((s) => s.status === 'done').map((s) => s.id)
    )
    const dbQueueIds = new Set<string>()
    // dbItems: students to photograph = 'sent' (photoshoot) OR 'active_N'
    // (wisuda). Works in BOTH modes so the operator recovers the queue after
    // close+reopen regardless of mode. Previously dbItems filtered ONLY for
    // 'sent' (photoshoot) AND had a !photoshoot early-return → in wisuda mode
    // (where students are 'active_N') the queue was ALWAYS empty after reopen,
    // even though the DB had the active students (PROSES count was correct).
    // 'sent'/'active_N' = NOT yet photographed (photographed = 'done' = excluded
    // via doneIds).
    //
    // BUG #1 (audit-electron-side): we ALSO exclude students whose id already
    // appears in the CURRENT-CHANNEL photoHistory (`alreadyPhotographed`). This
    // prevents a fast-clicking operator from re-capturing a photoshoot student
    // before the admin's SYNC_DB (which flips status to 'done') arrives, which
    // previously created unintended v2 retake files on disk without the MC's
    // explicit reset+resend flow. The historical stale-photoHistory exclusion
    // bug (commit f96e64f) was a CROSS-CHANNEL problem; `alreadyPhotographed`
    // here is built from CURRENT-channel entries only (line 828), so this is
    // safe. In wisuda mode this is a no-op (those students are already 'done'
    // and excluded via doneIds).
    const dbItems = currentProject.database.filter(
      (s) =>
        (s.status === 'sent' || isActiveStatus(s.status)) &&
        !doneIds.has(s.id) &&
        !alreadyPhotographed.has(s.id)
    )
    dbItems.forEach((s) => dbQueueIds.add(s.id))
    const bufferItems = mcCallBuffer.filter(
      (s) => !dbQueueIds.has(s.id) && !doneIds.has(s.id) && !alreadyPhotographed.has(s.id)
    )
    return [...dbItems, ...bufferItems]
  }, [currentProject, myChannel, mcCallBuffer])

  const opSearchResults = useMemo<Student[]>(() => {
    if (!opSearchQuery.trim()) return opQueue
    const q = opSearchQuery.toLowerCase().trim()
    return opQueue.filter(
      (s) => s.nim.toLowerCase().includes(q) || s.nama.toLowerCase().includes(q)
    )
  }, [opSearchQuery, opQueue])

  const currentlyActive = useMemo<Student | null>(() => {
    if (photoshoot) {
      return opCurrentTarget
    }
    const targetStatus: StudentStatus = `active_${myChannel}`
    return channelStudents.find((s) => s.status === targetStatus) ?? null
  }, [channelStudents, myChannel, photoshoot, opCurrentTarget])

  const nextPending = useMemo<Student | null>(() => {
    return channelStudents.find((s) => s.status === 'pending') ?? null
  }, [channelStudents])

  const remainingCount = useMemo<number>(() => {
    return channelStudents.filter((s) => s.status === 'pending').length
  }, [channelStudents])

  // 4 stat pills (mirrors MC panel): ANTREAN / PROSES / SELESAI / TOTAL —
  // per-channel counts so operator + MC + admin all see the same 4 categories
  // (sinkron + simultan). ANTREAN=pending, PROSES=sent+active, SELESAI=done.
  const opAntreanCount = remainingCount
  const opProsesCount = useMemo<number>(() => {
    return channelStudents.filter((s) => s.status === 'sent' || s.status.startsWith('active_')).length
  }, [channelStudents])
  const opSelesaiCount = useMemo<number>(() => {
    return channelStudents.filter((s) => s.status === 'done').length
  }, [channelStudents])
  const opTotalCount = channelStudents.length

  const hasActiveTarget = opCurrentTarget !== null && !!opCurrentTarget.id && (opCurrentTarget.nama !== undefined || opCurrentTarget.nim !== undefined)

  const capturePhase = useMemo<CapturePhase>(() => {
    if (sending) return 'sending'
    if (!hasActiveTarget) return 'standby'
    if (photoshoot) {
      if (opCapturedPhotos.length === 0) return 'ready-1'
      return 'sending'
    }
    if (opCapturedPhotos.length === 0) return 'ready-1'
    if (opCapturedPhotos.length === 1) return 'ready-2'
    return 'standby'
  }, [sending, hasActiveTarget, opCapturedPhotos.length, photoshoot])

  // ── Auto-scroll refs ─────────────────────────────────────────────────────
  useEffect(() => {
    const target = activeRowRef.current ?? nextRowRef.current
    if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [currentlyActive, nextPending])

  // ── Camera: enumerate devices ────────────────────────────────────────────
  const enumerateVideoDevices = useCallback(async () => {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices) return
    try {
      const devices = await navigator.mediaDevices.enumerateDevices()
      const videoInputs = devices
        .filter((d) => d.kind === 'videoinput')
        .map((d) => ({
          deviceId: d.deviceId,
          label: d.label || `Kamera ${d.deviceId.slice(0, 6)}`,
        }))
      setVideoDevices(videoInputs)
      if (videoInputs.length > 0 && !selectedDeviceRef.current) {
        // ── Per-channel camera selection ──────────────────────────────────
        // Dual photo mode: 2 cameras on the admin laptop (via USB capture
        // cards / HDMI capture). Each Operator App (channel 1 + channel 2)
        // must use a DIFFERENT camera, or the second one gets
        // NotReadableError (camera already held by the first).
        // Strategy:
        //   1. Read the per-channel stored choice (persists across relaunch).
        //   2. Validate it still exists in the enumerated device list (USB
        //      deviceIds can change across replugs).
        //   3. If no valid stored choice, AUTO-DISTRIBUTE by channel index:
        //      channel N → camera index (N-1). So channel 1 → camera 0,
        //      channel 2 → camera 1, etc. Fall back to camera 0 if the index
        //      is out of range. This means the 2 Operator Apps grab DIFFERENT
        //      cameras on first launch — no conflict, no manual setup.
        const STORAGE_KEY = `saatiril_op_camera_ch_${myChannel}`
        let chosenDeviceId = ''
        try {
          chosenDeviceId = localStorage.getItem(STORAGE_KEY) || ''
        } catch { /* localStorage may be unavailable */ }
        const storedStillExists = chosenDeviceId
          ? videoInputs.some((d) => d.deviceId === chosenDeviceId)
          : false
        if (!storedStillExists) {
          const idx = Math.max(0, (myChannel - 1))
          chosenDeviceId = videoInputs[idx]?.deviceId ?? videoInputs[0].deviceId
        }
        setSelectedDeviceId(chosenDeviceId)
        selectedDeviceRef.current = chosenDeviceId
      }
    } catch (err) {
      console.error('[SAATIRIL OP] Failed to enumerate devices:', err)
    }
  }, [myChannel])

  // ── Persist the selected camera per-channel ──────────────────────────────
  // When the user picks a different camera via the dropdown (or when the
  // auto-distribution above sets one), persist it to localStorage under a
  // per-channel key. So channel 1's choice + channel 2's choice are stored
  // independently, and both stick across relaunches. On the next launch,
  // enumerateVideoDevices reads this back so the 2 Operator Apps keep their
  // distinct cameras without re-auto-distributing.
  useEffect(() => {
    if (!selectedDeviceId || !myChannel) return
    try {
      localStorage.setItem(`saatiril_op_camera_ch_${myChannel}`, selectedDeviceId)
    } catch { /* localStorage may be unavailable */ }
  }, [selectedDeviceId, myChannel])

  // ── Camera: start stream ─────────────────────────────────────────────────
  // BUG #7 fix (audit Remote/Mirror batch 2): startCamera now accepts an
  // optional generation token (gen). After the await getUserMedia, we check
  // cameraGenRef.current — if it doesn't match gen, the camera was released
  // during the await (cleanup ran, or a newer startCamera superseded us) so
  // we stop the new stream immediately + bail out without touching streamRef.
  // This prevents the in-flight getUserMedia from re-grabbing the camera
  // after cleanup explicitly released it.
  const startCamera = useCallback(
    async (deviceId?: string, gen?: number) => {
      if (typeof navigator === 'undefined' || !navigator.mediaDevices) {
        setCameraAvailable(false)
        return
      }
      if (streamRef.current) {
        streamRef.current.getTracks().forEach((t) => t.stop())
        streamRef.current = null
      }

      let constraints: MediaStreamConstraints
      if (deviceId) {
        constraints = {
          video: { deviceId: { exact: deviceId }, width: { ideal: 1920 }, height: { ideal: 1080 } },
          audio: false,
        }
      } else if (isMobile) {
        constraints = {
          video: { facingMode: 'environment', width: { ideal: 1920 }, height: { ideal: 1080 } },
          audio: false,
        }
      } else {
        constraints = {
          video: { width: { ideal: 1920 }, height: { ideal: 1080 } },
          audio: false,
        }
      }

      try {
        const stream = await navigator.mediaDevices.getUserMedia(constraints)
        // BUG #7 fix: if the camera was released during the await (cleanup
        // ran + cameraGenRef set to -1, or a newer startCamera superseded
        // us with a different gen), stop the new stream immediately and bail
        // out without touching streamRef — the cleanup already released the
        // previous stream and we must NOT re-grab the camera.
        if (gen !== undefined && cameraGenRef.current !== gen) {
          stream.getTracks().forEach((t) => t.stop())
          return
        }
        streamRef.current = stream
        setCameraAvailable(true)
        if (videoRef.current) videoRef.current.srcObject = stream
        await enumerateVideoDevices()
      } catch (err) {
        console.error('[SAATIRIL OP] Camera access failed:', err)
        // Fallback: retry with looser constraints. Helps capture cards (Elgato
        // Cam Link, Blackmagic, AverMedia, etc.) that may not support the ideal
        // 1920x1080 constraint, professional cameras via capture card that
        // output at non-standard resolutions/frame rates, and mobile devices
        // where facingMode fails. Without this fallback, a capture card that
        // can't do 1080p-ideal would show 'camera not available' even though
        // it can stream at its native resolution.
        try {
          let fallbackConstraints: MediaStreamConstraints
          if (deviceId) {
            // Desktop / capture card: retry with JUST the deviceId (drop the
            // resolution constraint so the card streams at its native resolution).
            fallbackConstraints = { video: { deviceId: { exact: deviceId } }, audio: false }
          } else if (isMobile) {
            // Mobile: retry without facingMode (some phones reject 'environment').
            fallbackConstraints = { video: { width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false }
          } else {
            // Desktop default (no device selected): retry with the most permissive.
            fallbackConstraints = { video: true, audio: false }
          }
          const stream = await navigator.mediaDevices.getUserMedia(fallbackConstraints)
          // BUG #7 fix: same generation check after the fallback await.
          if (gen !== undefined && cameraGenRef.current !== gen) {
            stream.getTracks().forEach((t) => t.stop())
            return
          }
          streamRef.current = stream
          setCameraAvailable(true)
          if (videoRef.current) videoRef.current.srcObject = stream
          await enumerateVideoDevices()
          console.log('[SAATIRIL OP] Camera fallback succeeded (looser constraints)')
        } catch (fallbackErr) {
          console.error('[SAATIRIL OP] Camera fallback also failed:', fallbackErr)
          setCameraAvailable(false)
        }
      }
    },
    [enumerateVideoDevices, isMobile],
  )

  useEffect(() => {
    // Gate the camera on cameraActive: when the admin's OperatorPanel is hidden
    // (admin on Admin/MC tab), release the camera so the Operator Electron App
    // (if running on the same laptop) can use it. When cameraActive becomes
    // true (admin switches to Live/Operator view), re-start the camera.
    //
    // BUG #7 fix (audit Remote/Mirror batch 2): previously used
    //   queueMicrotask(() => void startCamera())
    // but the microtask could fire AFTER cleanup ran (if cameraActive flipped
    // back to false, or React StrictMode double-invoked the effect), causing
    // startCamera() to re-acquire the camera we just released — visible as a
    // brief camera grab on the operator Electron app or a "camera in use"
    // error. Now we call startCamera directly + use a generation token
    // (cameraGenRef) so any in-flight getUserMedia can detect that the camera
    // was released during the await and bail out before touching streamRef.
    if (!cameraActive) return
    const gen = ++cameraGenRef.current
    void startCamera(undefined, gen)
    return () => {
      // Invalidate this generation so any in-flight startCamera aborts.
      cameraGenRef.current = -1
      // Always release on cleanup (unmount OR cameraActive flip to false).
      if (streamRef.current) {
        streamRef.current.getTracks().forEach((t) => t.stop())
        streamRef.current = null
      }
    }
  }, [startCamera, cameraActive])

  useEffect(() => {
    // Re-attach the EXISTING camera stream to the <video> element when the
    // project loads (the <video> wasn't rendered while !currentProject — the
    // 'Belum ada proyek aktif' screen showed instead, so videoRef.current was
    // null + the stream couldn't attach on the initial startCamera). When the
    // project loads, hasProject flips false→true → the <video> renders → this
    // effect re-attaches the existing stream (NO stop/re-obtain — that raced on
    // Windows + caused 'NO CAMERA SIGNAL'). This is why wisuda camera showed
    // black: the operator started before the project loaded → stream obtained
    // but unattached → video element rendered later but the camera useEffect
    // didn't re-run → stream stayed unattached. In photoshoot the project was
    // often already loaded (localStorage) so the <video> rendered immediately.
    if (streamRef.current && videoRef.current) {
      videoRef.current.srcObject = streamRef.current
    }
  }, [hasProject])

  useEffect(() => {
    // Gate on cameraActive too — otherwise selecting/changing a device while the
    // admin's OperatorPanel is hidden (admin on Admin/MC tab, cameraActive=false)
    // would call startCamera(selectedDeviceId) and GRAB the camera even though
    // the admin isn't viewing the Operator → the Operator Electron App (if
    // running on the same laptop) can't activate its camera (NotReadableError).
    // This was the root cause of the dual-photoshoot camera conflict: device
    // selections triggered by the channel selector / device list bypassed the
    // cameraActive gate. Now the camera only starts when cameraActive is true.
    //
    // BUG #7 fix (audit Remote/Mirror batch 2): drop queueMicrotask + use the
    // generation token so a stale device-change doesn't grab the camera after
    // a newer selection (or a cameraActive=false cleanup) invalidated it.
    if (cameraActive && selectedDeviceId && selectedDeviceRef.current !== selectedDeviceId) {
      selectedDeviceRef.current = selectedDeviceId
      const gen = ++cameraGenRef.current
      void startCamera(selectedDeviceId, gen)
    }
  }, [selectedDeviceId, startCamera, cameraActive])

  useEffect(() => {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices) return
    const handler = () => enumerateVideoDevices()
    navigator.mediaDevices.addEventListener('devicechange', handler)
    return () => { navigator.mediaDevices.removeEventListener('devicechange', handler) }
  }, [enumerateVideoDevices])

  // ── Switch camera (mobile: toggle front/rear) ────────────────────────────
  const handleSwitchCamera = useCallback(async () => {
    if (videoDevices.length < 2) return
    const currentIdx = videoDevices.findIndex((d) => d.deviceId === selectedDeviceId)
    const nextIdx = (currentIdx + 1) % videoDevices.length
    const nextDeviceId = videoDevices[nextIdx].deviceId
    setSelectedDeviceId(nextDeviceId)
    selectedDeviceRef.current = nextDeviceId
  }, [videoDevices, selectedDeviceId])

  // ── Refs for stable handlers ─────────────────────────────────────────────
  const myChannelRef = useRef(myChannel)
  const currentProjectRef = useRef(currentProject)
  useEffect(() => { myChannelRef.current = myChannel }, [myChannel])
  useEffect(() => { currentProjectRef.current = currentProject }, [currentProject])

  // ── State recovery (non-photoshoot only) ────────────────────────────────
  useEffect(() => {
    if (!currentProject || photoshoot) return
    const activeStudent = currentProject.database.find((s) => {
      return s.assignedChannel === myChannel && isActiveStatus(s.status)
    })
    if (activeStudent) {
      setOpCurrentTarget(activeStudent)
    } else if (opCurrentTarget && !isActiveStatus(opCurrentTarget.status)) {
      setOpCurrentTarget(null)
    }
  }, [currentProject, myChannel, setOpCurrentTarget, photoshoot])

  // ── Socket: MC_CALL ─────────────────────────────────────────────────────
  useEffect(() => {
    const handleMcCall = (data: McCallData) => {
      // In photoshoot mode: accept MC_CALL from ANY channel (both operators see it)
      // In non-photoshoot mode: only accept MC_CALL for our channel
      if (!photoshoot && data.channel !== myChannelRef.current) return
      // CRITICAL FIX v15: Use data.student.status when the sender has set it.
      // v8 mc.html sends student WITH status='sent' (photoshoot) or 'active_channel' (wisuda).
      // Old code hardcoded 'active_channel' which OVERWROTE 'sent' in photoshoot mode.
      // Now: use whatever status the MC sent, fallback to 'active_channel' for old stable MC.
      const studentStatus = (data.student.status && data.student.status !== 'pending')
        ? data.student.status
        : `active_${data.channel}` as StudentStatus
      const correctedStudent = { ...data.student, status: studentStatus }
      console.log('[SAATIRIL OP] MC_CALL received:', data.student.nama, 'status:', correctedStudent.status, 'Ch.', data.channel)
      if (photoshoot) {
        // REPLACE any existing buffer entry for this student instead of
        // dedup-skipping. This ensures a RE-SEND (after reset/retake) updates
        // the buffer with fresh data instead of being silently dropped.
        setMcCallBuffer((prev) => {
          const without = prev.filter((s) => s.id !== data.student.id)
          return [...without, correctedStudent]
        })
      } else {
        setOpCurrentTarget(correctedStudent)
      }
    }
    onLocal('MC_CALL', handleMcCall)
    return () => { offLocal('MC_CALL', handleMcCall) }
  }, [setOpCurrentTarget, photoshoot])

  // ── Socket: STUDENT_RESET — explicit reset/retake signal from MC ────────
  // This bypasses the normal SYNC_DB merge (which blocks status regression:
  // pending priority 0 < sent/done) so the operator can fully clear a student
  // for retake: drop from mcCallBuffer, clear active target, remove the
  // photoHistory entry on this channel, and set status to 'pending' locally.
  useEffect(() => {
    const handleStudentReset = (data: { studentId: string; channel: number }) => {
      // In photoshoot mode: accept from any channel (matching APK behavior)
      // In non-photoshoot mode: only accept for our channel
      if (!photoshoot && data.channel !== myChannelRef.current) return
      console.log('[SAATIRIL OP] STUDENT_RESET received — clearing for retake:', data.studentId, 'Ch.', data.channel)

      // 1. Drop from mcCallBuffer
      setMcCallBuffer((prev) => prev.filter((s) => s.id !== data.studentId))

      // 2. Clear active target if it matches
      // Clear active target if it matches — read current (per-channel) + set.
      // (Was a functional updater `setOpCurrentTarget((cur) => ...)` which the
      // store action doesn't support — now reads fresh from getState + sets.)
      const _cur = useSaatirilStore.getState().opCurrentTargets[myChannelRef.current] ?? null
      setOpCurrentTarget(_cur?.id === data.studentId ? null : _cur)

      // 3. Remove this channel's photoHistory entry + reset status locally
      //    (bypasses mergeDatabases priority which would otherwise ignore the
      //    'pending' regression coming via SYNC_DB).
      //    IMPORTANT: read via useSaatirilStore.getState() (synchronous, latest)
      //    instead of currentProjectRef.current (stale ref updated only after
      //    render). This prevents a race where SYNC_DB arrives right after
      //    STUDENT_RESET and sees the OLD photoHistory — which would re-add
      //    the cleared entry via preservePhotoHistoryOnSync.
      const proj = useSaatirilStore.getState().currentProject
      if (proj) {
        const cleanedHistory = proj.photoHistory.filter(
          (h) => !(h.student.id === data.studentId && h.channel === myChannelRef.current),
        )
        const cleanedDb = proj.database.map((s) =>
          s.id === data.studentId ? { ...s, status: 'pending' as StudentStatus } : s,
        )
        updateCurrentProject({ ...proj, database: cleanedDb, photoHistory: cleanedHistory })
      }
    }
    onLocal('STUDENT_RESET', handleStudentReset)
    return () => { offLocal('STUDENT_RESET', handleStudentReset) }
  }, [setOpCurrentTarget, updateCurrentProject])

  // ── Socket: SYNC_DB ─────────────────────────────────────────────────────
  useEffect(() => {
    const handleSyncDb = (data: SyncDbData) => {
      // Read latest state synchronously (avoids stale currentProjectRef race
      // when SYNC_DB arrives immediately after STUDENT_RESET / other updates).
      const proj = useSaatirilStore.getState().currentProject
      if (!proj) {
        // No existing project — accept incoming directly (first connect)
        updateCurrentProject(data.project)
        console.log('[SAATIRIL OP] SYNC_DB: accepted new project (no existing):', data.project.name)
        // Check for active student in wisuda mode
        if (!isPhotoshootMode(data.project.config.mode)) {
          const ch = myChannelRef.current
          const activeStudent = data.project.database.find((s) => s.assignedChannel === ch && isActiveStatus(s.status))
          if (activeStudent) setOpCurrentTarget(activeStudent)
        }
        return
      }
      const mergedDb = mergeDatabases(proj.database, data.project.database)
      const mergedConfig = preserveFrameOnSync(data.project.config, proj.config)
      const mergedPhotoHistory = preservePhotoHistoryOnSync(
        data.project.photoHistory ?? [],
        proj.photoHistory,
      )
      // Merge captureVersions (MAX per key) so retake version numbers never
      // regress across clients. The operator who just captured has the
      // highest version; other clients adopt it via this merge.
      const mergedVersions = mergeCaptureVersions(
        proj.captureVersions,
        (data.project as any).captureVersions,
      )
      updateCurrentProject({ ...proj, database: mergedDb, photoHistory: mergedPhotoHistory, config: mergedConfig, captureVersions: mergedVersions })

      if (!isPhotoshootMode(data.project.config.mode)) {
        const ch = myChannelRef.current
        const activeStudent = data.project.database.find((s: Student) => {
          return s.assignedChannel === ch && isActiveStatus(s.status)
        })
        if (activeStudent) setOpCurrentTarget(activeStudent)
      }

      const doneIds = new Set(
        data.project.database.filter((s: Student) => s.status === 'done').map((s: Student) => s.id)
      )
      if (doneIds.size > 0) {
        setMcCallBuffer((prev) => prev.filter((s) => !doneIds.has(s.id)))
      }

      // Dual-photoshoot: when EITHER camera takes a photo, the participant is
      // considered done. If our current target is now 'done' (because the OTHER
      // operator took the photo), clear our target + captured photos so we don't
      // take a redundant photo. This realizes the "1 camera is enough" rule.
      const curTarget = useSaatirilStore.getState().opCurrentTargets[myChannelRef.current] ?? null
      if (curTarget && doneIds.has(curTarget.id)) {
        console.log('[SAATIRIL OP] SYNC_DB: current target is now done — clearing (other operator finished):', curTarget.nama)
        resetOpState()
      }
    }
    onLocal('SYNC_DB', handleSyncDb)
    return () => { offLocal('SYNC_DB', handleSyncDb) }
  }, [setOpCurrentTarget, resetOpState, updateCurrentProject])

  // ── Live Mirror: stream video frames at ~10 FPS to the /mirror page ────
  // Captures a JPEG frame from the local <video> element via a small canvas
  // (640px wide, aspect-correct height) and emits lan-message MIRROR_FRAME
  // on this channel. The /mirror page (opened on a separate device or 2nd
  // monitor) renders the frame fullscreen. Stops when the operator disables
  // the toggle or the camera goes inactive — cleanup runs on unmount too.
  // MIRROR_COUNTDOWN is emitted from a separate useEffect watching
  // effectiveTimerCountdown; MIRROR_CAPTURE is emitted in finalizeCapture.
  useEffect(() => {
    if (!liveMirror || !cameraAvailable) {
      if (mirrorIntervalRef.current) {
        clearInterval(mirrorIntervalRef.current)
        mirrorIntervalRef.current = null
      }
      return
    }

    // ── Performance tuning ────────────────────────────────────────────────
    // 15 FPS target with IPC fast path (Electron + mirror window open) —
    // the Blob goes directly via IPC (structured clone, ~1ms), no base64
    // encoding, no socket.io, no JSON. The only per-frame cost is the
    // toBlob JPEG encoding (async, off-thread ~5-15ms). At 15 FPS
    // (67ms interval), the encoder has 50ms+ headroom → smooth.
    // Falls back to 10 FPS socket.io path (FileReader → base64) when no
    // mirror window is open (cross-device tablet/phone scenario).
    // Width 480 + quality 0.4 → JPEG ~15-25KB. At 15 FPS = ~225-375 KB/s.
    const MIRROR_FPS = 15
    const MIRROR_WIDTH = 480
    const MIRROR_QUALITY = 0.4

    // Reusable canvas (off-screen) — avoids GC churn at 10 FPS.
    const mirrorCanvas = document.createElement('canvas')
    const mirrorCtx = mirrorCanvas.getContext('2d')
    if (!mirrorCtx) return

    let emittedCount = 0
    let skippedCount = 0
    let processing = false  // true while a toBlob() is encoding — drop new ticks
    let lastCanvasKey = ''
    mirrorIntervalRef.current = setInterval(() => {
      // If the previous toBlob() is still in flight, skip this tick — we'd
      // just queue up another encode + pile memory pressure. The next tick
      // will pick up a fresh frame.
      if (processing) return

      const video = videoRef.current
      if (!video || video.readyState < 2 || video.videoWidth <= 0) {
        skippedCount++
        if (skippedCount === 30) {
          console.warn('[SAATIRIL OP] mirror: 30 frames skipped (video null/ not ready).', {
            videoExists: !!video,
            readyState: video?.readyState,
            videoWidth: video?.videoWidth,
          })
          skippedCount = 0
        }
        return
      }
      // Use the configured aspectRatio so the mirror view matches the
      // operator's capture aspect (no distortion). The canvas dimensions
      // only update when the aspect changes (avoids clearing every tick).
      const newW = MIRROR_WIDTH
      const newH = Math.max(1, Math.round(MIRROR_WIDTH / aspectRatio))
      const newKey = newW + 'x' + newH
      if (newKey !== lastCanvasKey) {
        mirrorCanvas.width = newW
        mirrorCanvas.height = newH
        lastCanvasKey = newKey
      }
      // Compute crop region from the video to match the configured aspect
      // ratio (center crop, same logic as handleCapture). This is just a
      // drawImage with computed sx/sy/sw/sh — NO extra CPU cost vs a
      // non-cropped drawImage. Necessary to avoid stretching/distortion.
      const videoWidth = video.videoWidth
      const videoHeight = video.videoHeight
      const videoRatio = videoWidth / videoHeight
      let sx = 0, sy = 0, sw = videoWidth, sh = videoHeight
      if (videoRatio > aspectRatio) {
        sw = videoHeight * aspectRatio
        sx = (videoWidth - sw) / 2
      } else {
        sh = videoWidth / aspectRatio
        sy = (videoHeight - sh) / 2
      }
      try {
        // RAW camera only — no ctx.filter, no frame overlay. This is the
        // "apa adanya" path: just drawImage the video frame to the canvas.
        // Filter + frame were removed because they added per-frame CPU
        // overhead that caused lagging on the projector.
        mirrorCtx.drawImage(video, sx, sy, sw, sh, 0, 0, mirrorCanvas.width, mirrorCanvas.height)
      } catch (err) {
        // Cross-origin tainted canvas (shouldn't happen with local getUserMedia
        // stream, but guard anyway so the interval doesn't crash silently).
        console.warn('[SAATIRIL OP] mirror frame draw failed:', err)
        return
      }
      // toBlob is ASYNC + non-blocking (unlike toDataURL which is sync).
      // The JPEG encoder runs off the main thread → no UI jank. Then we use
      // FileReader.readAsDataURL to convert the Blob to a base64 string
      // (also async + off-thread) before sending. The string path is the
      // SAME wire format that worked at commit 1df4732 — socket.io v4
      // binary Blob transfer had issues on the mirror side (black screen
      // regression), so we stick with the proven base64 string approach
      // but keep the perf wins from async encoding + lower res/quality.
      processing = true
      mirrorCanvas.toBlob((blob) => {
        if (!blob) {
          processing = false
          return
        }
        // If toggle went OFF mid-encode, skip emitting.
        if (!liveMirrorRef.current) {
          processing = false
          return
        }

        // ── IPC FAST PATH (Electron + mirror window open) ──────────────
        // Send the Blob directly to the mirror projector BrowserWindow
        // via IPC (structured clone). No base64 encoding, no JSON
        // serialization, no socket.io wire transfer. Much faster →
        // smooth on the projector.
        //
        // IMPORTANT: This is a BONUS fast path, NOT a replacement for
        // socket.io. We ALWAYS also send via socket.io (below) as a
        // backup. If IPC fails for any reason (Blob not structured-cloned
        // correctly, mirror window preload issue, etc.), the socket.io
        // path ensures the mirror still gets frames. Without the socket.io
        // backup, an IPC failure → permanent black screen (which is what
        // the user experienced at commit 07a1ce5).
        let ipcSent = false
        if (useIpcForMirrorRef.current) {
          const api = (window as any).saatirilAPI
          if (api?.sendMirrorFrame) {
            try {
              api.sendMirrorFrame(blob)
              ipcSent = true
            } catch (err) {
              console.warn('[SAATIRIL OP] IPC sendMirrorFrame failed, falling back to socket.io only:', err)
            }
          }
        }

        // ── SOCKET.IO PATH (always — backup + cross-device) ─────────────
        // Convert blob → data URL string off-thread via FileReader. This
        // is the base64 encoding step — by doing it via FileReader we
        // keep the main thread responsive. ALWAYS run this path (even
        // when IPC was sent) so the mirror has a backup if IPC fails.
        const reader = new FileReader()
        reader.onload = () => {
          processing = false
          const dataUrl = reader.result
          if (typeof dataUrl !== 'string') return
          if (!liveMirrorRef.current) return
          emitLocal('MIRROR_FRAME', { frame: dataUrl, channel: liveMirrorChannelRef.current })
          emittedCount++
          if (emittedCount === 30) {
            const sk = getSocket()
            console.log('[SAATIRIL OP] mirror: 30 frames emitted (ch' + liveMirrorChannelRef.current + ').',
              'ipc=' + (ipcSent ? 'yes' : 'no'),
              'frame size=', Math.round(dataUrl.length / 1024) + 'KB',
              'socket.connected=', sk?.connected ?? false,
              'socket.id=', sk?.id ?? 'null')
            emittedCount = 0
          }
        }
        reader.onerror = () => {
          processing = false
          console.warn('[SAATIRIL OP] mirror FileReader failed')
        }
        reader.readAsDataURL(blob)
      }, 'image/jpeg', MIRROR_QUALITY)
    }, 1000 / MIRROR_FPS)

    return () => {
      if (mirrorIntervalRef.current) {
        clearInterval(mirrorIntervalRef.current)
        mirrorIntervalRef.current = null
      }
    }
  }, [liveMirror, cameraAvailable, aspectRatio])

  // ── Finalize capture ────────────────────────────────────────────────────
  // OPTIMIZED: Removed 400ms of unnecessary delays, added STUDENT_DONE lightweight
  // event for immediate MC unblocking, and SYNC_DB now strips photos (tiny payload).
  const finalizeCapture = useCallback(
    (canvas: HTMLCanvasElement) => {
      setFlashVisible(true)
      setTimeout(() => setFlashVisible(false), 200)

      const dataUrl = canvas.toDataURL('image/jpeg', 0.95)
      addOpCapturedPhoto(dataUrl)

      // Live Mirror: emit MIRROR_CAPTURE so the /mirror page does a white
      // flash overlay (matches the operator's local flash timing).
      if (liveMirrorRef.current) {
        emitLocal('MIRROR_CAPTURE', { channel: liveMirrorChannelRef.current })
      }

      const currentPhotos = useSaatirilStore.getState().opCapturedPhotosByChannel[myChannel] ?? []
      const currentTarget = useSaatirilStore.getState().opCurrentTargets[myChannel] ?? null
      const photoCount = currentPhotos.length
      const currentMode = useSaatirilStore.getState().currentProject?.config.mode ?? 'single'
      const isPhotoshoot = isPhotoshootMode(currentMode)

      console.log('[SAATIRIL OP] finalizeCapture: photoCount =', photoCount, 'mode =', currentMode)

      // Helper: update local photoHistory + captureVersions, emit lightweight SYNC_DB.
      // `newVersions` is the updated captureVersions map (with the just-captured
      // student+channel incremented) so version numbers persist across resets.
      const finishCapture = (
        student: Student,
        historyItem: PhotoHistoryItem,
        newVersions?: Record<string, number>,
      ) => {
        const store = useSaatirilStore.getState()
        if (store.currentProject) {
          const existingIdx = store.currentProject.photoHistory.findIndex(
            (h) => h.student.id === student.id && h.channel === myChannel
          )
          let newHistory: PhotoHistoryItem[]
          if (existingIdx !== -1) {
            newHistory = [...store.currentProject.photoHistory]
            newHistory[existingIdx] = historyItem
          } else {
            newHistory = [...store.currentProject.photoHistory, historyItem]
          }
          const updatedProject = {
            ...store.currentProject,
            photoHistory: newHistory,
            captureVersions: newVersions ?? store.currentProject.captureVersions,
          }
          store.updateCurrentProject(updatedProject)
          // SYNC_DB is now lightweight — photos stripped, only metadata sent
          // (captureVersions IS included so admin/MC see the same version numbers)
          emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })
        }
        setSending(false)
        isCapturingRef.current = false
        resetOpState()
      }

      // ── Compute versioned filename helper ──────────────────────────────────
      // Reads the per-student+channel capture counter from the project, increments
      // it, and returns { version, newVersions, filename } so the caller can
      // (a) use the filename for the disk save, and (b) pass newVersions to
      // finishCapture so the counter persists.
      const proj0 = useSaatirilStore.getState().currentProject
      const computeVersionedFilename = (
        student: Student,
        isPhotoshootModeFlag: boolean,
      ): { version: number; newVersions: Record<string, number>; filename: string } => {
        const versionKey = `${student.id}_${myChannel}`
        const currentVersions = proj0?.captureVersions ?? {}
        const prevVersion = currentVersions[versionKey] ?? 0
        const version = prevVersion + 1
        const newVersions = { ...currentVersions, [versionKey]: version }
        const filename = isPhotoshootModeFlag
          ? buildPhotoshootFilename(student.nim, student.nama, myChannel, version, student.id)
          : buildFilename(student.nim, student.nama, 1, 'Toga', version, student.id) // placeholder; standard mode overrides below
        return { version, newVersions, filename }
      }

      // Photoshoot mode: save after 1 photo
      if (isPhotoshoot && photoCount >= 1) {
        if (!currentTarget) {
          console.warn('[SAATIRIL OP] finalizeCapture: opCurrentTarget is null — aborting save')
          // CRITICAL: tell the operator WHY the photo wasn't saved, so they
          // don't think it was captured successfully. This happens when MC
          // resets a student but hasn't re-sent them yet.
          toast({
            title: 'Foto Tidak Tersimpan',
            description: 'Tidak ada peserta aktif. Minta MC mengirim ulang peserta, lalu foto kembali.',
            variant: 'destructive',
          })
          setSending(false)
          isCapturingRef.current = false
          resetOpState()
          return
        }
        setSending(true)
        const student = currentTarget
        const allPhotos = [...currentPhotos]
        const historyItem: PhotoHistoryItem = {
          student: { ...student },
          photos: allPhotos,
          channel: myChannel,
        }
        saveProjectsToStorageNow()
        setMcCallBuffer((prev) => prev.filter((s) => s.id !== student.id))

        // ── VERSIONED FILENAME ────────────────────────────────────────────────
        // Each capture (incl. retakes after MC reset) gets a unique version so
        // retakes create NEW files on disk instead of overwriting.
        // v1 → `NIM_Nama.jpg`, v2 → `NIM_Nama_v2.jpg`, …
        const { version, newVersions, filename: vFilename } = computeVersionedFilename(student, true)
        console.log(`[SAATIRIL OP] Photoshoot capture v${version} for ${student.nama}: ${vFilename}`)

        console.log('[SAATIRIL OP] Emitting PHOTOS_SAVED for student:', student.nama, 'channel:', myChannel, 'version:', version)

        // PHOTOS_SAVED for Admin/gallery (contains photos for display + version)
        emitLocal('PHOTOS_SAVED', {
          student: { ...student, status: student.status },
          photos: allPhotos,
          channel: myChannel,
          version,
          filename: vFilename,
        })
        emitLocal('OP_PROGRESS', { channel: myChannel, status: 'Selesai — Menunggu target...' })

        // ── SAVE TO DISK ──────────────────────────────────────────────────────
        // Electron only: photos write to config.targetFolder (the folder chosen
        // at PROJECT CREATION) via IPC. In browser mode (Chrome) photos stay in
        // memory/gallery only — run via Electron desktop for permanent disk
        // saves. The operator's job is ONLY to take photos; the folder is NOT
        // picked here.
        const projConfig = useSaatirilStore.getState().currentProject?.config
        if (projConfig) {
          const api = window.saatirilAPI
          if (api?.savePhoto) {
            const targetFolder = projConfig.targetFolder
            console.log(`[SAATIRIL OP] Saving photo to disk: ${targetFolder}/${vFilename}`)
            api.savePhoto({
              base64Data: allPhotos[0],
              filename: vFilename,
              targetFolder,
              // Pass backupFolder + projectName so the Electron savePhoto IPC
              // creates a project subfolder in the backup folder (mirrors
              // local structure: backupFolder/projectName/filename).
              backupFolder: projConfig.driveFolder ?? null,
              projectName: useSaatirilStore.getState().currentProject?.name ?? null,
            }).then((path: string | null) => {
              // Detect the legacy fake-delegation sentinel returned by an OLD
              // operator-exe preload (pre-fix). The new preload (after CHANGE 3)
              // returns a real filesystem path or null — never this sentinel.
              if (path === 'saved-via-admin-socket') {
                console.log(`[SAATIRIL OP] Photo delegated — Admin will save to disk via PHOTOS_SAVED event. (${vFilename})`)
                return
              }
              if (path) {
                console.log(`[SAATIRIL OP] ✓ Photo saved to disk (local redundancy): → ${path}`)
              } else {
                console.warn(`[SAATIRIL OP] ✗ Photo FAILED to save to disk: ${targetFolder}/${vFilename}`)
                toast({
                  title: 'Gagal Simpan ke Disk',
                  description: `Foto ${vFilename} tidak tersimpan. Cek ruang disk & folder target. Admin tetap akan mencoba simpan via PHOTOS_SAVED.`,
                  variant: 'destructive',
                })
              }
            }).catch((err: Error) => {
              console.error('[SAATIRIL OP] Error saving photo to disk:', err)
              toast({
                title: 'Error Simpan Foto',
                description: err.message || 'Terjadi kesalahan saat menyimpan ke disk.',
                variant: 'destructive',
              })
            })
          } else {
            // Browser mode (operator opened in Chrome for camera flags, etc.).
            // The operator does NOT save to its own disk here — the Admin panel
            // (running in Electron) receives the PHOTOS_SAVED socket event and
            // saves to config.targetFolder via its own IPC. So the photo IS
            // saved to disk; we just skip the operator's local save silently.
            console.log('[SAATIRIL OP] Browser mode — operator disk save skipped. Admin (Electron) will save via PHOTOS_SAVED event.')
          }
        }

        // IMMEDIATE: update local state + emit lightweight SYNC_DB (no delays!)
        finishCapture(student, historyItem, newVersions)
        return
      }

      // Standard mode (single/dual): 2 photos
      if (photoCount === 1) {
        isCapturingRef.current = false
        emitLocal('OP_PROGRESS', { channel: myChannel, status: 'Pose 1 OK — Siap Foto 2' })
      } else if (photoCount >= 2) {
        if (!currentTarget) {
          console.warn('[SAATIRIL OP] finalizeCapture: opCurrentTarget is null — aborting save')
          toast({
            title: 'Foto Tidak Tersimpan',
            description: 'Tidak ada peserta aktif. Minta MC mengirim ulang peserta, lalu foto kembali.',
            variant: 'destructive',
          })
          setSending(false)
          isCapturingRef.current = false
          resetOpState()
          return
        }
        setSending(true)
        const student = currentTarget
        const allPhotos = [...currentPhotos]
        const historyItem: PhotoHistoryItem = {
          student: { ...student },
          photos: allPhotos,
          channel: myChannel,
        }
        updateStudentStatus(student.id, 'done')
        saveProjectsToStorageNow()

        // ── VERSIONED FILENAMES ───────────────────────────────────────────────
        // Standard mode: 2 photos (Toga + Ijazah), both share the same version.
        // v1 → `NIM_Nama_1_Toga.jpg` + `NIM_Nama_2_Ijazah.jpg`
        // v2 → `NIM_Nama_1_Toga_v2.jpg` + `NIM_Nama_2_Ijazah_v2.jpg`
        const { version, newVersions } = computeVersionedFilename(student, false)
        const togaFilename = buildFilename(student.nim, student.nama, 1, 'Toga', version, student.id)
        const ijazahFilename = buildFilename(student.nim, student.nama, 2, 'Ijazah', version, student.id)
        console.log(`[SAATIRIL OP] Standard capture v${version} for ${student.nama}: ${togaFilename}, ${ijazahFilename}`)

        console.log('[SAATIRIL OP] Emitting STUDENT_DONE + PHOTOS_SAVED for student:', student.nama, 'channel:', myChannel, 'version:', version)

        // PRIORITY 1: STUDENT_DONE — lightweight event for IMMEDIATE MC unblocking
        // This fires BEFORE the heavy PHOTOS_SAVED so MC can call next student instantly
        emitLocal('STUDENT_DONE', {
          studentId: student.id,
          channel: myChannel,
        })

        // PRIORITY 2: PHOTOS_SAVED — contains photos for Admin gallery + version
        emitLocal('PHOTOS_SAVED', {
          student: { ...student, status: 'done' },
          photos: allPhotos,
          channel: myChannel,
          version,
          filename: togaFilename,
        })
        emitLocal('OP_PROGRESS', { channel: myChannel, status: 'Selesai — Menunggu target...' })

        // ── SAVE TO DISK ──────────────────────────────────────────────────────
        // Electron only: photos write to config.targetFolder (folder chosen at
        // PROJECT CREATION) via IPC. In browser mode photos stay in
        // memory/gallery — run via Electron desktop for permanent disk saves.
        const projConfig = useSaatirilStore.getState().currentProject?.config
        if (projConfig) {
          const api = window.saatirilAPI
          if (api?.savePhoto) {
            const targetFolder = projConfig.targetFolder
            console.log(`[SAATIRIL OP] Saving 2 photos to disk: ${targetFolder}/`)
            Promise.all([
              api.savePhoto({
                base64Data: allPhotos[0],
                filename: togaFilename,
                targetFolder,
                backupFolder: projConfig.driveFolder ?? null,
                projectName: useSaatirilStore.getState().currentProject?.name ?? null,
              }),
              api.savePhoto({
                base64Data: allPhotos[1],
                filename: ijazahFilename,
                targetFolder,
                backupFolder: projConfig.driveFolder ?? null,
                projectName: useSaatirilStore.getState().currentProject?.name ?? null,
              }),
            ]).then(([path1, path2]) => {
              // Detect the legacy fake-delegation sentinel returned by an OLD
              // operator-exe preload (pre-fix). The new preload (after CHANGE 3)
              // returns real filesystem paths or null — never this sentinel.
              const SENTINEL = 'saved-via-admin-socket'
              const delegated1 = path1 === SENTINEL
              const delegated2 = path2 === SENTINEL
              if (delegated1 && delegated2) {
                console.log(`[SAATIRIL OP] Photos delegated — Admin will save to disk via PHOTOS_SAVED event. (${togaFilename}, ${ijazahFilename})`)
                return
              }
              const ok1 = !!path1 && !delegated1
              const ok2 = !!path2 && !delegated2
              if (ok1 && ok2) {
                console.log(`[SAATIRIL OP] ✓ Photos saved to disk (local redundancy):\n  → ${path1}\n  → ${path2}`)
              } else if (ok1 || ok2) {
                console.warn(`[SAATIRIL OP] ✗ Partial save: toga=${ok1} ijazah=${ok2}`)
                toast({
                  title: 'Gagal Simpan ke Disk',
                  description: 'Sebagian foto tidak tersimpan. Cek ruang disk & folder target. Admin tetap akan mencoba simpan via PHOTOS_SAVED.',
                  variant: 'destructive',
                })
              } else {
                console.warn(`[SAATIRIL OP] ✗ Photos FAILED to save: toga=${ok1} ijazah=${ok2}`)
                toast({
                  title: 'Gagal Simpan ke Disk',
                  description: 'Foto tidak tersimpan. Cek ruang disk & folder target. Admin tetap akan mencoba simpan via PHOTOS_SAVED.',
                  variant: 'destructive',
                })
              }
            }).catch((err) => {
              console.error('[SAATIRIL OP] Error saving photos to disk:', err)
              toast({
                title: 'Error Simpan Foto',
                description: err.message || 'Terjadi kesalahan saat menyimpan ke disk.',
                variant: 'destructive',
              })
            })
          } else {
            // Browser mode (operator opened in Chrome for camera flags, etc.).
            // The operator does NOT save to its own disk here — the Admin panel
            // (running in Electron) receives the PHOTOS_SAVED socket event and
            // saves to config.targetFolder via its own IPC. So the photo IS
            // saved to disk; we just skip the operator's local save silently.
            console.log('[SAATIRIL OP] Browser mode — operator disk save skipped. Admin (Electron) will save via PHOTOS_SAVED event.')
          }
        }

        // IMMEDIATE: update local state + emit lightweight SYNC_DB (no delays!)
        finishCapture(student, historyItem, newVersions)
      }
    },
    [myChannel, addOpCapturedPhoto, updateStudentStatus, saveProjectsToStorageNow, resetOpState, toast],
  )

  // ── Photo capture logic ──────────────────────────────────────────────────
  const handleCapture = useCallback(() => {
    if (!opCurrentTarget) return
    if (photoshoot) {
      if (capturePhase !== 'ready-1') return
    } else {
      if (capturePhase !== 'ready-1' && capturePhase !== 'ready-2') return
    }
    if (isCapturingRef.current) return
    isCapturingRef.current = true

    const canvas = canvasRef.current
    const video = videoRef.current
    if (!canvas) return

    const targetWidth = 1920
    const targetHeight = Math.round(targetWidth / aspectRatio)
    canvas.width = targetWidth
    canvas.height = targetHeight

    const ctx = canvas.getContext('2d')
    if (!ctx) return

    ctx.fillStyle = '#000000'
    ctx.fillRect(0, 0, targetWidth, targetHeight)

    if (video && video.readyState >= 2) {
      const videoWidth = video.videoWidth
      const videoHeight = video.videoHeight
      const videoRatio = videoWidth / videoHeight
      let sx = 0, sy = 0, sw = videoWidth, sh = videoHeight
      if (videoRatio > aspectRatio) {
        sw = videoHeight * aspectRatio
        sx = (videoWidth - sw) / 2
      } else {
        sh = videoWidth / aspectRatio
        sy = (videoHeight - sh) / 2
      }
      if (cssFilter !== 'none') ctx.filter = cssFilter
      ctx.drawImage(video, sx, sy, sw, sh, 0, 0, targetWidth, targetHeight)
      ctx.filter = 'none'
    } else {
      ctx.fillStyle = '#1a0b2e'
      ctx.fillRect(0, 0, targetWidth, targetHeight)
      ctx.fillStyle = '#533485'
      ctx.font = 'bold 48px sans-serif'
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillText('NO CAMERA SIGNAL', targetWidth / 2, targetHeight / 2)
    }

    if (frameImgRef.current) {
      ctx.drawImage(frameImgRef.current, 0, 0, targetWidth, targetHeight)
      finalizeCapture(canvas)
    } else if (frameData) {
      const frameImg = new Image()
      frameImg.crossOrigin = 'anonymous'
      frameImg.onload = () => { ctx.drawImage(frameImg, 0, 0, targetWidth, targetHeight); finalizeCapture(canvas) }
      frameImg.onerror = () => { finalizeCapture(canvas) }
      frameImg.src = frameData
    } else {
      finalizeCapture(canvas)
    }
  }, [opCurrentTarget, capturePhase, aspectRatio, cssFilter, frameData, finalizeCapture, photoshoot])

  // ── Shutter: Timer logic ─────────────────────────────────────────────────
  const handleCaptureRef = useRef(handleCapture)
  useEffect(() => { handleCaptureRef.current = handleCapture }, [handleCapture])
  const handleCaptureClickRef = useRef<() => void>(() => {})

  const cancelTimer = useCallback(() => {
    if (timerIntervalRef.current) {
      clearInterval(timerIntervalRef.current)
      timerIntervalRef.current = null
    }
    timerActiveRef.current = false
    setTimerCountdown(0)
  }, [])

  // Cancel timer interval when capture phase changes away from ready (external cleanup only)
  useEffect(() => {
    if (capturePhase !== 'ready-1' && capturePhase !== 'ready-2') {
      if (timerIntervalRef.current) {
        clearInterval(timerIntervalRef.current)
        timerIntervalRef.current = null
      }
      timerActiveRef.current = false
    }
  }, [capturePhase])

  // Derived: only show countdown when in ready phase
  const effectiveTimerCountdown = (capturePhase === 'ready-1' || capturePhase === 'ready-2') ? timerCountdown : 0

  // ── Live Mirror: emit MIRROR_COUNTDOWN when the timer ticks ────────────
  // Watches effectiveTimerCountdown (the displayed countdown — only updates
  // during the 'ready-1' / 'ready-2' phases). Each tick → mirror shows the
  // number with a pulse animation. When it hits 0, finalizeCapture emits
  // MIRROR_CAPTURE for the flash.
  const prevMirrorCountdownRef = useRef(0)
  useEffect(() => {
    if (!liveMirror) return
    if (effectiveTimerCountdown === prevMirrorCountdownRef.current) return
    prevMirrorCountdownRef.current = effectiveTimerCountdown
    emitLocal('MIRROR_COUNTDOWN', { seconds: effectiveTimerCountdown, channel: liveMirrorChannelRef.current })
  }, [liveMirror, effectiveTimerCountdown])

  const startTimer = useCallback(() => {
    if (!isTimerMode(effectiveShutterMode)) return
    if (capturePhase !== 'ready-1' && capturePhase !== 'ready-2') return
    if (timerActiveRef.current) {
      // Cancel if already running
      cancelTimer()
      return
    }

    const duration = getTimerDuration(effectiveShutterMode)
    let remaining = duration
    timerActiveRef.current = true
    setTimerCountdown(remaining)

    timerIntervalRef.current = setInterval(() => {
      remaining -= 1
      if (remaining <= 0) {
        // Time's up — capture!
        if (timerIntervalRef.current) {
          clearInterval(timerIntervalRef.current)
          timerIntervalRef.current = null
        }
        timerActiveRef.current = false
        setTimerCountdown(0)
        handleCaptureRef.current()
      } else {
        setTimerCountdown(remaining)
      }
    }, 1000)
  }, [effectiveShutterMode, capturePhase, cancelTimer])

  // ── Shutter: AI detection ────────────────────────────────────────────────
  const capturePhaseRef = useRef(capturePhase)
  useEffect(() => { capturePhaseRef.current = capturePhase }, [capturePhase])

  // AI: Initialize when camera is ready and AI shutter mode is active
  useEffect(() => {
    if (effectiveShutterMode === 'ai' && cameraAvailable && hasActiveTarget && !ai.scriptsLoaded && ai.status === 'unloaded') {
      ai.initialize().then((ok) => {
        if (ok) console.log('[SAATIRIL OP] AI initialized for shutter mode')
      })
    }
  }, [effectiveShutterMode, cameraAvailable, hasActiveTarget])

  // AI: Start/stop detection based on shutter mode
  useEffect(() => {
    if (effectiveShutterMode === 'ai' && ai.modelLoaded && cameraAvailable && videoRef.current && hasActiveTarget) {
      ai.startDetection(videoRef.current, (event: AIMomentEvent) => {
        console.log('[SAATIRIL OP] AI moment:', event.type, 'phase:', capturePhaseRef.current)
        const phase = capturePhaseRef.current
        if (event.type === 'toga' && phase === 'ready-1') {
          handleCaptureRef.current()
        } else if (event.type === 'ijazah' && phase === 'ready-2') {
          handleCaptureRef.current()
        }
      })
    } else if (effectiveShutterMode !== 'ai' && ai.isRunning) {
      ai.stopDetection()
    }
  }, [effectiveShutterMode, ai.modelLoaded, cameraAvailable, hasActiveTarget, capturePhase])

  // ── Palm trigger (hands-free shutter) ────────────────────────────────────
  // Palm detection is a TRIGGER, not a shutter mode. When the toggle is ON,
  // showing an open palm fires handleCaptureButtonClick() — which respects
  // the selected mode (manual → instant photo, timer → starts countdown).
  // Disabled in AI mode (AI already auto-triggers).
  const palmTriggerActive = palmTriggerEnabled && effectiveShutterMode !== 'ai'

  useEffect(() => {
    if (palmTriggerActive && cameraAvailable && hasActiveTarget && palm.status === 'unloaded') {
      palm.initialize().then((ok) => {
        if (ok) console.log('[SAATIRIL OP] Palm trigger initialized')
      })
    }
  }, [palmTriggerActive, cameraAvailable, hasActiveTarget])

  useEffect(() => {
    if (palmTriggerActive && (palm.status === 'model_ready' || palm.status === 'stopped') && cameraAvailable && videoRef.current && hasActiveTarget) {
      palm.startDetection(videoRef.current, {
        onPalmConfirmed: () => {
          console.log('[SAATIRIL OP] Hand confirmed — waiting for hand to leave')
        },
        onPalmLeft: () => {
          // PHOTOBOOTH TRIGGER: hand left frame → start timer/capture
          console.log('[SAATIRIL OP] Hand left frame → triggering shutter')
          handleCaptureClickRef.current()
        },
      })
    } else if (!palmTriggerActive && palm.isRunning) {
      palm.stopDetection()
    }
  }, [palmTriggerActive, palm.status, cameraAvailable, hasActiveTarget])

  // ── Cleanup on unmount ───────────────────────────────────────────────────
  useEffect(() => {
    return () => {
      cancelTimer()
      if (ai.isRunning) ai.stopDetection()
      if (palm.isRunning) palm.stopDetection()
    }
  }, [])

  // ── Shutter mode: determine if capture button should trigger timer or direct capture
  const handleCaptureButtonClick = useCallback(() => {
    if (isTimerMode(effectiveShutterMode)) {
      if (timerActiveRef.current) {
        // Cancel running timer
        cancelTimer()
      } else {
        // Start timer
        startTimer()
      }
    } else {
      // Manual mode — direct capture
      handleCapture()
    }
  }, [effectiveShutterMode, startTimer, cancelTimer, handleCapture])

  // Keep handleCaptureClickRef in sync with the latest handler
  useEffect(() => { handleCaptureClickRef.current = handleCaptureButtonClick }, [handleCaptureButtonClick])

  // ── Keyboard shortcuts (physical shutter for camera operator) ──────────────
  // Space / Enter → trigger capture (same as clicking the FOTO button)
  // Esc           → cancel a running countdown timer
  // F             → toggle app-wide fullscreen (hands-free operation)
  const cancelTimerRef = useRef(cancelTimer)
  useEffect(() => { cancelTimerRef.current = cancelTimer }, [cancelTimer])

  // Fullscreen is now managed by the parent (main-app) via props
  const isFullscreen = isAppFullscreen
  const toggleFullscreen = useCallback(() => {
    onToggleAppFullscreen?.()
  }, [onToggleAppFullscreen])
  const exitFullscreen = useCallback(() => {
    if (document.fullscreenElement) {
      document.exitFullscreen?.().catch(() => {})
    }
    onToggleAppFullscreen?.()
  }, [onToggleAppFullscreen])

  useEffect(() => {
    // Keyboard shortcut handler

    const isTypingTarget = (el: EventTarget | null): boolean => {
      if (!(el instanceof HTMLElement)) return false
      const tag = el.tagName
      return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
    }

    const onKeyDown = (e: KeyboardEvent) => {
      if (isTypingTarget(e.target)) return

      if (e.key === 'Escape') {
        if (timerActiveRef.current) {
          e.preventDefault()
          cancelTimerRef.current()
        }
        return
      }

      if (e.key === ' ' || e.key === 'Enter') {
        e.preventDefault()
        handleCaptureClickRef.current()
        return
      }

      if (e.key === 'f' || e.key === 'F') {
        e.preventDefault()
        toggleFullscreen()
        return
      }
    }

    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [toggleFullscreen])

  // ── Progress badge text ──────────────────────────────────────────────────
  const progressText = useMemo(() => {
    if (!hasActiveTarget) return 'Menunggu Arahan MC...'
    if (effectiveTimerCountdown > 0) return `Timer: ${effectiveTimerCountdown}s`
    if (effectiveShutterMode === 'ai' && ai.isRunning) {
      if (ai.momentState === 'toga_possible' || ai.momentState === 'toga_sustained') return 'AI: Toga terdeteksi...'
      if (ai.momentState === 'ijazah_possible' || ai.momentState === 'ijazah_sustained') return 'AI: Ijazah terdeteksi...'
    }
    if (palmTriggerEnabled && palm.isRunning) {
      if (palm.palmState === 'triggered') return 'Timer dimulai — bereskan pose...'
      if (palm.palmState === 'confirmed') return 'Tangan terdeteksi — lepaskan untuk mulai'
      if (palm.palmState === 'hand_detected') return 'Tangan terdeteksi...'
      if (palm.palmState === 'searching') return 'Menunggu tangan...'
    }
    if (photoshoot) {
      if (capturePhase === 'ready-1') return 'Siap Foto'
      if (capturePhase === 'sending') return 'Mengirim...'
    } else {
      if (capturePhase === 'ready-1') return 'Siap Foto 1'
      if (capturePhase === 'ready-2') return 'Pose 1 OK - Siap Foto 2'
      if (capturePhase === 'sending') return 'Mengirim...'
    }
    return 'Menunggu Arahan MC...'
  }, [hasActiveTarget, capturePhase, photoshoot, effectiveTimerCountdown, effectiveShutterMode, ai.isRunning, ai.momentState, palmTriggerEnabled, palm.isRunning, palm.palmState])

  // ── Render helpers ───────────────────────────────────────────────────────
  const getRowStyle = (student: Student): React.CSSProperties => {
    const isActive = student.status === `active_${myChannel}`
    const isSent = student.status === 'sent'
    const isNext = !photoshoot && student.id === nextPending?.id && student.status === 'pending'
    const isDone = student.status === 'done'
    if (isActive) return {
      // Active participant being photographed — MOST prominent so the operator
      // can compare this name with the one MC sent. Full gold bg + thick border
      // + strong glow + bold text.
      backgroundColor: `${THEME.gold}33`,
      borderLeft: `6px solid ${THEME.gold}`,
      boxShadow: `0 0 20px ${THEME.gold}66`,
      fontWeight: 700,
    }
    if (isSent) return { backgroundColor: `${THEME.cyan}11`, borderLeft: `4px solid ${THEME.cyan}` }
    if (isNext) return {
      // Top pending — button-like (matches MC panel) for visual consistency.
      backgroundColor: `${THEME.gold}1a`,
      borderLeft: `5px solid ${THEME.gold}`,
      boxShadow: `0 0 14px ${THEME.gold}44`,
      fontWeight: 600,
    }
    if (isDone) return { backgroundColor: '#22c55e0d', opacity: 0.55, borderLeft: `4px solid #22c55e66` }
    return { backgroundColor: THEME.panel, borderLeft: `4px solid ${THEME.border}` }
  }

  // ── Minimalist status dot (for queue list — matches MC panel style) ────
  const renderStatusDot = (status: StudentStatus) => {
    if (status === 'done') return <span className="size-2 rounded-full shrink-0" style={{ backgroundColor: '#4ade80', boxShadow: '0 0 4px #4ade8066' }} title="Selesai" />
    if (status === 'sent') return <span className="size-2 rounded-full shrink-0" style={{ backgroundColor: THEME.cyan, boxShadow: `0 0 4px ${THEME.cyan}66` }} title="Dikirim" />
    if (isActiveStatus(status)) return <span className="size-2 rounded-full shrink-0 animate-pulse" style={{ backgroundColor: THEME.gold, boxShadow: `0 0 6px ${THEME.gold}88` }} title={statusLabel(status)} />
    return <span className="size-2 rounded-full shrink-0" style={{ backgroundColor: THEME.border }} title="Menunggu" />
  }

  // ── Shutter mode selector ────────────────────────────────────────────────
  const renderShutterModeSelector = (compact = false) => {
    const availableModes = SHUTTER_MODES.filter((m) => {
      if (m.modesAllowed && !m.modesAllowed.includes(mode as CameraMode)) return false
      return true
    })

    return (
      <div className={`flex flex-col gap-1.5 min-w-0 ${compact ? '' : ''}`}>
        <p className={`font-semibold uppercase tracking-widest truncate ${compact ? 'text-[8px]' : 'text-[9px]'}`} style={{ color: THEME.muted }}>
          Mode Shutter
        </p>
        {/* Row 1: Shutter mode buttons */}
        <div className={`flex flex-wrap gap-1`}>
          {availableModes.map((m) => {
            const isActive = effectiveShutterMode === m.id
            const isLoading = (m.id === 'ai' && (ai.status === 'loading_scripts' || ai.status === 'loading_model'))

            return (
              <button
                key={m.id}
                onClick={() => {
                  if (!isLoading) {
                    setShutterMode(m.id)
                    cancelTimer()
                  }
                }}
                className={`flex items-center gap-0.5 rounded-md font-semibold transition-all duration-200 cursor-pointer min-w-0 ${
                  compact ? 'px-1 py-0.5 text-[8px]' : 'px-2 py-1.5 text-[10px]'
                } ${isActive ? 'scale-105' : 'hover:bg-white/5'}`}
                style={{
                  backgroundColor: isActive ? `${THEME.gold}33` : THEME.panel,
                  color: isActive ? THEME.gold : THEME.muted,
                  border: `1px solid ${isActive ? THEME.gold : THEME.border}`,
                  boxShadow: isActive ? `0 0 8px ${THEME.gold}22` : 'none',
                  opacity: isLoading ? 0.6 : 1,
                }}
                title={m.label}
              >
                {isLoading ? <Loader2 className="size-3 animate-spin" /> : m.icon}
                <span>{m.shortLabel}</span>
              </button>
            )
          })}
        </div>
        {/* Row 2: Palm trigger toggle (separate row for clarity — prevents overlap) */}
        {effectiveShutterMode !== 'ai' && (
          <button
            onClick={() => {
              setPalmTriggerEnabled((v) => !v)
              if (palmTriggerEnabled) palm.stopDetection()
            }}
            className={`flex items-center gap-1 rounded-md font-semibold transition-all duration-200 cursor-pointer w-full min-w-0 ${
              compact ? 'px-1 py-0.5 text-[8px]' : 'px-2 py-1.5 text-[10px]'
            } ${palmTriggerEnabled ? 'scale-[1.02]' : 'hover:bg-white/5'}`}
            style={{
              backgroundColor: palmTriggerEnabled ? '#22c55e22' : THEME.panel,
              color: palmTriggerEnabled ? '#4ade80' : THEME.muted,
              border: `1px solid ${palmTriggerEnabled ? '#22c55e' : THEME.border}`,
              boxShadow: palmTriggerEnabled ? '0 0 8px #22c55e22' : 'none',
            }}
            title="Trigger Tangan — tunjukkan tangan, lalu lepaskan untuk memulai timer/foto"
          >
            {palm.status === 'loading_scripts' || palm.status === 'loading_model' ? (
              <Loader2 className="size-3 animate-spin" />
            ) : (
              <Hand className="size-3" />
            )}
            <span className="font-bold">Trigger Tangan</span>
            {palmTriggerEnabled && (
              <>
                <span style={{ color: THEME.muted }}>•</span>
                <span className={`text-[8px] ${compact ? '' : 'text-[9px]'}`} style={{
                  color: palm.palmState === 'triggered' ? '#4ade80' : palm.palmState === 'confirmed' ? '#4ade80' : palm.palmState === 'hand_detected' ? THEME.gold : THEME.muted
                }}>
                  {palm.palmState === 'triggered'
                    ? 'Timer dimulai ✓'
                    : palm.palmState === 'confirmed'
                      ? 'Lepas tangan = mulai'
                      : palm.palmState === 'hand_detected'
                        ? 'Tangan terdeteksi...'
                        : palm.isRunning
                          ? 'Tunjukkan tangan'
                          : ''}
                </span>
              </>
            )}
          </button>
        )}
        {/* Row 3: Camera mirror toggle (flip horizontal) — mirrors the live
            preview via transform: scaleX(-1). Does NOT affect the captured
            photo (the canvas draw uses the un-mirrored frame). */}
        <button
          onClick={() => setMirror((v) => !v)}
          className={`flex items-center gap-1 rounded-md font-semibold transition-all duration-200 cursor-pointer w-full min-w-0 ${
            compact ? 'px-1 py-0.5 text-[8px]' : 'px-2 py-1.5 text-[10px]'
          } ${mirror ? 'scale-[1.02]' : 'hover:bg-white/5'}`}
          style={{
            backgroundColor: mirror ? `${THEME.gold}22` : THEME.panel,
            color: mirror ? THEME.gold : THEME.muted,
            border: `1px solid ${mirror ? THEME.gold : THEME.border}`,
            boxShadow: mirror ? `0 0 8px ${THEME.gold}22` : 'none',
          }}
          title="Mirror Kamera — balik preview secara horizontal (tidak pengaruhi foto yang disimpan)"
        >
          <FlipHorizontal2 className="size-3" />
          <span className="font-bold">Mirror</span>
          {mirror && (
            <>
              <span style={{ color: THEME.muted }}>•</span>
              <span className={`text-[8px] ${compact ? '' : 'text-[9px]'}`} style={{ color: THEME.gold }}>
                Aktif ✓
              </span>
            </>
          )}
        </button>

        {/* Row 4: Live Mirror broadcast — streams the camera frames at ~10 FPS
            to the /mirror page (opened on a separate device or 2nd monitor) so
            participants can see themselves + the countdown + the photo flash
            without disturbing the operator's panel. Independent of the local
            Mirror toggle (Row 3) which only flips the operator's preview. */}
        <button
          onClick={() => setLiveMirror((v) => !v)}
          className={`flex items-center gap-1 rounded-md font-semibold transition-all duration-200 cursor-pointer w-full min-w-0 ${
            compact ? 'px-1 py-0.5 text-[8px]' : 'px-2 py-1.5 text-[10px]'
          } ${liveMirror ? 'scale-[1.02]' : 'hover:bg-white/5'}`}
          style={{
            backgroundColor: liveMirror ? `${THEME.cyan}22` : THEME.panel,
            color: liveMirror ? THEME.cyan : THEME.muted,
            border: `1px solid ${liveMirror ? THEME.cyan : THEME.border}`,
            boxShadow: liveMirror ? `0 0 8px ${THEME.cyan}22` : 'none',
          }}
          title={`Live Mirror — stream kamera ke layar peserta.

Buka di perangkat lain (tablet / HP / laptop 2 / projector):
  http://<IP-LAPTOP-INI>:81/mirror?channel=${myChannel}
Atau via Caddy gateway:
  http://<IP-LAPTOP-INI>/mirror?channel=${myChannel}&XTransformPort=81

  ?channel=${myChannel}  — saluran kamera ini
  ?flip=0         — nonaktifkan mirror (default: flipped = bercmin)
  ?ip=...         — override IP socket.io

Klik layar untuk fullscreen. Tombol: F = fullscreen, M = flip mirror.`}
        >
          <Cast className="size-3" />
          <span className="font-bold">Live Mirror</span>
          {liveMirror && (
            <>
              <span style={{ color: THEME.muted }}>•</span>
              <span className={`text-[8px] ${compact ? '' : 'text-[9px]'}`} style={{ color: THEME.cyan }}>
                ch{myChannel} ✓
              </span>
            </>
          )}
        </button>

        {/* Live Mirror URL — copy-paste-ready link with the laptop's LAN IP,
            shown ONLY when Live Mirror is ON. Click 'Copy' to copy to
            clipboard; the field is read-only so the operator can also
            manually select + Ctrl+C if needed. */}
        {liveMirror && (
          <>
          <div
            className={`flex items-stretch gap-1 min-w-0 ${compact ? 'mt-1' : 'mt-1.5'}`}
          >
            <input
              type="text"
              readOnly
              value={mirrorUrl}
              placeholder="Memuat URL…"
              onClick={(e) => (e.target as HTMLInputElement).select()}
              className={`flex-1 min-w-0 px-2 rounded-md font-mono bg-[#1a0b2e] text-white border outline-none cursor-text
                ${compact ? 'text-[8px] py-0.5' : 'text-[9px] py-1'}
                ${mirrorCopied ? 'border-[#4ade80]/70' : 'border-[#533485]'}`}
              style={{
                boxShadow: mirrorCopied ? '0 0 6px rgba(74, 222, 128, 0.35)' : 'none',
              }}
              title="URL Live Mirror — klik untuk select all, lalu Ctrl+C"
            />
            <button
              type="button"
              onClick={copyMirrorLink}
              disabled={!mirrorUrl}
              className={`flex items-center justify-center gap-1 rounded-md font-semibold transition-all duration-200 shrink-0 cursor-pointer
                ${compact ? 'px-1.5 py-0.5 text-[8px]' : 'px-2 py-1 text-[9px]'}
                ${mirrorCopied ? 'hover:scale-[1.02]' : 'hover:bg-white/5'}`}
              style={{
                backgroundColor: mirrorCopied ? `${THEME.emerald}22` : THEME.panel,
                color: mirrorCopied ? THEME.emerald : THEME.cyan,
                border: `1px solid ${mirrorCopied ? THEME.emerald : THEME.cyan + '55'}`,
              }}
              title="Salin URL ke clipboard"
            >
              {mirrorCopied ? (
                <>
                  <Check className={compact ? 'size-2.5' : 'size-3'} />
                  <span>Disalin</span>
                </>
              ) : (
                <>
                  <Copy className={compact ? 'size-2.5' : 'size-3'} />
                  <span>Copy</span>
                </>
              )}
            </button>
          </div>

          {/* Buka/Tutup Projector buttons — only in Electron runtime. Opens
              or closes a separate BrowserWindow on the external display
              (HDMI) via Electron IPC. Two buttons side-by-side so the
              user can manually open/close the projector window without
              having to toggle Live Mirror off + on. */}
          {isElectronRuntime && (
            <div className={`flex gap-1 w-full ${compact ? 'mt-1' : 'mt-1.5'}`}>
              <button
                type="button"
                onClick={handleOpenOnProjector}
                disabled={projectorOpening}
                className={`flex items-center justify-center gap-1 rounded-md font-bold transition-all duration-200 flex-1 cursor-pointer
                  ${compact ? 'px-1.5 py-1 text-[9px]' : 'px-2 py-1.5 text-[10px]'}
                  ${projectorOpening ? 'opacity-60 cursor-wait' : 'hover:scale-[1.01]'}`}
                style={{
                  backgroundColor: `${THEME.cyan}22`,
                  color: THEME.cyan,
                  border: `1px solid ${THEME.cyan}55`,
                }}
                title={`Buka window Live Mirror di external display (projector via HDMI).

PRASYARAT:
1. Hubungkan projector via HDMI ke laptop ini.
2. Windows + P → pilih "Extend" (BUKAN Duplicate).
3. Klik tombol ini → Electron otomatis buka window di projector,
   fullscreen + frameless. Window menampilkan live camera +
   countdown saja (tidak ada UI Operator).

Kalau tombol tidak aktif atau muncul error "Tidak ada layar
eksternal" → cek koneksi HDMI + pastikan Windows di mode Extend.`}
              >
                <Monitor className={compact ? 'size-3' : 'size-3.5'} />
                <span>Buka</span>
              </button>
              <button
                type="button"
                onClick={handleCloseProjector}
                disabled={projectorClosing}
                className={`flex items-center justify-center gap-1 rounded-md font-bold transition-all duration-200 flex-1 cursor-pointer
                  ${compact ? 'px-1.5 py-1 text-[9px]' : 'px-2 py-1.5 text-[10px]'}
                  ${projectorClosing ? 'opacity-60 cursor-wait' : 'hover:scale-[1.01]'}`}
                style={{
                  backgroundColor: `${THEME.red}22`,
                  color: THEME.red,
                  border: `1px solid ${THEME.red}55`,
                }}
                title="Tutup window Live Mirror di external display. Window juga otomatis tertutup saat kembali ke halaman depan / membuat project baru."
              >
                <PowerOff className={compact ? 'size-3' : 'size-3.5'} />
                <span>Tutup</span>
              </button>
            </div>
          )}
          </>
        )}
      </div>
    )
  }

  // ── Gridline settings ──────────────────────────────────────────────────────
  const renderGridlineSettings = (compact = false) => {
    const colorSwatches: { id: typeof gridlineColor; color: string; label: string }[] = [
      { id: 'white', color: '#ffffff', label: 'Putih' },
      { id: 'yellow', color: '#facc15', label: 'Kuning' },
      { id: 'red', color: '#ef4444', label: 'Merah' },
      { id: 'cyan', color: '#06b6d4', label: 'Cyan' },
      { id: 'green', color: '#22c55e', label: 'Hijau' },
    ]

    const typeOptions: { id: typeof gridlineType; label: string; icon: React.ReactNode }[] = [
      { id: 'thirds', label: '1/3', icon: <List className="size-3" /> },
      { id: 'quarters', label: '1/4', icon: <List className="size-3" /> },
      { id: 'crosshair', label: '+', icon: <Aperture className="size-3" /> },
      { id: 'diagonal', label: 'X', icon: <Frame className="size-3" /> },
    ]

    return (
      <div className={`flex flex-col gap-1 min-w-0`}>
        <div className="flex items-center justify-between min-w-0">
          <p className={`font-semibold uppercase tracking-widest truncate ${compact ? 'text-[8px]' : 'text-[9px]'}`} style={{ color: THEME.muted }}>
            Gridline
          </p>
          <button
            onClick={() => setGridlineEnabled((v) => !v)}
            className="flex items-center gap-1 cursor-pointer"
            title={gridlineEnabled ? 'Sembunyikan gridline' : 'Tampilkan gridline'}
          >
            <div
              className={`rounded-full transition-all duration-200 ${compact ? 'w-6 h-3.5' : 'w-7 h-4'}`}
              style={{
                backgroundColor: gridlineEnabled ? THEME.gold : THEME.border,
                position: 'relative',
              }}
            >
              <div
                className={`rounded-full bg-white transition-all duration-200 ${compact ? 'size-2.5' : 'size-3'}`}
                style={{
                  position: 'absolute',
                  top: '50%',
                  transform: `translateY(-50%) translateX(${gridlineEnabled ? (compact ? '10px' : '14px') : '1px'})`,
                  transition: 'transform 0.2s ease',
                }}
              />
            </div>
          </button>
        </div>

        {gridlineEnabled && (
          <>
            {/* Grid type selector */}
            <div className="flex flex-wrap gap-1">
              {typeOptions.map((t) => (
                <button
                  key={t.id}
                  onClick={() => setGridlineType(t.id)}
                  className={`flex items-center justify-center gap-0.5 rounded-md font-semibold transition-all duration-200 cursor-pointer min-w-0 ${
                    compact ? 'px-1 py-0.5 text-[8px]' : 'px-2 py-1.5 text-[10px]'
                  } ${gridlineType === t.id ? 'scale-105' : 'hover:bg-white/5'}`}
                  style={{
                    backgroundColor: gridlineType === t.id ? `${THEME.gold}33` : THEME.panel,
                    color: gridlineType === t.id ? THEME.gold : THEME.muted,
                    border: `1px solid ${gridlineType === t.id ? THEME.gold : THEME.border}`,
                  }}
                  title={t.label}
                >
                  {t.icon}
                  <span>{t.label}</span>
                </button>
              ))}
            </div>

            {/* Thickness selector */}
            <div className="flex items-center gap-1.5">
              <span className={`font-medium ${compact ? 'text-[8px]' : 'text-[9px]'}`} style={{ color: THEME.muted }}>
                Ketebalan
              </span>
              <div className="flex gap-1">
                {[1, 2, 3].map((t) => (
                  <button
                    key={t}
                    onClick={() => setGridlineThickness(t as 1 | 2 | 3)}
                    className={`flex items-center justify-center rounded-md transition-all duration-200 cursor-pointer ${
                      compact ? 'w-6 h-5' : 'w-7 h-6'
                    }`}
                    style={{
                      backgroundColor: gridlineThickness === t ? `${THEME.gold}33` : THEME.panel,
                      border: `1px solid ${gridlineThickness === t ? THEME.gold : THEME.border}`,
                    }}
                    title={`Ketebalan ${t}`}
                  >
                    <div
                      className="rounded-full"
                      style={{
                        backgroundColor: gridlineThickness === t ? THEME.gold : THEME.muted,
                        width: `${t * 2 + 1}px`,
                        height: `${t * 2 + 1}px`,
                      }}
                    />
                  </button>
                ))}
              </div>
            </div>

            {/* Color selector */}
            <div className="flex items-center gap-1.5">
              <span className={`font-medium ${compact ? 'text-[8px]' : 'text-[9px]'}`} style={{ color: THEME.muted }}>
                Warna
              </span>
              <div className="flex gap-1">
                {colorSwatches.map((c) => (
                  <button
                    key={c.id}
                    onClick={() => setGridlineColor(c.id)}
                    className={`rounded-full transition-all duration-200 cursor-pointer ${
                      compact ? 'size-4' : 'size-5'
                    }`}
                    style={{
                      backgroundColor: c.color,
                      border: `2px solid ${gridlineColor === c.id ? THEME.gold : 'transparent'}`,
                      boxShadow: gridlineColor === c.id ? `0 0 6px ${c.color}66` : 'none',
                      opacity: gridlineColor === c.id ? 1 : 0.5,
                    }}
                    title={c.label}
                  />
                ))}
              </div>
            </div>
          </>
        )}
      </div>
    )
  }

  // ── Operator queue with search (photoshoot mode) ────────────────────────
  const renderOpSearch = (compact = false) => {
    if (!photoshoot) return null
    return (
      <Card
        className="shrink-0 border rounded-lg overflow-hidden min-w-0"
        style={{ backgroundColor: THEME.card, borderColor: THEME.gold }}
      >
        <CardContent className={compact ? 'p-1.5 space-y-1 min-w-0' : 'p-2.5 space-y-2'}>
          <div className="flex items-center justify-between min-w-0">
            <p
              className="text-[9px] font-semibold uppercase tracking-widest truncate"
              style={{ color: THEME.gold }}
            >
              <List className="size-3 inline mr-1" />
              Antre dari MC ({opQueue.length})
            </p>
          </div>

          <div className="relative">
            <Search className="absolute left-2 top-1/2 -translate-y-1/2 size-3.5" style={{ color: THEME.muted }} />
            <Input
              placeholder="Cari NIM / Nama di antrean..."
              value={opSearchQuery}
              onChange={(e) => setOpSearchQuery(e.target.value)}
              className={`pl-7 border-[#533485] bg-[#2a164a] text-white placeholder:text-[#533485] focus-visible:border-[#d4af37] focus-visible:ring-[#d4af37]/30 ${compact ? 'h-7 text-[11px]' : 'h-8 text-xs'}`}
            />
          </div>

          {opQueue.length === 0 ? (
            <p className={`text-center ${compact ? 'text-[9px] py-1' : 'text-[10px] py-1.5'}`} style={{ color: THEME.muted }}>
              {opSearchQuery.trim() ? 'Tidak ditemukan di antrean' : 'Belum ada peserta dikirim MC'}
            </p>
          ) : (
            <div
              className={`overflow-y-auto rounded-md border ${compact ? 'max-h-32' : 'max-h-48'}`}
              style={{ borderColor: THEME.border }}
            >
              {opSearchResults.map((student) => (
                <button
                  key={student.id}
                  onClick={() => {
                    if (!sending) {
                      setOpCurrentTarget(student)
                      setOpSearchQuery('')
                    }
                  }}
                  className={`w-full flex items-center gap-1.5 px-2 text-left transition-colors hover:bg-white/5 cursor-pointer ${compact ? 'py-1' : 'py-1.5'} ${opCurrentTarget?.id === student.id ? 'bg-[#d4af37]/10' : ''}`}
                  style={{
                    borderBottom: `1px solid ${THEME.border}44`,
                    borderLeft: opCurrentTarget?.id === student.id ? `3px solid ${THEME.gold}` : `3px solid transparent`,
                  }}
                >
                  <span className={`font-mono truncate shrink-0 ${compact ? 'text-[9px] w-12' : 'text-[10px] w-14'}`} style={{ color: THEME.muted }}>
                    {student.nim}
                  </span>
                  <span className={`font-medium truncate flex-1 ${compact ? 'text-[10px]' : 'text-[11px]'}`} style={{ color: opCurrentTarget?.id === student.id ? THEME.gold : '#ffffff' }}>
                    {student.nama}
                  </span>
                  {opCurrentTarget?.id === student.id && (
                    <Camera className="size-3 shrink-0" style={{ color: THEME.gold }} />
                  )}
                </button>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    )
  }

  // ── Gridline color map ────────────────────────────────────────────────────
  const GRIDLINE_COLORS: Record<string, { stroke: string; opacity: number }> = {
    white:  { stroke: '#ffffff', opacity: 0.25 },
    yellow: { stroke: '#facc15', opacity: 0.35 },
    red:    { stroke: '#ef4444', opacity: 0.35 },
    cyan:   { stroke: '#06b6d4', opacity: 0.35 },
    green:  { stroke: '#22c55e', opacity: 0.35 },
  }

  // ── Gridline SVG renderer ─────────────────────────────────────────────────
  const renderGridlineSVG = () => {
    const { stroke, opacity } = GRIDLINE_COLORS[gridlineColor] || GRIDLINE_COLORS.white
    const sw = gridlineThickness * 0.15 // stroke width in viewBox units (0.15, 0.3, 0.45)
    const commonProps = { stroke, strokeWidth: sw, opacity, fill: 'none' }

    switch (gridlineType) {
      case 'thirds':
        return (
          <>
            <line x1="33.333" y1="0" x2="33.333" y2="100" {...commonProps} />
            <line x1="66.666" y1="0" x2="66.666" y2="100" {...commonProps} />
            <line x1="0" y1="33.333" x2="100" y2="33.333" {...commonProps} />
            <line x1="0" y1="66.666" x2="100" y2="66.666" {...commonProps} />
          </>
        )
      case 'quarters':
        return (
          <>
            <line x1="25" y1="0" x2="25" y2="100" {...commonProps} />
            <line x1="50" y1="0" x2="50" y2="100" {...commonProps} />
            <line x1="75" y1="0" x2="75" y2="100" {...commonProps} />
            <line x1="0" y1="25" x2="100" y2="25" {...commonProps} />
            <line x1="0" y1="50" x2="100" y2="50" {...commonProps} />
            <line x1="0" y1="75" x2="100" y2="75" {...commonProps} />
          </>
        )
      case 'crosshair':
        return (
          <>
            <line x1="50" y1="0" x2="50" y2="100" {...commonProps} />
            <line x1="0" y1="50" x2="100" y2="50" {...commonProps} />
            {/* Center circle */}
            <circle cx="50" cy="50" r="8" {...commonProps} />
            <circle cx="50" cy="50" r="20" {...commonProps} strokeDasharray="2 2" />
            {/* Small tick marks */}
            <line x1="50" y1="0" x2="50" y2="5" {...commonProps} strokeWidth={sw * 2} />
            <line x1="50" y1="95" x2="50" y2="100" {...commonProps} strokeWidth={sw * 2} />
            <line x1="0" y1="50" x2="5" y2="50" {...commonProps} strokeWidth={sw * 2} />
            <line x1="95" y1="50" x2="100" y2="50" {...commonProps} strokeWidth={sw * 2} />
          </>
        )
      case 'diagonal':
        return (
          <>
            {/* Diagonal lines + thirds */}
            <line x1="0" y1="0" x2="100" y2="100" {...commonProps} />
            <line x1="100" y1="0" x2="0" y2="100" {...commonProps} />
            <line x1="33.333" y1="0" x2="33.333" y2="100" {...commonProps} opacity={opacity * 0.5} />
            <line x1="66.666" y1="0" x2="66.666" y2="100" {...commonProps} opacity={opacity * 0.5} />
            <line x1="0" y1="33.333" x2="100" y2="33.333" {...commonProps} opacity={opacity * 0.5} />
            <line x1="0" y1="66.666" x2="100" y2="66.666" {...commonProps} opacity={opacity * 0.5} />
          </>
        )
      default:
        return null
    }
  }

  // ── Shared camera view component ─────────────────────────────────────────
  const renderCameraView = () => (
    <div
      className="relative rounded-xl overflow-hidden border-2"
      style={{
        width: cameraDims.width > 0 ? cameraDims.width : undefined,
        height: cameraDims.height > 0 ? cameraDims.height : undefined,
        borderColor: hasActiveTarget ? THEME.gold : THEME.border,
        boxShadow: hasActiveTarget ? `0 0 16px ${THEME.gold}15` : 'none',
        backgroundColor: '#000000',
      }}
    >
      <video
        ref={videoRef}
        autoPlay
        playsInline
        muted
        className="absolute inset-0 w-full h-full object-cover"
        style={{
          filter: cssFilter !== 'none' ? cssFilter : undefined,
          transform: mirror ? 'scaleX(-1)' : undefined,
        }}
      />

      {frameData && (
        <img
          src={frameData}
          alt="Frame overlay"
          className="absolute inset-0 w-full h-full object-fill pointer-events-none"
          style={{ zIndex: 5 }}
        />
      )}

      {/* Gridline overlay */}
      {gridlineEnabled && (
        <svg
          className="absolute inset-0 w-full h-full pointer-events-none"
          style={{ zIndex: 6 }}
          viewBox="0 0 100 100"
          preserveAspectRatio="none"
        >
          {renderGridlineSVG()}
        </svg>
      )}

      {/* Timer countdown overlay */}
      {effectiveTimerCountdown > 0 && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none" style={{ zIndex: 15 }}>
          <div className="flex items-center justify-center" style={{
            width: isMobile ? '80px' : '120px',
            height: isMobile ? '80px' : '120px',
            borderRadius: '50%',
            backgroundColor: `${THEME.bg}cc`,
            border: `4px solid ${THEME.gold}`,
            boxShadow: `0 0 40px ${THEME.gold}66, 0 0 80px ${THEME.gold}33`,
          }}>
            <span className="font-bold" style={{
              color: THEME.gold,
              fontSize: isMobile ? '36px' : '56px',
              textShadow: `0 0 20px ${THEME.gold}88`,
            }}>
              {effectiveTimerCountdown}
            </span>
          </div>
        </div>
      )}

      {/* Palm trigger indicator */}
      {palmTriggerEnabled && palm.isRunning && (
        <div className="absolute top-2 right-2 flex items-center gap-1.5 pointer-events-none" style={{ zIndex: 10 }}>
          <div className="flex items-center gap-1 rounded-full px-2 py-1" style={{
            backgroundColor: palm.palmState === 'triggered' ? '#22c55e88' : palm.palmState === 'confirmed' ? '#22c55e88' : palm.palmState === 'hand_detected' ? '#d4af3788' : 'rgba(0,0,0,0.7)',
            border: `1px solid ${palm.palmState === 'triggered' ? '#22c55e' : palm.palmState === 'confirmed' ? '#22c55e' : palm.palmState === 'hand_detected' ? THEME.gold : THEME.border}`,
          }}>
            <Hand className={`size-3 ${palm.palmState === 'hand_detected' ? 'animate-pulse' : ''}`} style={{ color: palm.palmState === 'triggered' ? '#4ade80' : palm.palmState === 'confirmed' ? '#4ade80' : palm.palmState === 'hand_detected' ? THEME.gold : THEME.muted }} />
            <span className="text-[10px] font-bold" style={{ color: palm.palmState === 'confirmed' ? '#4ade80' : THEME.muted }}>
              {palm.palmState === 'triggered' ? 'Timer ✓' : palm.palmState === 'confirmed' ? 'Siap ✓' : palm.palmState === 'hand_detected' ? 'Tangan...' : 'Tangan'}
            </span>
          </div>
        </div>
      )}

      {/* AI detection indicator */}
      {effectiveShutterMode === 'ai' && ai.isRunning && (
        <div className="absolute top-2 right-2 flex items-center gap-1.5 pointer-events-none" style={{ zIndex: 10 }}>
          <div className="flex items-center gap-1 rounded-full px-2 py-1" style={{
            backgroundColor: ai.momentState !== 'idle' ? '#d4af3788' : 'rgba(0,0,0,0.7)',
            border: `1px solid ${ai.momentState !== 'idle' ? THEME.gold : THEME.border}`,
          }}>
            <Sparkles className={`size-3 ${ai.momentState !== 'idle' ? 'animate-pulse' : ''}`} style={{ color: ai.momentState !== 'idle' ? THEME.gold : THEME.muted }} />
            <span className="text-[10px] font-bold" style={{ color: ai.momentState !== 'idle' ? THEME.gold : THEME.muted }}>
              AI {ai.posesDetected > 0 ? `(${ai.posesDetected})` : ''}
            </span>
          </div>
        </div>
      )}

      {/* Flash overlay */}
      <div
        ref={flashRef}
        className="absolute inset-0 transition-opacity duration-150 pointer-events-none"
        style={{
          backgroundColor: '#ffffff',
          opacity: flashVisible ? 0.85 : 0,
          zIndex: 20,
        }}
      />

      {/* NO CAMERA SIGNAL */}
      {!cameraAvailable && (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-black/80" style={{ zIndex: 8 }}>
          <VideoOff className={isMobile ? 'size-8' : 'size-12'} style={{ color: THEME.border }} />
          <p className={`font-semibold tracking-wider ${isMobile ? 'text-xs' : 'text-sm'}`} style={{ color: THEME.muted }}>
            NO CAMERA SIGNAL
          </p>
          <p className="text-[10px]" style={{ color: THEME.border }}>
            Pastikan kamera terhubung dan izin diberikan
          </p>
        </div>
      )}

      {/* Aspect ratio & frame badges */}
      <div className="absolute top-2 left-2 flex gap-1.5" style={{ zIndex: 10 }}>
        <Badge className="text-[9px] px-1.5 py-0.5 border-0" style={{ backgroundColor: 'rgba(0,0,0,0.6)', color: THEME.muted }}>
          {config?.ratio ?? '4:3'}
        </Badge>
        {frameData && (
          <Badge className="text-[9px] px-1.5 py-0.5 border-0" style={{ backgroundColor: 'rgba(0,0,0,0.6)', color: THEME.gold }}>
            <Frame className="size-2.5 mr-0.5" />Frame
          </Badge>
        )}
        {gridlineEnabled && (
          <Badge className="text-[9px] px-1.5 py-0.5 border-0" style={{ backgroundColor: 'rgba(0,0,0,0.6)', color: GRIDLINE_COLORS[gridlineColor]?.stroke ?? '#ffffff' }}>
            <Grid3x3 className="size-2.5 mr-0.5" />{gridlineType === 'thirds' ? '1/3' : gridlineType === 'quarters' ? '1/4' : gridlineType === 'crosshair' ? '+' : 'X'}
          </Badge>
        )}
      </div>

      {/* Mobile: Switch camera button */}
      {isMobile && videoDevices.length > 1 && (
        <button
          onClick={handleSwitchCamera}
          className="absolute top-2 right-2 flex items-center justify-center w-9 h-9 rounded-full bg-black/60 backdrop-blur-sm cursor-pointer active:scale-95 transition-transform"
          style={{ zIndex: 10 }}
          title="Ganti kamera"
        >
          <SwitchCamera className="size-4" style={{ color: THEME.gold }} />
        </button>
      )}

      <canvas ref={canvasRef} className="hidden" />
    </div>
  )

  // ── Capture button (shared) ──────────────────────────────────────────────
  const renderCaptureButton = (size: 'normal' | 'large' | 'xl' = 'normal') => {
    const btnClass = size === 'xl'
      ? 'w-full h-16 sm:h-20 text-lg sm:text-xl font-bold cursor-pointer rounded-xl transition-all duration-200 active:scale-[0.97] shadow-lg'
      : size === 'large'
        ? 'w-full h-14 text-base font-bold cursor-pointer rounded-lg transition-all duration-200 active:scale-[0.97]'
        : 'w-full h-12 text-sm font-bold cursor-pointer rounded-lg transition-all duration-200 hover:scale-[1.01] active:scale-[0.99]'
    // Icon size scales with button size so the shutter is easy to hit
    const iconCls = size === 'xl' ? 'size-6 sm:size-7' : 'size-4'

    if (capturePhase === 'standby') {
      return (
        <Button disabled className={`${btnClass} cursor-not-allowed`} style={{ backgroundColor: THEME.panel, color: THEME.muted, border: `2px solid ${THEME.border}`, opacity: 0.6 }}>
          <Aperture className={`${iconCls} mr-2`} />STANDBY
        </Button>
      )
    }

    // Timer is counting down — show cancel button
    if (effectiveTimerCountdown > 0) {
      return (
        <Button onClick={handleCaptureButtonClick} className={btnClass} style={{
          backgroundColor: '#ef4444',
          color: '#ffffff',
          border: '2px solid #ef4444',
          boxShadow: '0 0 30px #ef444444, 0 0 60px #ef444422',
        }}>
          <X className={`${iconCls} mr-2`} />BATAL ({effectiveTimerCountdown}s)
        </Button>
      )
    }

    if (capturePhase === 'ready-1') {
      const isAutoMode = effectiveShutterMode === 'ai'
      const isTimer = isTimerMode(effectiveShutterMode)

      if (isAutoMode) {
        // AI auto-capture mode: show status button
        const isDetecting = ai.isRunning
        return (
          <Button disabled className={`${btnClass} cursor-default`} style={{
            backgroundColor: isDetecting ? '#22c55e33' : THEME.panel,
            color: isDetecting ? '#4ade80' : THEME.muted,
            border: `2px solid ${isDetecting ? '#22c55e' : THEME.border}`,
          }}>
            {isDetecting ? <Loader2 className={`${iconCls} mr-2 animate-spin`} /> : <Aperture className={`${iconCls} mr-2`} />}
            {isDetecting ? 'AI Mendeteksi Pose...' : 'AI Loading...'}
          </Button>
        )
      }

      if (isTimer) {
        return (
          <Button onClick={handleCaptureButtonClick} className={btnClass} style={{
            backgroundColor: THEME.gold,
            color: THEME.bg,
            border: `2px solid ${THEME.gold}`,
            boxShadow: `0 0 30px ${THEME.gold}44, 0 0 60px ${THEME.gold}22`,
          }}>
            <Timer className={`${iconCls} mr-2`} />
            {photoshoot ? `FOTO (${getTimerDuration(effectiveShutterMode)}s)` : `FOTO 1 — TOGA (${getTimerDuration(effectiveShutterMode)}s)`}
          </Button>
        )
      }

      // Manual mode
      return (
        <Button onClick={handleCaptureButtonClick} className={btnClass} style={photoshoot
          ? { backgroundColor: '#4ade80', color: '#1a0b2e', border: '2px solid #4ade80', boxShadow: '0 0 30px #4ade8044, 0 0 60px #4ade8022' }
          : { backgroundColor: THEME.gold, color: THEME.bg, border: `2px solid ${THEME.gold}`, boxShadow: `0 0 30px ${THEME.gold}44, 0 0 60px ${THEME.gold}22` }
        }>
          <Camera className={`${iconCls} mr-2`} />{photoshoot ? 'FOTO' : 'FOTO 1 — TOGA'}
        </Button>
      )
    }

    if (capturePhase === 'ready-2') {
      const isAutoMode = effectiveShutterMode === 'ai'
      const isTimer = isTimerMode(effectiveShutterMode)

      if (isAutoMode) {
        const isDetecting = ai.isRunning
        return (
          <Button disabled className={`${btnClass} cursor-default`} style={{
            backgroundColor: isDetecting ? '#22c55e33' : THEME.panel,
            color: isDetecting ? '#4ade80' : THEME.muted,
            border: `2px solid ${isDetecting ? '#22c55e' : THEME.border}`,
          }}>
            {isDetecting ? <Loader2 className={`${iconCls} mr-2 animate-spin`} /> : <Aperture className={`${iconCls} mr-2`} />}
            AI Mendeteksi Ijazah...
          </Button>
        )
      }

      if (isTimer) {
        return (
          <Button onClick={handleCaptureButtonClick} className={btnClass} style={{
            backgroundColor: THEME.gold,
            color: THEME.bg,
            border: `2px solid ${THEME.gold}`,
            boxShadow: `0 0 30px ${THEME.gold}44, 0 0 60px ${THEME.gold}22`,
          }}>
            <Timer className={`${iconCls} mr-2`} />
            FOTO 2 — IJAZAH ({getTimerDuration(effectiveShutterMode)}s)
          </Button>
        )
      }

      return (
        <Button onClick={handleCaptureButtonClick} className={btnClass} style={{ backgroundColor: '#22c55e', color: '#ffffff', border: '2px solid #22c55e', boxShadow: '0 0 30px #22c55e44, 0 0 60px #22c55e22' }}>
          <Camera className={`${iconCls} mr-2`} />FOTO 2 — IJAZAH
        </Button>
      )
    }

    if (capturePhase === 'sending') {
      return (
        <Button disabled className={`${btnClass} cursor-not-allowed`} style={{ backgroundColor: THEME.panel, color: THEME.muted, border: `2px solid ${THEME.border}` }}>
          <Loader2 className={`${iconCls} mr-2 animate-spin`} />MENGIRIM...
        </Button>
      )
    }

    return null
  }

  // ── Queue list (shared) ──────────────────────────────────────────────────
  // Matches MC panel's minimalist style: number + name + small dot indicator
  const renderQueueList = (compact = false) => (
    <Card
      className="flex-1 min-h-0 border rounded-lg overflow-hidden flex flex-col min-w-0"
      style={{ backgroundColor: THEME.card, borderColor: THEME.border }}
    >
      <div
        className="shrink-0 flex items-center justify-between px-1.5 py-1.5 min-w-0"
        style={{ borderBottom: `1px solid ${THEME.border}` }}
      >
        <div className="flex items-center gap-1 min-w-0">
          <h3 className="text-[10px] font-semibold shrink-0" style={{ color: '#ffffff' }}>
            Antrean
          </h3>
          <span
            className="text-[10px] font-bold px-1 py-0 rounded-full shrink-0"
            style={{
              backgroundColor: remainingCount > 10 ? `${THEME.red}33` : remainingCount > 0 ? `${THEME.gold}33` : `${THEME.border}44`,
              color: remainingCount > 10 ? THEME.red : remainingCount > 0 ? THEME.gold : THEME.muted,
              border: `1px solid ${remainingCount > 10 ? `${THEME.red}55` : remainingCount > 0 ? `${THEME.gold}55` : THEME.border}`,
            }}
          >
            {remainingCount}
          </span>
        </div>
        {!channel && channelCount(mode) > 1 ? (
          /* ── Jalur dropdown (single-panel layout, dual mode) ──
             When this OperatorPanel is rendered ONCE (no `channel` prop) but
             the project is dual mode, let the user pick which Jalur to view.
             Calls setMyChannel. In the admin's dual 50/50 layout, each
             OperatorPanel has channel={1}/{2} fixed → this branch is skipped
             (the fixed label below shows instead). */
          <select
            value={storeChannel}
            onChange={(e) => setMyChannel(Number(e.target.value))}
            className="text-[9px] shrink-0 cursor-pointer rounded-md px-1 py-0.5"
            style={{ backgroundColor: `${THEME.border}33`, color: THEME.gold, border: `1px solid ${THEME.border}` }}
            title="Pilih Jalur (channel)"
          >
            {Array.from({ length: channelCount(mode) }, (_, i) => i + 1).map((ch) => (
              <option key={ch} value={ch}>Jalur {ch}</option>
            ))}
          </select>
        ) : (
          <span className="text-[9px] shrink-0" style={{ color: THEME.muted }}>Ch.{myChannel}</span>
        )}
      </div>

      <ScrollArea className="flex-1 min-h-0">
        <div className="flex flex-col">
          {channelStudents.length === 0 ? (
            <div className="flex items-center justify-center py-8">
              <p className="text-xs" style={{ color: THEME.muted }}>Tidak ada peserta</p>
            </div>
          ) : (
            channelStudents.map((student, idx) => {
              const isActive = student.status === `active_${myChannel}`
              const isNext = student.id === nextPending?.id && student.status === 'pending'

              // Minimalist row: number + name + status dot — matches MC panel style
              return (
                <div
                  key={student.id}
                  ref={isActive ? activeRowRef : isNext ? nextRowRef : undefined}
                  className="flex items-center gap-1 px-1.5 py-1 transition-colors duration-200 min-w-0"
                  style={getRowStyle(student)}
                >
                  <span className="text-[9px] font-mono w-3 shrink-0 text-center" style={{ color: THEME.muted }}>
                    {idx + 1}
                  </span>
                  <span
                    className={`text-[10px] font-medium truncate flex-1 min-w-0 ${student.status === 'done' ? 'line-through' : ''}`}
                    style={{
                      color: isActive ? THEME.gold : student.status === 'sent' ? THEME.cyan : student.status === 'done' ? THEME.muted : '#ffffff',
                    }}
                  >
                    {student.nama}
                  </span>
                  {renderStatusDot(student.status)}
                </div>
              )
            })
          )}
        </div>
      </ScrollArea>
    </Card>
  )

  
  // ── Monitor Lock: prevents accidental clicks (view-only mode) ──
  const renderMonitorLock = () => {
    if (!monitorLocked) return null
    return (
      <div
        style={{
          position: 'fixed' as const, top: 0, left: 0, right: 0, bottom: 0,
          backgroundColor: 'rgba(26, 11, 46, 0.85)',
          zIndex: 9999,
          display: 'flex' as const, flexDirection: 'column' as const,
          alignItems: 'center' as const, justifyContent: 'center' as const,
          gap: '16px', cursor: 'pointer' as const,
        }}
        onClick={() => setMonitorLocked(false)}
      >
        <div style={{ fontSize: '48px' }}>🔒</div>
        <div style={{ fontSize: '20px', fontWeight: 'bold' as const, color: '#d4af37' }}>MODE MONITOR</div>
        <div style={{ fontSize: '12px', color: '#c4b5fd', textAlign: 'center' as const, maxWidth: '280px' }}>
          Layar terkunci. Klik untuk membuka kunci.
        </div>
        <button
          style={{ marginTop: '8px', padding: '10px 24px', background: '#d4af37', color: '#1a0b2e', border: 'none', borderRadius: '8px', fontSize: '14px', fontWeight: 'bold' as const, cursor: 'pointer' as const }}
          onClick={(e) => { e.stopPropagation(); setMonitorLocked(false) }}
        >🔓 BUKA KUNCI</button>
      </div>
    )
  }

  const renderLockToggle = () => (
    <button
      onClick={() => setMonitorLocked(!monitorLocked)}
      style={{
        position: 'fixed' as const, top: 8, right: 8, zIndex: 10000,
        background: monitorLocked ? '#4ade80' : '#2a164a',
        color: monitorLocked ? '#1a0b2e' : '#c4b5fd',
        border: monitorLocked ? '1px solid #4ade80' : '1px solid #533485',
        borderRadius: '6px', padding: '4px 10px',
        fontSize: '10px', fontWeight: 'bold' as const, cursor: 'pointer' as const,
      }}
      title={monitorLocked ? 'Buka kunci' : 'Kunci layar'}
    >
      {monitorLocked ? '🔓 Terkunci' : '🔒 Kunci'}
    </button>
  )

// ── Main render ──────────────────────────────────────────────────────────
  if (!currentProject) {
    return (
      <div className="flex items-center justify-center h-full" style={{ backgroundColor: THEME.bg, color: THEME.muted }}>
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="size-8 animate-spin" style={{ color: THEME.gold }} />
          <p className="text-sm opacity-60">Memuat proyek...</p>
        </div>
      </div>
    )
  }

  // ── Floating toolbar toggle button ──────────────────────────────────────
  const renderToolbarButton = (
    icon: React.ReactNode,
    label: string,
    isActive: boolean,
    onClick: () => void,
    badge?: React.ReactNode,
  ) => (
    <button
      onClick={onClick}
      className="flex items-center gap-1 rounded-lg px-1.5 py-1.5 text-[10px] font-semibold uppercase tracking-wider transition-all duration-200 cursor-pointer min-w-0"
      style={{
        backgroundColor: isActive ? `${THEME.gold}22` : THEME.panel,
        color: isActive ? THEME.gold : THEME.muted,
        border: `1px solid ${isActive ? THEME.gold : THEME.border}`,
        boxShadow: isActive ? `0 0 8px ${THEME.gold}22` : 'none',
      }}
      title={label}
    >
      {icon}
      <span className="truncate max-w-[50px]">{label}</span>
      {badge}
    </button>
  )

  // ── MOBILE LAYOUT ────────────────────────────────────────────────────────
  if (isMobile) {
    return (
      <div className="flex flex-col h-full touch-no-select" style={{ backgroundColor: THEME.bg }}>

        {/* ── Camera Zone ────────────────────────────────────────────────── */}
        <div
          ref={cameraZoneRef}
          className="flex-1 flex items-center justify-center min-h-0 p-1 relative"
        >
          {renderCameraView()}

          {/* ── Floating Toolbar (top-right of camera view) ──────────────── */}
          <div
            className="absolute top-2 right-2 z-20 flex items-center gap-1 rounded-xl px-1.5 py-1"
            style={{
              backgroundColor: `${THEME.panel}dd`,
              border: `1px solid ${THEME.border}`,
              backdropFilter: 'blur(12px)',
            }}
          >
            {renderToolbarButton(
              <Camera className="size-3" />,
              'Shutter',
              showShutterPanel,
              () => { setShowShutterPanel((v) => !v); setShowGridlinePanel(false); setShowQueueOnMobile(false) },
              shutterMode !== 'manual' ? (
                <span className="text-[8px] font-bold px-1 py-0.5 rounded-full" style={{ backgroundColor: `${THEME.gold}33`, color: THEME.gold, border: `1px solid ${THEME.gold}55` }}>
                  {shutterMode === 'ai' ? 'AI' : getTimerDuration(shutterMode) + 's'}
                </span>
              ) : undefined,
            )}
            {renderToolbarButton(
              <Grid3x3 className="size-3" />,
              'Grid',
              showGridlinePanel,
              () => { setShowGridlinePanel((v) => !v); setShowShutterPanel(false); setShowQueueOnMobile(false) },
              gridlineEnabled ? (
                <span className="text-[8px] font-bold px-1 py-0.5 rounded-full" style={{ backgroundColor: `${THEME.gold}33`, color: THEME.gold, border: `1px solid ${THEME.gold}55` }}>
                  {gridlineType === 'thirds' ? '⅓' : gridlineType === 'quarters' ? '¼' : '+'}
                </span>
              ) : undefined,
            )}
            {renderToolbarButton(
              <List className="size-3" />,
              'Antrean',
              showQueueOnMobile,
              () => { setShowQueueOnMobile((v) => !v); setShowShutterPanel(false); setShowGridlinePanel(false) },
              remainingCount > 0 ? (
                <span className="text-[8px] font-bold px-1 py-0.5 rounded-full" style={{
                  backgroundColor: remainingCount > 10 ? `${THEME.red}33` : `${THEME.gold}33`,
                  color: remainingCount > 10 ? THEME.red : THEME.gold,
                  border: `1px solid ${remainingCount > 10 ? `${THEME.red}55` : `${THEME.gold}55`}`,
                }}>
                  {remainingCount}
                </span>
              ) : undefined,
            )}
          </div>
        </div>

        {/* ── Capture Button (centered, directly below camera view) ─────── */}
        <div
          className="shrink-0 w-full px-3 py-2 border-t"
          style={{
            backgroundColor: `${THEME.panel}ee`,
            borderColor: THEME.border,
            backdropFilter: 'blur(12px)',
          }}
        >
          <div className="mx-auto w-full max-w-md">
            {renderCaptureButton('xl')}
          </div>
        </div>

        {/* ── Bottom Control Bar ──────────────────────────────────────────── */}
        <div
          className="shrink-0 border-t safe-bottom"
          style={{
            backgroundColor: `${THEME.panel}ee`,
            borderColor: THEME.border,
            backdropFilter: 'blur(12px)',
          }}
        >
          {/* Target info row */}
          <div className="flex items-center gap-2 px-3 py-2">
            <div
              className="shrink-0 flex items-center justify-center w-8 h-8 rounded-full border-2"
              style={{
                backgroundColor: THEME.bg,
                borderColor: hasActiveTarget ? THEME.gold : THEME.border,
              }}
            >
              <User className="size-3.5" style={{ color: hasActiveTarget ? THEME.gold : THEME.border }} />
            </div>

            <div className="flex-1 min-w-0">
              {hasActiveTarget ? (
                <div className="flex items-center gap-2">
                  <div className="min-w-0">
                    <p className="text-sm font-bold leading-tight truncate" style={{ color: '#ffffff' }}>
                      {opCurrentTarget.nama}
                    </p>
                    <p className="text-[10px] font-mono" style={{ color: THEME.muted }}>
                      {opCurrentTarget.nim}
                    </p>
                  </div>
                  <Badge
                    className={`text-[9px] px-1.5 py-0.5 shrink-0 ${hasActiveTarget && !sending ? 'animate-pulse' : ''}`}
                    style={{
                      backgroundColor:
                        capturePhase === 'ready-1' ? `${THEME.gold}33`
                          : capturePhase === 'ready-2' ? '#22c55e33'
                            : capturePhase === 'sending' ? `${THEME.border}66`
                              : `${THEME.border}44`,
                      color:
                        capturePhase === 'ready-1' ? THEME.gold
                          : capturePhase === 'ready-2' ? '#4ade80'
                            : THEME.muted,
                      border: `1px solid ${capturePhase === 'ready-1' ? `${THEME.gold}66` : capturePhase === 'ready-2' ? '#22c55e66' : THEME.border}`,
                    }}
                  >
                    {capturePhase === 'sending' && <Loader2 className="size-2.5 mr-0.5 animate-spin" />}
                    {capturePhase === 'ready-1' && <Camera className="size-2.5 mr-0.5" />}
                    {capturePhase === 'ready-2' && <CheckCircle2 className="size-2.5 mr-0.5" />}
                    {capturePhase === 'standby' && <Clock className="size-2.5 mr-0.5" />}
                    {progressText}
                  </Badge>
                </div>
              ) : (
                <p className="text-xs italic" style={{ color: THEME.muted }}>
                  Menunggu panggilan MC...
                </p>
              )}
            </div>
          </div>

          {/* Shutter Mode Panel (toggleable) */}
          {showShutterPanel && (
            <div className="px-3 py-1.5 border-t" style={{ borderColor: THEME.border }}>
              {renderShutterModeSelector(true)}
              {videoDevices.length > 1 && (
                <div className="mt-1.5">
                  <Select value={selectedDeviceId} onValueChange={setSelectedDeviceId}>
                    <SelectTrigger
                      className="text-[10px] h-7 w-full"
                      style={{ backgroundColor: THEME.bg, borderColor: THEME.border, color: THEME.muted }}
                    >
                      <Video className="size-3 mr-1 shrink-0" style={{ color: THEME.gold }} />
                      <SelectValue placeholder="Pilih Kamera" />
                    </SelectTrigger>
                    <SelectContent style={{ backgroundColor: THEME.panel, borderColor: THEME.border }}>
                      {videoDevices.map((dev) => (
                        <SelectItem key={dev.deviceId} value={dev.deviceId} style={{ color: '#ffffff' }}>
                          {dev.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}
            </div>
          )}

          {/* Gridline Settings Panel (toggleable) */}
          {showGridlinePanel && (
            <div className="px-3 py-1.5 border-t" style={{ borderColor: THEME.border }}>
              {renderGridlineSettings(true)}
            </div>
          )}

          {/* Queue Panel (toggleable) */}
          {showQueueOnMobile && (
            <div className="border-t" style={{ borderColor: THEME.border }}>
              {/* Operator search (photoshoot only) */}
              {renderOpSearch(true)}
              {/* Queue list */}
              <div style={{ maxHeight: '35vh' }}>
                {renderQueueList(true)}
              </div>
            </div>
          )}
        </div>
      </div>
    )
  }

  // ── DESKTOP LAYOUT ──────────────────────────────────────────────────────
  return (
    <div className="flex flex-col h-full overflow-hidden" style={{ backgroundColor: THEME.bg }}>
      {/* ── 4 stat pills: ANTREAN / PROSES / SELESAI / TOTAL (mirrors MC panel) ──
          Per-channel counts so operator + MC + admin pantau progres simultan
          with the same 4 categories. Always visible at the top of the panel. */}
      <div
        className="shrink-0 flex items-center gap-1.5 px-2 py-1.5 overflow-x-auto"
        style={{ backgroundColor: THEME.panel, borderBottom: `1px solid ${THEME.border}` }}
      >
        {/* ANTREAN (pending) */}
        <div className="flex flex-col items-center justify-center rounded-md shrink-0 px-2 py-0.5 min-w-[48px]" style={{ backgroundColor: `${THEME.border}33`, border: `1px solid ${THEME.muted}44` }}>
          <span className="font-bold leading-none text-sm" style={{ color: THEME.muted }}>{opAntreanCount}</span>
          <span className="uppercase tracking-wider font-semibold leading-tight text-[7px]" style={{ color: THEME.muted }}>Antrean</span>
        </div>
        {/* PROSES (sent + active) */}
        <div className="flex flex-col items-center justify-center rounded-md shrink-0 px-2 py-0.5 min-w-[48px]" style={{ backgroundColor: `#06b6d422`, border: `1px solid #06b6d444` }}>
          <span className="font-bold leading-none text-sm" style={{ color: '#06b6d4' }}>{opProsesCount}</span>
          <span className="uppercase tracking-wider font-semibold leading-tight text-[7px]" style={{ color: '#06b6d4' }}>Proses</span>
        </div>
        {/* SELESAI (done) */}
        <div className="flex flex-col items-center justify-center rounded-md shrink-0 px-2 py-0.5 min-w-[48px]" style={{ backgroundColor: `#22c55e22`, border: `1px solid #22c55e44` }}>
          <span className="font-bold leading-none text-sm" style={{ color: '#22c55e' }}>{opSelesaiCount}</span>
          <span className="uppercase tracking-wider font-semibold leading-tight text-[7px]" style={{ color: '#22c55e' }}>Selesai</span>
        </div>
        {/* TOTAL */}
        <div className="flex flex-col items-center justify-center rounded-md shrink-0 px-2 py-0.5 min-w-[48px]" style={{ backgroundColor: `${THEME.gold}22`, border: `1px solid ${THEME.gold}44` }}>
          <span className="font-bold leading-none text-sm" style={{ color: THEME.gold }}>{opTotalCount}</span>
          <span className="uppercase tracking-wider font-semibold leading-tight text-[7px]" style={{ color: THEME.gold }}>Total</span>
        </div>
        {typeof window !== 'undefined' && (window as any).saatirilAPI?.backToLogin && (
          <button
            onClick={() => (window as any).saatirilAPI.backToLogin()}
            className="flex items-center gap-1 rounded-md cursor-pointer transition-all hover:brightness-125 shrink-0 px-1.5 py-0.5 text-[8px]"
            style={{
              backgroundColor: `${THEME.border}33`,
              color: THEME.muted,
              border: `1px solid ${THEME.border}`,
            }}
            title="Kembali ke halaman login (ganti server / retry)"
          >
            ← Login
          </button>
        )}
      </div>
      {/* Main resizable area */}
      <ResizablePanelGroup direction="horizontal" className="flex-1 min-h-0">
        {/* LEFT: Camera zone + capture button */}
        <ResizablePanel defaultSize={65} minSize={40} maxSize={85}>
          <div className="flex flex-col h-full">
            {/* Camera zone */}
            <div
              ref={cameraZoneRef}
              className="flex-1 flex items-center justify-center min-h-0 p-2 relative"
            >
              {renderCameraView()}

            </div>

            {/* Capture Button (full width of left panel) */}
            <div
              className="shrink-0 w-full px-4 py-3 border-t"
              style={{
                backgroundColor: `${THEME.panel}ee`,
                borderColor: THEME.border,
                backdropFilter: 'blur(12px)',
              }}
            >
              <div className="mx-auto w-full max-w-xl">
                {renderCaptureButton('xl')}
              </div>
            </div>
          </div>
        </ResizablePanel>

        {/* Resizable handle */}
        <ResizableHandle
          withHandle
          className="w-1.5 hover:w-2 transition-all duration-150"
          style={{
            backgroundColor: THEME.border,
          }}
        />

        {/* RIGHT: Toolbar + Target info + toggleable panels */}
        <ResizablePanel defaultSize={35} minSize={12} maxSize={60}>
          <div className="flex flex-col h-full min-w-0" style={{ backgroundColor: THEME.panel }}>
            {/* ── Toolbar Row (top of right panel, no longer covering camera) ── */}
            <div
              className="shrink-0 flex flex-wrap items-center gap-1 px-1.5 py-1.5 border-b min-w-0"
              style={{ borderColor: THEME.border }}
            >
              {renderToolbarButton(
                <Camera className="size-3.5" />,
                'Shutter',
                showShutterPanel,
                () => setShowShutterPanel((v) => !v),
                shutterMode !== 'manual' ? (
                  <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full" style={{ backgroundColor: `${THEME.gold}33`, color: THEME.gold, border: `1px solid ${THEME.gold}55` }}>
                    {shutterMode === 'ai' ? 'AI' : getTimerDuration(shutterMode) + 's'}
                  </span>
                ) : undefined,
              )}
              {renderToolbarButton(
                <Grid3x3 className="size-3.5" />,
                'Grid',
                showGridlinePanel,
                () => setShowGridlinePanel((v) => !v),
                gridlineEnabled ? (
                  <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full" style={{ backgroundColor: `${THEME.gold}33`, color: THEME.gold, border: `1px solid ${THEME.gold}55` }}>
                    {gridlineType === 'thirds' ? '⅓' : gridlineType === 'quarters' ? '¼' : gridlineType === 'crosshair' ? '+' : '✕'}
                  </span>
                ) : undefined,
              )}
              {renderToolbarButton(
                <List className="size-3.5" />,
                'Antrean',
                showQueuePanel,
                () => setShowQueuePanel((v) => !v),
                remainingCount > 0 ? (
                  <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full" style={{
                    backgroundColor: remainingCount > 10 ? `${THEME.red}33` : `${THEME.gold}33`,
                    color: remainingCount > 10 ? THEME.red : THEME.gold,
                    border: `1px solid ${remainingCount > 10 ? `${THEME.red}55` : `${THEME.gold}55`}`,
                  }}>
                    {remainingCount}
                  </span>
                ) : undefined,
              )}
              <div className="flex-1" />
              {renderToolbarButton(
                isFullscreen ? <Minimize className="size-3.5" /> : <Maximize className="size-3.5" />,
                isFullscreen ? 'Keluar FS' : 'Fullscreen',
                isFullscreen,
                toggleFullscreen,
              )}
            </div>

            {/* Target Info Row — compact, always visible */}
            <div className="shrink-0 flex items-center gap-1.5 px-1.5 py-1.5 border-b min-w-0" style={{ borderColor: THEME.border }}>
              <div
                className="shrink-0 flex items-center justify-center w-6 h-6 rounded-full border-2"
                style={{
                  backgroundColor: THEME.bg,
                  borderColor: hasActiveTarget ? THEME.gold : THEME.border,
                }}
              >
                <User className="size-2.5" style={{ color: hasActiveTarget ? THEME.gold : THEME.border }} />
              </div>

              <div className="flex-1 min-w-0">
                {hasActiveTarget ? (
                  <div className="flex items-center gap-1 min-w-0">
                    <div className="min-w-0 flex-1">
                      <p className="text-xs font-bold leading-tight truncate" style={{ color: '#ffffff' }}>
                        {opCurrentTarget.nama}
                      </p>
                      <p className="text-[9px] font-mono truncate" style={{ color: THEME.muted }}>
                        {opCurrentTarget.nim}
                      </p>
                    </div>
                    <Badge
                      className={`text-[8px] px-1 py-0 shrink-0 ${hasActiveTarget && !sending ? 'animate-pulse' : ''}`}
                      style={{
                        backgroundColor: capturePhase === 'ready-1' ? `${THEME.gold}33` : capturePhase === 'ready-2' ? '#22c55e33' : capturePhase === 'sending' ? `${THEME.border}66` : `${THEME.border}44`,
                        color: capturePhase === 'ready-1' ? THEME.gold : capturePhase === 'ready-2' ? '#4ade80' : THEME.muted,
                        border: `1px solid ${capturePhase === 'ready-1' ? `${THEME.gold}66` : capturePhase === 'ready-2' ? '#22c55e66' : THEME.border}`,
                      }}
                    >
                      {capturePhase === 'sending' && <Loader2 className="size-2 mr-0.5 animate-spin" />}
                      {capturePhase === 'ready-1' && <Camera className="size-2 mr-0.5" />}
                      {capturePhase === 'ready-2' && <CheckCircle2 className="size-2 mr-0.5" />}
                      {capturePhase === 'standby' && <Clock className="size-2 mr-0.5" />}
                      {progressText}
                    </Badge>
                  </div>
                ) : (
                  <p className="text-[10px] italic truncate" style={{ color: THEME.muted }}>
                    Menunggu panggilan MC...
                  </p>
                )}
              </div>
            </div>

            {/* Camera selector dropdown */}
            <div className="shrink-0 px-1.5 py-1 border-b min-w-0" style={{ borderColor: THEME.border }}>
              <Select value={selectedDeviceId} onValueChange={setSelectedDeviceId}>
                <SelectTrigger className="w-full text-[10px] h-6 min-w-0" style={{ backgroundColor: THEME.bg, borderColor: THEME.border, color: THEME.muted }}>
                  <Video className="size-2.5 mr-1 shrink-0" style={{ color: THEME.gold }} />
                  <SelectValue placeholder="Pilih Kamera" />
                </SelectTrigger>
                <SelectContent style={{ backgroundColor: THEME.panel, borderColor: THEME.border }}>
                  {videoDevices.length === 0 ? (
                    <SelectItem value="__none" disabled>Tidak ada kamera</SelectItem>
                  ) : (
                    videoDevices.map((dev) => (
                      <SelectItem key={dev.deviceId} value={dev.deviceId} style={{ color: '#ffffff' }}>{dev.label}</SelectItem>
                    ))
                  )}
                </SelectContent>
              </Select>
            </div>

            {/* Fixed panels at top — NOT inside scrollable area */}
            {showShutterPanel && (
              <div className="shrink-0 px-1.5 py-1.5 border-b min-w-0" style={{ borderColor: THEME.border }}>
                <Card className="border rounded-lg overflow-hidden min-w-0" style={{ backgroundColor: THEME.card, borderColor: THEME.gold }}>
                  <CardContent className="p-1.5 min-w-0">
                    {renderShutterModeSelector(true)}
                  </CardContent>
                </Card>
              </div>
            )}
            {showGridlinePanel && (
              <div className="shrink-0 px-1.5 py-1.5 border-b min-w-0" style={{ borderColor: THEME.border }}>
                <Card className="border rounded-lg overflow-hidden min-w-0" style={{ backgroundColor: THEME.card, borderColor: THEME.gold }}>
                  <CardContent className="p-1.5 min-w-0">
                    {renderGridlineSettings(true)}
                  </CardContent>
                </Card>
              </div>
            )}
            {/* MC Call Panel (Antre dari MC) — FIXED, own internal scroll */}
            {showQueuePanel && (
              <div className="shrink-0 px-1.5 py-1.5 border-b min-w-0" style={{ borderColor: THEME.border }}>
                {renderOpSearch(true)}
              </div>
            )}
            {/* Queue Panel (Antrean) — own SEPARATE scroll area */}
            {showQueuePanel && (
              <ScrollArea className="flex-1 min-h-0">
                <div className="px-1.5 py-1.5 min-w-0">
                  {renderQueueList(true)}
                </div>
              </ScrollArea>
            )}
          </div>
        </ResizablePanel>
      </ResizablePanelGroup>
          {renderMonitorLock()}
      {renderLockToggle()}
</div>
      
  )
      {renderMonitorLock()}
      {renderLockToggle()}
}

export default OperatorPanel
