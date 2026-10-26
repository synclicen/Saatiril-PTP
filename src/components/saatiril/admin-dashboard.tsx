'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Users,
  CheckCircle2,
  Copy,
  Camera,
  Wifi,
  Image as ImageIcon,
  Clock,
  Cable,
  Zap,
  Download,
  FileSpreadsheet,
  XCircle,
  QrCode,
  X,
  Package,
  Monitor,
  CloudUpload,
  Folder,
  Link2Off,
  Send,
} from 'lucide-react'
import { QRCodeSVG } from 'qrcode.react'
import * as XLSX from 'xlsx'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Separator } from '@/components/ui/separator'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/components/ui/dialog'
import { useSaatirilStore, type Student, type StudentStatus, type PhotoHistoryItem, type CameraMode, mergeDatabases, preserveFrameOnSync, preservePhotoHistoryOnSync, mergeCaptureVersions, isPhotoshootMode, isDualPhotoshootMode } from '@/store/use-saatiril-store'
import { onLocal, offLocal, emitLocal, getConnectionHealth, onLatencyUpdate, type ConnectionHealth } from '@/lib/socket'
import { useToast } from '@/hooks/use-toast'
import { useIsMobile } from '@/hooks/use-mobile'
import {
  isBrowserSaveSupported,
  isElectronSaveAvailable,
  requestBrowserSaveDirectory,
  getBrowserSaveDirectory,
  savePhotoInBrowser,
  browserSaveStorageKey,
} from '@/lib/browser-photo-save'

// ── Theme constants ──────────────────────────────────────────────
const BG = 'bg-[#1a0b2e]'
const PANEL = 'bg-[#2a164a]'
const BORDER = 'border-[#533485]'
const GOLD = '#d4af37'
const MUTED = 'text-[#c4b5fd]'
const CYAN = '#06b6d4'

// ── Helper: sanitize nama for filenames ──────────────────────────
// DEFENSIVE: nama/nim can be undefined if a photoHistory entry got corrupted
// during reset+retake cycles. Never let .trim() crash the whole app.
// (matches Android FilenameUtils.kt after fix in d07fdeb — sanitize + cap
//  nama at 40 chars and fall back to studentId → clock-hex when both sanitize
//  empty so empty Excel rows no longer collide on filenames like "___1_Toga.jpg".)
const MAX_NAMA_LENGTH = 40

function sanitizeNama(nama: string | undefined | null): string {
  return (nama ?? '')
    .trim()
    .replace(/\s+/g, '_')
    .replace(/[^a-zA-Z0-9_]/g, '')
    .slice(0, MAX_NAMA_LENGTH)
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
 */
function buildFilename(nim: string | undefined, nama: string | undefined, suffix: number, type: string, version: number = 1, studentId?: string): string {
  const base = `${buildIdentityPart(nim, nama, studentId)}_${suffix}_${type}`
  return version > 1 ? `${base}_v${version}.jpg` : `${base}.jpg`
}

/**
 * Build a versioned filename for photoshoot mode.
 * version: 1 = first capture, 2+ = retake after MC reset.
 */
function buildPhotoshootFilename(nim: string | undefined, nama: string | undefined, channel: number, version: number = 1, studentId?: string): string {
  const base = buildIdentityPart(nim, nama, studentId)
  const withCh = channel > 1 ? `${base}_Ch${channel}` : base
  return version > 1 ? `${withCh}_v${version}.jpg` : `${withCh}.jpg`
}

// ── Helper: human-readable status label for display/export ───────
function statusToLabel(status: StudentStatus): string {
  if (status === 'done') return 'Selesai'
  if (status === 'sent') return 'Dikirim'
  if (status === 'pending') return 'Belum'
  // active_N
  const ch = status.split('_')[1]
  return ch ? `Aktif Ch.${ch}` : 'Aktif'
}

// ── Socket event data shapes ─────────────────────────────────────
interface PhotosSavedData {
  student: Student
  photos: string[]
  channel: number
  /** Version number (1 = first capture, 2+ = retake). Sent by operator. */
  version?: number
  /** Filename chosen by the operator (includes version suffix). */
  filename?: string
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

// BUG #7 (audit-electron-side): LazyThumbnail — lazily rehydrates a photo
// thumbnail from disk via the read-photo IPC. Used by the admin gallery for
// photoHistory entries whose `photos` array is empty (which happens after an
// app restart — saveProjectsToStorage strips base64 to stay under localStorage
// quotas). Falls back to the ImageIcon placeholder if the file is missing or
// the IPC is unavailable (browser mode). Memoized so each (filename, folder)
// pair is fetched at most once per mount.
interface LazyThumbnailProps {
  filename: string
  targetFolder: string
  alt: string
  className?: string
}
function LazyThumbnail({ filename, targetFolder, alt, className }: LazyThumbnailProps) {
  const [src, setSrc] = useState<string | null>(null)
  // useRef (not useState) for the tried-guard so we don't trigger a synchronous
  // setState-in-effect lint error + don't cause a cascading re-render.
  const triedRef = useRef(false)
  useEffect(() => {
    if (triedRef.current) return
    triedRef.current = true
    const api = typeof window !== 'undefined' ? window.saatirilAPI : undefined
    if (!api?.readPhoto || !targetFolder || !filename) return
    let cancelled = false
    api.readPhoto({ filename, targetFolder }).then((dataUrl) => {
      if (!cancelled && dataUrl) setSrc(dataUrl)
    }).catch(() => { /* leave placeholder */ })
    return () => { cancelled = true }
  }, [filename, targetFolder])
  if (src) {
    return <img src={src} alt={alt} loading="lazy" decoding="async" className={className} />
  }
  return <ImageIcon className="size-5 text-[#533485]" />
}

// ── Component ────────────────────────────────────────────────────
export default function AdminDashboard() {
  const { toast } = useToast()
  const isMobile = useIsMobile()

  // ── Disk-save stats (visible in header) ──────────────────────────────
  // Cumulative count of photos successfully written to disk vs failed.
  // Disk saves are triggered per-PHOTOS_SAVED in handlePhotosSaved below.
  const [diskSaveStats, setDiskSaveStats] = useState<{
    saved: number
    failed: number
    lastFile: string | null
    lastError: string | null
  }>({ saved: 0, failed: 0, lastFile: null, lastError: null })
  // Ref mirror so the save callback can read the latest cumulative count
  // synchronously (for the "toast on first success only" decision) without
  // depending on stale closure state.
  const diskSaveStatsRef = useRef(diskSaveStats)
  useEffect(() => {
    diskSaveStatsRef.current = diskSaveStats
  }, [diskSaveStats])

  // Session flag for the first-success toast (separate from diskSaveStats.saved
  // so the toast still fires once per session even after refreshDiskCount sets
  // saved to the actual on-disk count on reopen).
  const sessionSavedRef = useRef(false)

  // Store
  const currentProject = useSaatirilStore((s) => s.currentProject)
  const updateCurrentProject = useSaatirilStore((s) => s.updateCurrentProject)
  const lastSavedAt = useSaatirilStore((s) => s.lastSavedAt)

  // ── Disk photo count (persists across project close/reopen + app restart) ─
  // The in-memory diskSaveStats.saved resets to 0 when AdminDashboard unmounts
  // (project close/reopen, app restart). This calls the count-photos IPC to
  // count the ACTUAL .jpg/.png files in targetFolder on mount + whenever the
  // targetFolder changes. The badge then shows the real on-disk count — never
  // resets to 0 unless the folder is genuinely empty (the user's complaint:
  // 'after exiting + reopening the project, Disk: 0 tersimpan').
  const refreshDiskCount = useCallback(async () => {
    const api = window.saatirilAPI
    const targetFolder = currentProject?.config?.targetFolder
    if (api?.countPhotos && targetFolder) {
      try {
        const count = await api.countPhotos({ targetFolder })
        setDiskSaveStats(prev => ({ ...prev, saved: count }))
      } catch (err) {
        console.error('[SAATIRIL ADMIN] countPhotos failed:', err)
      }
    } else if (!targetFolder) {
      setDiskSaveStats(prev => ({ ...prev, saved: 0 }))
    }
  }, [currentProject?.config?.targetFolder])

  useEffect(() => {
    refreshDiskCount()
  }, [refreshDiskCount])

  // ── Computed values ──────────────────────────────────────────────
  const mode = currentProject?.config.mode ?? 'single'
  const database = currentProject?.database ?? []
  const photoHistory = currentProject?.photoHistory ?? []

  const totalPeserta = database.length
  const doneCount = useMemo(
    () => database.filter((s) => s.status === 'done').length,
    [database],
  )
  // "Belum" = belum selesai = total - selesai (includes pending, sent, active)
  const belumCount = useMemo(
    () => database.filter((s) => s.status !== 'done').length,
    [database],
  )
  const sentCount = useMemo(
    () => database.filter((s) => s.status === 'sent').length,
    [database],
  )
  // ANTREAN = pending (belum dipanggil/dikirim) — mirrors MC panel's 4 pills
  const antreanCount = useMemo(
    () => database.filter((s) => s.status === 'pending').length,
    [database],
  )
  // PROSES = sent + active_N (sedang dengan operator / difoto) — mirrors MC panel
  const prosesCount = useMemo(
    () => database.filter((s) => s.status === 'sent' || s.status.startsWith('active_')).length,
    [database],
  )

  // ── Refs for stable event handlers (avoid re-registering on every project change) ──
  const currentProjectRef = useRef(currentProject)
  useEffect(() => { currentProjectRef.current = currentProject }, [currentProject])

  // ── Socket listeners ─────────────────────────────────────────────
  useEffect(() => {
    const handlePhotosSaved = (data: PhotosSavedData) => {
      // BUG FIX (audit Electron BUG #2): use sync getState() read instead of
      // stale currentProjectRef.current. The ref only updates via useEffect
      // after render — back-to-back PHOTOS_SAVED events (dual-photoshoot:
      // both operators capture simultaneously, or socket.io batches events)
      // would both read the SAME stale proj → second event OVERWRITES first
      // event's photoHistory entry → first student's photo LOST + stuck in
      // PROSES forever. Sync read from the store always returns the latest.
      const proj = useSaatirilStore.getState().currentProject
      if (!proj) return

      // ── Determine version + filenames ──────────────────────────────────
      // The operator sends `version` (1 = first capture, 2+ = retake) and the
      // chosen `filename` in the PHOTOS_SAVED event. We prefer the operator's
      // filename to guarantee the admin saves under the EXACT same name (so
      // there is no mismatch between operator's disk and admin's disk).
      const photoshootMode = isPhotoshootMode(proj.config.mode)
      const version = data.version ?? 1

      // ── Save photos to disk ─────────────────────────────────────────────
      // BULLETPROOF: tries Electron IPC first, falls back to the browser File
      // System Access API, and finally counts a failure. Visible via the
      // diskSaveStats state + a header Disk badge + toasts (failure always,
      // first-success only — no spam for 4000 photos). Disk saves are UNCAPPED
      // and independent of the in-memory gallery cap (MAX_PHOTO_HISTORY_IN_MEMORY).
      const targetFolder = proj.config?.targetFolder
      const hasEnoughPhotos = data.photos?.length >= (photoshootMode ? 1 : 2)

      if (hasEnoughPhotos) {
        // Build the list of { base64Data, filename } items to save.
        // Photoshoot mode → 1 photo (buildPhotoshootFilename).
        // Wisuda mode (single/dual) → 2 photos (buildFilename Toga + Ijazah).
        const items: { base64Data: string; filename: string }[] = []
        if (photoshootMode) {
          const filename = data.filename ?? buildPhotoshootFilename(data.student.nim, data.student.nama, data.channel, version, data.student.id)
          items.push({ base64Data: data.photos[0], filename })
        } else {
          const togaFilename = buildFilename(data.student.nim, data.student.nama, 1, 'Toga', version, data.student.id)
          const ijazahFilename = buildFilename(data.student.nim, data.student.nama, 2, 'Ijazah', version, data.student.id)
          items.push({ base64Data: data.photos[0], filename: togaFilename })
          items.push({ base64Data: data.photos[1], filename: ijazahFilename })
        }

        const electronOk = isElectronSaveAvailable()
        const browserOk = isBrowserSaveSupported()
        const hasFolder = !!targetFolder

        // No save path at all — tell the user immediately (visible, not silent).
        if (!hasFolder && !browserOk && !electronOk) {
          console.error('[SAATIRIL ADMIN] No disk-save path available (no Electron IPC, no browser FS Access, no targetFolder). Photos stay in memory/gallery only.')
          toast({
            title: 'Tidak Ada Folder Simpan',
            description: 'Jalankan via Electron (portable.exe) atau pilih folder simpan di browser.',
            variant: 'destructive',
          })
        } else {
          const storageKey = browserSaveStorageKey(proj.id)
          // Save each photo with PRIORITY:
          //   (a) Electron IPC (primary) — if window.saatirilAPI.savePhoto exists AND targetFolder is set
          //   (b) Browser File System Access API (fallback) — pick directory once, persist in IndexedDB
          //   (c) failure — count toward diskSaveStats.failed
          const savePromises = items.map(async (item) => {
            // (a) Electron IPC (primary)
            if (electronOk && hasFolder) {
              try {
                const p = await window.saatirilAPI!.savePhoto({
                  base64Data: item.base64Data,
                  filename: item.filename,
                  targetFolder,
                  // Per-project Google Drive backup folder (null = no backup
                  // for this project). Passed to the Electron savePhoto IPC
                  // which copies the photo to this folder after the local save.
                  // NOT auto-connected — the admin explicitly sets it per-project.
                  backupFolder: proj.config?.driveFolder ?? null,
                  // Pass projectName so the Electron savePhoto IPC creates a
                  // project subfolder in the backup folder (mirrors local
                  // structure: backupFolder/projectName/filename).
                  projectName: proj.name,
                })
                if (p) {
                  console.log(`[SAATIRIL ADMIN] Photo saved to disk (Electron IPC, v${version}): ${item.filename} → ${p}`)
                  return { ok: true, filename: item.filename, error: null as string | null }
                }
                console.warn(`[SAATIRIL ADMIN] Electron savePhoto returned null for ${item.filename} — trying browser fallback`)
              } catch (err: any) {
                console.warn(`[SAATIRIL ADMIN] Electron savePhoto threw for ${item.filename}: ${err?.message} — trying browser fallback`)
              }
            }
            // (b) Browser File System Access API (fallback)
            if (browserOk) {
              try {
                let dir = await getBrowserSaveDirectory(storageKey)
                if (!dir) {
                  dir = await requestBrowserSaveDirectory(storageKey)
                }
                if (dir) {
                  const saved = await savePhotoInBrowser(storageKey, item.base64Data, item.filename)
                  if (saved) {
                    console.log(`[SAATIRIL ADMIN] Photo saved to disk (browser FS Access, v${version}): ${item.filename}`)
                    return { ok: true, filename: item.filename, error: null as string | null }
                  }
                  console.warn(`[SAATIRIL ADMIN] Browser savePhotoInBrowser returned null for ${item.filename}`)
                } else {
                  console.warn(`[SAATIRIL ADMIN] No browser save directory picked — skipping browser save for ${item.filename}`)
                }
              } catch (err: any) {
                console.error(`[SAATIRIL ADMIN] Browser save failed for ${item.filename}:`, err)
              }
            }
            // (c) Both unavailable / failed
            return { ok: false, filename: item.filename, error: 'No save path available (no Electron, no browser folder)' }
          })

          Promise.allSettled(savePromises).then((results) => {
            let savedNow = 0
            let failedNow = 0
            let lastFile: string | null = null
            let lastError: string | null = null
            const savedNames: string[] = []
            for (const r of results) {
              if (r.status === 'fulfilled') {
                if (r.value.ok) {
                  savedNow++
                  savedNames.push(r.value.filename)
                  lastFile = r.value.filename
                } else {
                  failedNow++
                  lastError = r.value.error
                }
              } else {
                // Promise rejected outright (unexpected) — count as failed.
                failedNow++
                const msg = (r.reason as Error)?.message ?? String(r.reason)
                lastError = msg
                console.error('[SAATIRIL ADMIN] Save promise rejected:', r.reason)
              }
            }

            const prev = diskSaveStatsRef.current
            // Toast on FAILURE always; on SUCCESS only for the very first
            // photo of the session (prev.saved === 0) — so 4000 photos do
            // not spam toasts.
            if (failedNow > 0) {
              toast({
                title: 'Gagal Simpan ke Disk',
                description: `${failedNow} foto tidak tersimpan. ${lastError ?? 'Cek ruang disk & folder target.'}`,
                variant: 'destructive',
              })
            } else if (savedNow > 0 && !sessionSavedRef.current) {
              const folderDesc = hasFolder ? targetFolder : 'folder browser (File System Access)'
              toast({
                title: 'Foto Tersimpan ke Disk',
                description: `${savedNames.join(', ')} → ${folderDesc}`,
              })
              sessionSavedRef.current = true
            }
            setDiskSaveStats({
              saved: prev.saved + savedNow,
              failed: prev.failed + failedNow,
              lastFile,
              lastError,
            })
            // Refresh the badge with the ACTUAL on-disk count (count-photos IPC).
            // This overrides the session-cumulative saved with the real file count,
            // so the badge persists correctly across project close/reopen + shows
            // the true number of photos in targetFolder.
            const api2 = window.saatirilAPI
            if (api2?.countPhotos && targetFolder) {
              api2.countPhotos({ targetFolder }).then((count: number) => {
                setDiskSaveStats(prev2 => ({ ...prev2, saved: count }))
              }).catch((err: Error) => console.error('[SAATIRIL ADMIN] countPhotos refresh failed:', err))
            }
          })
        }
      }

      // Build the history item from the data
      const historyItem: PhotoHistoryItem = {
        student: data.student,
        photos: data.photos,
        channel: data.channel,
      }

      // Check if this student already has a history entry
      const existing = proj.photoHistory.findIndex(
        (h) =>
          h.student.id === data.student.id &&
          h.channel === data.channel,
      )
      let newHistory: PhotoHistoryItem[]
      if (existing !== -1) {
        newHistory = [...proj.photoHistory]
        newHistory[existing] = historyItem
      } else {
        newHistory = [...proj.photoHistory, historyItem]
      }

      // Check completion: in dual-photoshoot mode, BOTH cameras must photograph
      // (the participant is done only after channel 1 AND channel 2 each have
      // a photoHistory entry with photos). In single-photoshoot mode, the
      // single channel is sufficient. In non-photoshoot modes, mark done
      // immediately.
      // (matches Android AdminViewModel.handlePhotosSaved after fix in d07fdeb —
      // the old `||` marked done after either channel, causing the second
      // channel operator to skip the student and miss the second photo.)
      let allChannelsDone = true
      if (isDualPhotoshootMode(proj.config.mode)) {
        const ch1Done = newHistory.some((h) => h.student.id === data.student.id && h.channel === 1 && h.photos.length > 0)
        const ch2Done = newHistory.some((h) => h.student.id === data.student.id && h.channel === 2 && h.photos.length > 0)
        allChannelsDone = ch1Done && ch2Done
      } else if (photoshootMode) {
        // Single photoshoot: one channel is enough
        allChannelsDone = true
      } else {
        // Non-photoshoot modes: mark done immediately
        allChannelsDone = true
      }

      // Update student status: 'done' if all channels complete, keep current status otherwise
      const updatedDatabase = proj.database.map((s) =>
        s.id === data.student.id && allChannelsDone
          ? { ...s, status: 'done' as const }
          : s.id === data.student.id && !allChannelsDone
            ? { ...s, status: s.status } // keep current status (e.g., 'sent')
            : s
      )

      updateCurrentProject({
        ...proj,
        database: updatedDatabase,
        photoHistory: newHistory,
      })

      // ── Sync ALL clients with the 'done' status + photoHistory entry ──────
      // The operator (sender of PHOTOS_SAVED) does NOT receive its own
      // lan-message broadcast (socket.broadcast.emit excludes sender), so it
      // relies on its OWN handlePhotosSaved for the local 'done' update. To
      // guarantee EVERY surface (MC App, Operator App, Portable's MC/Operator
      // panels) stays perfectly in sync — same data, same status — emit a
      // lightweight SYNC_DB (photos stripped) right after the admin updates.
      // This is the single source of truth: the admin's state, propagated.
      const strippedForSync = {
        ...proj,
        database: updatedDatabase,
        config: { ...proj.config, sessionPassword: proj.config.sessionPassword != null ? '__PASSWORD_SET__' : null },
        photoHistory: newHistory.map((h) => ({ ...h, photos: [] })),
      }
      emitLocal('SYNC_DB', { project: strippedForSync })
    }

    const handleSyncDb = (data: SyncDbData) => {
      // Read latest state synchronously to avoid stale-ref race
      const proj = useSaatirilStore.getState().currentProject
      if (!proj) {
        // No existing project — accept incoming directly (Electron client first connect)
        if (data.project) {
          updateCurrentProject(data.project)
          console.log('[SAATIRIL ADMIN] SYNC_DB: accepted new project (no existing):', data.project.name)
        }
        return
      }
      // Preserve frame data: if incoming has '__FRAME_SAVED__', keep existing frame
      const mergedConfig = preserveFrameOnSync(data.project.config, proj.config)
      // Merge database with incoming (prevents channel data overwrite in dual mode)
      const mergedDb = mergeDatabases(proj.database, data.project.database)
      // Preserve photos: incoming SYNC_DB may have stripped photos
      const mergedPhotoHistory = preservePhotoHistoryOnSync(
        data.project.photoHistory ?? [],
        proj.photoHistory,
      )
      // Merge captureVersions (MAX per key) so retake version numbers sync
      // across admin/operator/MC without regressing.
      const mergedVersions = mergeCaptureVersions(
        proj.captureVersions,
        (data.project as any).captureVersions,
      )
      updateCurrentProject({
        ...proj,
        database: mergedDb,
        photoHistory: mergedPhotoHistory,
        config: mergedConfig,
        captureVersions: mergedVersions,
      })
    }

    // STUDENT_RESET: explicit reset/retake signal from MC.
    // Bypasses the SYNC_DB merge (which blocks status regression pending<sent)
    // so admin clears the student's photoHistory entries + resets status here.
    const handleStudentReset = (data: { studentId: string; channel: number }) => {
      console.log('[SAATIRIL ADMIN] STUDENT_RESET received — clearing for retake:', data.studentId, 'Ch.', data.channel)
      // Read latest state synchronously to avoid stale-ref race with SYNC_DB
      const proj = useSaatirilStore.getState().currentProject
      if (!proj) return
      const cleanedHistory = proj.photoHistory.filter((h) => h.student.id !== data.studentId)
      const cleanedDb = proj.database.map((s) =>
        s.id === data.studentId ? { ...s, status: 'pending' as StudentStatus } : s,
      )
      updateCurrentProject({ ...proj, database: cleanedDb, photoHistory: cleanedHistory })
    }

    onLocal('PHOTOS_SAVED', handlePhotosSaved)
    onLocal('SYNC_DB', handleSyncDb)
    
    // ── MC_CALL: admin processes ALL MC_CALL from ANY channel (no channel check) ──
    const handleMcCallAlways = (data: { student: any; channel: number }) => {
      const curProj = useSaatirilStore.getState().currentProject
      if (!curProj) return
      const isPs = isPhotoshootMode(curProj.config.mode)
      const newStatus = (isPs ? 'sent' : `active_${data.channel}`) as StudentStatus

      // BUG #19 (audit-electron-side): pre-compute the photographed-id Set ONCE
      // per MC_CALL instead of calling photoHistory.some() inside the .map()
      // callback for every stale-active student. The previous code was
      // O(database.length) + O(num_stale_active × photoHistory.length) per
      // MC_CALL; with hundreds of MC_CALLs during a ceremony that caused
      // GC pressure + 50-200ms main-thread blocks on slow admin laptops.
      // Now: O(database.length) + O(photoHistory.length) total — the
      // per-student check is O(1). Also short-circuit: there's at most 1
      // stale active_N per channel at any time (we reset on every MC_CALL),
      // so once we've reset one we can stop scanning for stale actives.
      const targetActiveStatus = `active_${data.channel}`
      const photographedIds = new Set<string>()
      if (!isPs) {
        for (const h of curProj.photoHistory) {
          if (h.channel === data.channel) photographedIds.add(h.student.id)
        }
      }
      let didResetStale = false
      const updatedDb = curProj.database.map((s: any) => {
        if (s.id === data.student.id) return { ...s, status: newStatus }
        // Wisuda sequential (non-photoshoot): reset any OTHER stale 'active_N'
        // on the called channel — only 1 should be active at a time. When the
        // MC calls the next, a previously-called student still 'active_N' (not
        // photographed) goes back to 'pending' (queue), OR to 'done' if it was
        // photographed (in photoHistory). Without this, stale 'active_N' accu-
        // mulate in PROSES (mirrors the MC's handleCallNow fix). Photoshoot
        // mode uses 'sent' (not 'active_N') so it's unaffected.
        if (!isPs && !didResetStale && s.status === targetActiveStatus) {
          didResetStale = true  // short-circuit: at most 1 stale active per channel
          const photographed = photographedIds.has(s.id)
          return { ...s, status: (photographed ? 'done' : 'pending') as StudentStatus }
        }
        return s
      })
      updateCurrentProject({ ...curProj, database: updatedDb })
      if (!isPs) {
        useSaatirilStore.getState().setOpCurrentTarget({ ...data.student, status: newStatus }, data.channel)
      }
      const stripped = {
        ...curProj,
        database: updatedDb,
        config: { ...curProj.config, sessionPassword: curProj.config.sessionPassword != null ? '__PASSWORD_SET__' : null },
        photoHistory: curProj.photoHistory.map((h: any) => ({ ...h, photos: [] }))
      }
      emitLocal('SYNC_DB', { project: stripped })
      console.log('[SAATIRIL MAIN] MC_CALL processed:', data.student.nama, '(photoshoot:', isPs, ') DB size:', curProj.database.length, 'sent:', updatedDb.filter((s:any)=>s.status==='sent').length)
    }
    onLocal('MC_CALL', handleMcCallAlways)
    
    // ── REQUEST_STATE: MC/Operator reconnects → send full SYNC_DB ──
    const handleRequestState = (data: { role?: string; channel?: number }) => {
      const curProj = useSaatirilStore.getState().currentProject
      if (!curProj) return
      const stripped = {
        ...curProj,
        config: { ...curProj.config, sessionPassword: curProj.config.sessionPassword != null ? '__PASSWORD_SET__' : null },
        photoHistory: curProj.photoHistory.map((h: any) => ({ ...h, photos: [] }))
      }
      emitLocal('SYNC_DB', { project: stripped })
      console.log('[SAATIRIL MAIN] REQUEST_STATE from role=' + (data?.role || '?') + ' — sent SYNC_DB with ' + (curProj.database?.length || 0) + ' students')
    }
    onLocal('REQUEST_STATE', handleRequestState)
    onLocal('STUDENT_RESET', handleStudentReset)

    // ── BLE_TRIGGER: handle MC trigger from /admin-ble page ──
    // MC HP (BLE Server) sends PANGGIL/NEXT/RESET via Bluetooth →
    // /admin-ble page → /api/ble-trigger → socket emit BLE_TRIGGER →
    // here: look up next student, emit proper MC_CALL + update store
    const handleBLETrigger = (data: { action: string; studentId?: string }) => {
      const proj = useSaatirilStore.getState().currentProject
      if (!proj) return
      const channel = 1 // Admin is always channel 1 in combined mode

      if (data.action === 'PANGGIL') {
        const nextPending = proj.database.find(s => s.status === 'pending')
        if (nextPending) {
          const newStatus = `active_${channel}` as StudentStatus
          const updatedDb = proj.database.map(s =>
            s.id === nextPending.id ? { ...s, status: newStatus } :
            (s.status.startsWith('active_') && s.status === `active_${channel}`) ? { ...s, status: 'pending' as StudentStatus } : s
          )
          updateCurrentProject({ ...proj, database: updatedDb })
          // Emit MC_CALL so operator panel picks it up
          emitLocal('MC_CALL', { student: { ...nextPending, status: newStatus }, channel })
          console.log('[SAATIRIL BLE] PANGGIL:', nextPending.nama)
        }
      } else if (data.action === 'NEXT') {
        const active = proj.database.find(s => s.status.startsWith('active_'))
        if (active) {
          const resetDb = proj.database.map(s =>
            s.id === active.id ? { ...s, status: 'pending' as StudentStatus } : s
          )
          // BUG #11 (audit-electron-side): exclude the just-reset student from
          // the nextPending search. Previously `resetDb.find(s => s.status ===
          // 'pending')` could find the SAME student we just reset (when only
          // 1 non-done student remained) → 'NEXT' re-called the same student
          // instead of advancing. Now we exclude active.id; if no OTHER
          // pending student exists (end-of-ceremony edge case), we simply
          // don't call anyone — the MC can manually call the next student.
          const nextPending = resetDb.find(s => s.status === 'pending' && s.id !== active.id)
          if (nextPending) {
            const newStatus = `active_${channel}` as StudentStatus
            const updatedDb = resetDb.map(s =>
              s.id === nextPending.id ? { ...s, status: newStatus } : s
            )
            updateCurrentProject({ ...proj, database: updatedDb })
            emitLocal('MC_CALL', { student: { ...nextPending, status: newStatus }, channel })
          } else {
            // No other pending student — just commit the reset (the active
            // student goes back to 'pending' without calling anyone new).
            updateCurrentProject({ ...proj, database: resetDb })
            console.log('[SAATIRIL BLE] NEXT: no other pending student — reset only')
          }
        }
      } else if (data.action === 'RESET') {
        const active = proj.database.find(s => s.status.startsWith('active_'))
        if (active) {
          const updatedDb = proj.database.map(s =>
            s.id === active.id ? { ...s, status: 'pending' as StudentStatus } : s
          )
          updateCurrentProject({ ...proj, database: updatedDb })
          emitLocal('STUDENT_RESET', { studentId: active.id, channel })
        }
      }
    }
    onLocal('BLE_TRIGGER', handleBLETrigger)

    // ── Periodic SYNC_DB broadcast (every 3s) ──────────────────────────────
    // Ensures ALL connected clients (even pending_auth — wrong/no session
    // password) receive the current project FAST. The server's lan-message
    // relay sends to ALL sockets regardless of the RECEIVER's auth, so even
    // pending_auth clients receive + process the SYNC_DB. This fixes the
    // "Belum ada proyek aktif" flash the user sees: the operator/MC start with
    // currentProject=null (showing "Belum ada proyek aktif") until the project
    // arrives via this broadcast (or via REQUEST_STATE if authenticated). With
    // a 3s interval, the max delay is 3s — the "Belum" screen barely flashes.
    const syncInterval = setInterval(() => {
      const curProj = useSaatirilStore.getState().currentProject
      if (!curProj) return
      const stripped = {
        ...curProj,
        config: { ...curProj.config, sessionPassword: curProj.config.sessionPassword != null ? '__PASSWORD_SET__' : null },
        photoHistory: curProj.photoHistory.map((h: any) => ({ ...h, photos: [] })),
      }
      emitLocal('SYNC_DB', { project: stripped })
    }, 3000)

    return () => {
      clearInterval(syncInterval)
      offLocal('PHOTOS_SAVED', handlePhotosSaved)
      offLocal('SYNC_DB', handleSyncDb)
      offLocal('MC_CALL', handleMcCallAlways)
      offLocal('REQUEST_STATE', handleRequestState)
      offLocal('STUDENT_RESET', handleStudentReset)
      offLocal('BLE_TRIGGER', handleBLETrigger)
    }
  }, [updateCurrentProject])

  // ── Network quality state ──────────────────────────────────────
  const [networkHealth, setNetworkHealth] = useState<ConnectionHealth>(() => getConnectionHealth())

  useEffect(() => {
    const unsub = onLatencyUpdate((h) => setNetworkHealth({ ...h }))
    return unsub
  }, [])

  // ── LAN info state ────────────────────────────────────────────────
  const [lanInfo, setLanInfo] = useState<{ httpPort: number; socketPort: number; ips: { name: string; address: string }[] } | null>(null)

  // ── QR Code dialog state ───────────────────────────────────────────
  const [qrDialogOpen, setQrDialogOpen] = useState(false)
  const [qrLink, setQrLink] = useState('')
  const [qrLabel, setQrLabel] = useState('')

  // ── Release download state (from GitHub Releases) ──────────────────
  const GITHUB_REPO = 'synclicen/Saatiril-Andro'
  const [apkInfo, setApkInfo] = useState<{ available: boolean; sizeMB?: string; assetName?: string; lastModified?: string; downloadUrl?: string; error?: string } | null>(null)
  const [mcApkInfo, setMcApkInfo] = useState<{ available: boolean; sizeMB?: string; assetName?: string; lastModified?: string; downloadUrl?: string; error?: string } | null>(null)
  const [portableInfo, setPortableInfo] = useState<{ available: boolean; sizeMB?: string; assetName?: string; lastModified?: string; downloadUrl?: string; error?: string } | null>(null)

  const bleProjectInfoCharRef = useRef<any>(null)
  const bleQueueDataCharRef = useRef<any>(null)
  const bleNextStudentCharRef = useRef<any>(null)

  // Push project data to MC via BLE whenever the project changes (if connected).
  // This keeps the MC's display in sync: when admin calls a student or marks
  // one as done, the MC sees the updated queue + next student.


  // ── Google Drive / Cloud backup state ──────────────────────────────
  const [backupFolder, setBackupFolder] = useState<string | null>(null)
  const [backupStats, setBackupStats] = useState<{ connected: boolean; totalFiles: number } | null>(null)

  // Load backup folder on mount + poll stats every 5s
  useEffect(() => {
    const api = window.saatirilAPI
    if (!api?.isElectron || !api.getBackupFolder) return

    // Load initial
    api.getBackupFolder().then(setBackupFolder)

    // Poll stats
    const interval = setInterval(async () => {
      try {
        const stats = await api.getBackupStats()
        setBackupStats(stats)
      } catch {}
    }, 5000)
    return () => clearInterval(interval)
  }, [])

  // Check release status from GitHub Releases on mount
  // ── In Electron portable: use IPC (main process fetches GitHub API via Node.js,
  //    no CORS/network issues). In web/dev: use direct client-side fetch.
  useEffect(() => {
    const fetchReleaseInfo = async () => {
      try {
        // ── Electron: use IPC → main process fetch ──
        const api = window.saatirilAPI
        if (api?.isElectron && api.getReleaseInfo) {
          const data = await api.getReleaseInfo()
          setApkInfo(data.apk)
          setPortableInfo(data.portable)
          return
        }

        // ── Web / dev preview: direct client-side fetch ──
        const res = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/tags/latest`, {
          headers: { Accept: 'application/vnd.github+json' },
        })
        if (!res.ok) throw new Error(`GitHub API returned ${res.status}`)
        const release = await res.json()
        const assets = release.assets || []

        // Find specific APKs by name
        const apkAsset = assets.find((a: { name: string }) => a.name === 'saatiril-andro.apk')
        const mcApkAsset = assets.find((a: { name: string }) => a.name === 'saatiril-mc.apk')
        const portableAsset = assets.find((a: { name: string }) => a.name === 'saatiril-portable.exe')

        setApkInfo(apkAsset ? {
          available: true,
          sizeMB: (apkAsset.size / (1024 * 1024)).toFixed(1),
          assetName: apkAsset.name,
          lastModified: apkAsset.updated_at || release.published_at,
          downloadUrl: apkAsset.browser_download_url,
        } : { available: false, error: 'No APK asset found' })

        setMcApkInfo(mcApkAsset ? {
          available: true,
          sizeMB: (mcApkAsset.size / (1024 * 1024)).toFixed(1),
          assetName: mcApkAsset.name,
          lastModified: mcApkAsset.updated_at || release.published_at,
          downloadUrl: mcApkAsset.browser_download_url,
        } : { available: false, error: 'No MC APK asset found' })

        setPortableInfo(portableAsset ? {
          available: true,
          sizeMB: (portableAsset.size / (1024 * 1024)).toFixed(1),
          assetName: portableAsset.name,
          lastModified: portableAsset.updated_at || release.published_at,
          downloadUrl: portableAsset.browser_download_url,
        } : { available: false, error: 'No Portable asset found' })
      } catch (err: any) {
        setApkInfo({ available: false, error: err?.message || 'GitHub API error' })
        setMcApkInfo({ available: false, error: err?.message || 'GitHub API error' })
        setPortableInfo({ available: false, error: err?.message || 'GitHub API error' })
      }
    }
    fetchReleaseInfo()
  }, [])

  useEffect(() => {
    const api = window.saatirilAPI
    if (api?.isElectron && api.getLanInfo) {
      api.getLanInfo().then((info: any) => {
        setLanInfo(info)
        console.log('[SAATIRIL] LAN info:', info)
      }).catch(() => {})
    }
  }, [])

  // ── Generate link URL (shared logic for copy & QR) ────────────────────
  // Returns the URL string for a given role+channel combo.
  // Same logic as copyLink but returns the URL instead of copying.
  const generateLink = useCallback(
    async (role: string, channel: number): Promise<string> => {
      const api = window.saatirilAPI
      const isElectron = api?.isElectron
      // UNIFIED: load the root route with ?role= param so the browser page
      // uses the SAME React panel (mc-panel.tsx / operator-panel.tsx) as the
      // Electron apps — identical display + data sync across MC page, MC App,
      // Operator page, Operator App, and the Portable's MC/Operator panels.
      // (Previously /mc and /operator served the standalone mc.html /
      // operator.html which had drifted from the React panels.)
      const path = '/?role=' + role

      // BUG #9 fix (audit Remote/Mirror batch 2): session password is NO
      // LONGER embedded in the QR code / copied URL. Passing ?password= in a
      // URL is insecure (browser history, server logs, referrer headers, and
      // the QR code itself becomes a credential). The browser MC/Operator
      // page now ALWAYS prompts for the password via showPasswordPrompt when
      // the server demands one (auth-requirement / auth-failed). The Electron
      // apps already collect the password via connection.html. So no pwdParam
      // is appended here — the QR code encodes ONLY ip/port/channel/role.

      if (isElectron) {
        try {
          const info = lanInfo || (await api.getLanInfo())
          const ips = info.ips
          const lanIP = ips.length > 0 ? ips[0].address : 'localhost'
          return `http://${lanIP}:${info.httpPort}${path}&channel=${channel}&socketPort=${info.socketPort}&v=23`
        } catch {
          const hostname = window.location.hostname
          const socketPort = new URLSearchParams(window.location.search).get('socketPort') || '3003'
          return `http://${hostname}:3000${path}&channel=${channel}&socketPort=${socketPort}&v=23`
        }
      } else {
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
              const port = window.location.port || '3000'
              origin = `http://${lanIP}:${port}`
            }
          } catch {
            // WebRTC not available — keep localhost URL
          }
        }

        return `${origin}${path}&channel=${channel}&socketPort=${socketPort}&v=23`
      }
    },
    [lanInfo],
  )

  // ── Generate download link helper ────────────────────────────────
  // Uses direct GitHub Release URL — works in both dev and portable Electron
  // (no server-side API route needed)
  const generateDownloadLink = useCallback(
    (type: 'apk' | 'portable'): string => {
      const info = type === 'portable' ? portableInfo : apkInfo
      if (info?.downloadUrl) return info.downloadUrl
      // Fallback: construct the GitHub Release URL directly using the tag name
      // NOTE: Use /releases/download/{tag}/ not /releases/latest/download/
      // because the latter only works for non-prerelease releases.
      const filename = type === 'portable' ? 'saatiril-portable.exe' : 'saatiril-operator.apk'
      return `https://github.com/${GITHUB_REPO}/releases/download/latest/${filename}`
    },
    [apkInfo, portableInfo],
  )

  // Convenience wrappers
  const generateApkLink = useCallback(() => generateDownloadLink('apk'), [generateDownloadLink])
  const generatePortableLink = useCallback(() => generateDownloadLink('portable'), [generateDownloadLink])

  // ── Show QR code dialog ─────────────────────────────────────────────
  const showQrCode = useCallback(
    async (role: string, channel: number) => {
      const url = await generateLink(role, channel)
      const label = role === 'mc' ? `MC ${channel > 1 ? channel : ''}`.trim() : `Operator ${channel}`
      setQrLink(url)
      setQrLabel(label)
      setQrDialogOpen(true)
    },
    [generateLink],
  )

  // ── Show APK download QR code ────────────────────────────────────
  const showApkQrCode = useCallback(
    () => {
      const url = generateApkLink()
      setQrLink(url)
      setQrLabel('APK Saatiril Android')
      setQrDialogOpen(true)
    },
    [generateApkLink],
  )

  // ── Show Portable download QR code ───────────────────────────────
  const showPortableQrCode = useCallback(
    () => {
      const url = generatePortableLink()
      setQrLink(url)
      setQrLabel('Saatiril Portable Windows')
      setQrDialogOpen(true)
    },
    [generatePortableLink],
  )

  // ── Copy APK download link ────────────────────────────────────────
  const copyApkLink = useCallback(
    async () => {
      const url = generateApkLink()
      try {
        if (navigator.clipboard) {
          await navigator.clipboard.writeText(url)
        } else {
          const textarea = document.createElement('textarea')
          textarea.value = url
          document.body.appendChild(textarea)
          textarea.select()
          document.execCommand('copy')
          document.body.removeChild(textarea)
        }
        toast({
          title: 'Link APK disalin!',
          description: `APK Saatiril Android — ${url}`,
        })
      } catch {
        toast({
          title: 'Gagal menyalin',
          description: 'Tidak dapat menyalin link. Silakan salin manual.',
          variant: 'destructive',
        })
      }
    },
    [toast, generateApkLink],
  )

  // ── Copy Portable download link ──────────────────────────────────
  const copyPortableLink = useCallback(
    async () => {
      const url = generatePortableLink()
      try {
        if (navigator.clipboard) {
          await navigator.clipboard.writeText(url)
        } else {
          const textarea = document.createElement('textarea')
          textarea.value = url
          document.body.appendChild(textarea)
          textarea.select()
          document.execCommand('copy')
          document.body.removeChild(textarea)
        }
        toast({
          title: 'Link Portable disalin!',
          description: `Saatiril Portable Windows — ${url}`,
        })
      } catch {
        toast({
          title: 'Gagal menyalin',
          description: 'Tidak dapat menyalin link. Silakan salin manual.',
          variant: 'destructive',
        })
      }
    },
    [toast, generatePortableLink],
  )

  // ── Copy link handler ────────────────────────────────────────────
  // All links use HTTP. Operator needs Chrome Flag for camera access.
  //
  // CRITICAL: When the admin accesses via localhost, generated links also
  // use localhost — which doesn't work on other devices (phones, other laptops).
  // We detect the LAN IP via WebRTC and substitute it in the URL when needed.
  const copyLink = useCallback(
    async (role: string, channel: number) => {
      const api = window.saatirilAPI
      const isElectron = api?.isElectron
      // UNIFIED: load the root route with ?role= param so the browser page
      // uses the SAME React panel (mc-panel.tsx / operator-panel.tsx) as the
      // Electron apps — identical display + data sync across MC page, MC App,
      // Operator page, Operator App, and the Portable's MC/Operator panels.
      // (Previously /mc and /operator served the standalone mc.html /
      // operator.html which had drifted from the React panels.)
      const path = '/?role=' + role

      // BUG #9 fix (audit Remote/Mirror batch 2): no password in URL (see
      // generateLink above for full rationale). QR codes / copied links
      // encode ONLY ip/port/channel/role.

      let url: string
      if (isElectron) {
        try {
          const info = lanInfo || (await api.getLanInfo())
          const ips = info.ips
          const lanIP = ips.length > 0 ? ips[0].address : 'localhost'
          url = `http://${lanIP}:${info.httpPort}${path}&channel=${channel}&socketPort=${info.socketPort}&v=23`
        } catch {
          const hostname = window.location.hostname
          const params = new URLSearchParams(window.location.search)
          const socketPort = params.get('socketPort') || '3003'
          url = `http://${hostname}:3000${path}&channel=${channel}&socketPort=${socketPort}&v=23`
        }
      } else {
        // Web/sandbox mode: include socketPort so LAN clients can connect to the Socket.io server
        const socketPort = new URLSearchParams(window.location.search).get('socketPort') || '3003'
        let origin = window.location.origin

        // CRITICAL FIX: If accessing via localhost/127.0.0.1, the generated link
        // won't work on other devices (phones, other laptops). Try to detect the
        // LAN IP and substitute it so cross-device access works.
        const hostname = window.location.hostname
        if (hostname === 'localhost' || hostname === '127.0.0.1') {
          try {
            // Detect LAN IP via WebRTC (same technique as main-app.tsx)
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
              const port = window.location.port || '3000'
              origin = `http://${lanIP}:${port}`
              console.log(`[SAATIRIL] LAN IP detected for link: ${lanIP}`)
            }
          } catch {
            // WebRTC not available — keep localhost URL
          }
        }

        url = `${origin}${path}&channel=${channel}&socketPort=${socketPort}&v=23`
      }
      try {
        if (navigator.clipboard) {
          await navigator.clipboard.writeText(url)
        } else {
          const textarea = document.createElement('textarea')
          textarea.value = url
          document.body.appendChild(textarea)
          textarea.select()
          document.execCommand('copy')
          document.body.removeChild(textarea)
        }
        toast({
          title: 'Link disalin!',
          description: `${role.toUpperCase()} ${channel > 0 ? channel : ''} — ${url}`,
        })
      } catch {
        toast({
          title: 'Gagal menyalin',
          description: 'Tidak dapat menyalin link. Silakan salin manual.',
          variant: 'destructive',
        })
      }
    },
    [toast, lanInfo, currentProject],
  )

  // ── Export participants to Excel ─────────────────────────────────
  // Generates an .xlsx file with No, NIM, Nama, Status, Channel — used by
  // admin as an offline control sheet to verify total/belum/sudah at the
  // end of an event.
  const exportToExcel = useCallback(() => {
    if (!currentProject) {
      toast({
        title: 'Tidak ada proyek aktif',
        description: 'Buka proyek terlebih dahulu sebelum mengekspor.',
        variant: 'destructive',
      })
      return
    }
    if (database.length === 0) {
      toast({
        title: 'Daftar peserta kosong',
        description: 'Belum ada peserta untuk diekspor.',
        variant: 'destructive',
      })
      return
    }

    try {
      const rows = database.map((s, idx) => ({
        No: idx + 1,
        NIM: s.nim,
        Nama: s.nama,
        Status: statusToLabel(s.status),
        Channel: s.assignedChannel,
      }))
      const ws = XLSX.utils.json_to_sheet(rows)
      // Column widths
      ws['!cols'] = [
        { wch: 5 },
        { wch: 20 },
        { wch: 36 },
        { wch: 14 },
        { wch: 10 },
      ]
      const wb = XLSX.utils.book_new()
      XLSX.utils.book_append_sheet(wb, ws, 'Daftar Peserta')

      const dateStr = new Date().toISOString().slice(0, 10)
      const safeName = currentProject.name.replace(/[^a-zA-Z0-9-_]/g, '_').slice(0, 40)
      const filename = `Daftar_Peserta_${safeName}_${dateStr}.xlsx`
      XLSX.writeFile(wb, filename)

      toast({
        title: 'Ekspor berhasil',
        description: `${filename} — ${database.length} peserta (Selesai: ${doneCount}, Belum: ${belumCount}).`,
      })
    } catch (err) {
      console.error('[SAATIRIL ADMIN] Excel export failed:', err)
      toast({
        title: 'Ekspor gagal',
        description: 'Terjadi kesalahan saat membuat file Excel.',
        variant: 'destructive',
      })
    }
  }, [currentProject, database, doneCount, belumCount, toast])

  // ── Render: Daftar Peserta (replaces Status Panel + Live Command Center) ──
  // Shows: Total / Selesai / Belum stats + scrollable participant list +
  // Excel export button. Gives admin a single control panel for monitoring
  // participant progress and exporting data for offline verification.
  const renderDaftarPeserta = () => (
    <Card className={`${PANEL} ${BORDER} shadow-lg flex flex-col`}>
      <CardHeader className="pb-2 shrink-0">
        <CardTitle className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2 text-sm font-semibold tracking-wide text-[#c4b5fd]">
            <Users className="size-4" style={{ color: GOLD }} />
            Daftar Peserta
          </div>
          <Button
            onClick={exportToExcel}
            disabled={database.length === 0}
            variant="outline"
            size="sm"
            className="h-8 gap-1.5 border-emerald-400/30 bg-emerald-400/10 text-emerald-300 hover:bg-emerald-400/20 hover:text-emerald-200 disabled:opacity-40"
            title="Ekspor daftar peserta & status ke Excel"
          >
            <FileSpreadsheet className="size-3.5" />
            <span className="hidden sm:inline">Excel</span>
            <Download className="size-3" />
          </Button>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 flex-1 min-h-0">
        {/* ── Stats: ANTREAN / PROSES / SELESAI / TOTAL (mirrors MC panel) ─────
            4 pills sinkron dengan halaman MC + Operator agar semua user pantau
            progres simultan dengan kategori yang sama. */}
        <div className="grid grid-cols-4 gap-1.5 shrink-0">
          {/* ANTREAN (pending) */}
          <div className="rounded-lg bg-[#1a0b2e]/60 px-1.5 py-2 text-center border border-[#533485]/40">
            <div className="flex items-center justify-center gap-1 mb-0.5">
              <Users className="size-2.5 text-[#c4b5fd]/70" />
            </div>
            <span className="text-lg font-bold text-[#c4b5fd]">{antreanCount}</span>
            <span className="block text-[8px] uppercase tracking-wider text-[#c4b5fd]/70">Antrean</span>
          </div>
          {/* PROSES (sent + active) */}
          <div className="rounded-lg bg-[#1a0b2e]/60 px-1.5 py-2 text-center border border-cyan-500/30">
            <div className="flex items-center justify-center gap-1 mb-0.5">
              <Send className="size-2.5 text-cyan-400/70" />
            </div>
            <span className="text-lg font-bold text-cyan-400">{prosesCount}</span>
            <span className="block text-[8px] uppercase tracking-wider text-cyan-400/70">Proses</span>
          </div>
          {/* SELESAI (done) */}
          <div className="rounded-lg bg-[#1a0b2e]/60 px-1.5 py-2 text-center border border-emerald-500/30">
            <div className="flex items-center justify-center gap-1 mb-0.5">
              <CheckCircle2 className="size-2.5 text-emerald-400/70" />
            </div>
            <span className="text-lg font-bold text-emerald-400">{doneCount}</span>
            <span className="block text-[8px] uppercase tracking-wider text-emerald-400/70">Selesai</span>
          </div>
          {/* TOTAL */}
          <div className="rounded-lg bg-[#1a0b2e]/60 px-1.5 py-2 text-center border border-amber-500/30">
            <div className="flex items-center justify-center gap-1 mb-0.5">
              <Users className="size-2.5 text-amber-400/70" />
            </div>
            <span className="text-lg font-bold text-amber-400">{totalPeserta}</span>
            <span className="block text-[8px] uppercase tracking-wider text-amber-400/70">Total</span>
          </div>
        </div>

        {/* ── Progress bar ── */}
        {totalPeserta > 0 && (
          <div className="shrink-0">
            <div className="flex items-center justify-between mb-1">
              <span className="text-[10px] text-[#c4b5fd]/70">Progres</span>
              <span className="text-[10px] font-mono text-[#c4b5fd]">
                {doneCount}/{totalPeserta} ({Math.round((doneCount / totalPeserta) * 100)}%)
              </span>
            </div>
            <div className="h-1.5 w-full rounded-full bg-[#1a0b2e]/80 overflow-hidden">
              <div
                className="h-full rounded-full bg-emerald-400 transition-all duration-300"
                style={{ width: `${(doneCount / totalPeserta) * 100}%` }}
              />
            </div>
          </div>
        )}

        {/* ── Auto-save indicator ── */}
        {lastSavedAt && (
          <div className="shrink-0 flex items-center gap-1.5 text-[10px] text-[#c4b5fd]/50">
            <CheckCircle2 className="size-3 text-emerald-500/60" />
            <span>Tersimpan otomatis{lastSavedAt ? ` — ${new Date(lastSavedAt).toLocaleTimeString('id-ID')}` : ''}</span>
          </div>
        )}

        {/* ── Disk-save status (visible: cumulative saved vs failed) ── */}
        <div className="shrink-0">
          <Badge
            variant="outline"
            className={
              diskSaveStats.failed > 0
                ? 'border-red-500/40 text-red-400'
                : 'border-green-500/40 text-green-400'
            }
          >
            <Folder className="h-3 w-3 mr-1" />
            Disk: {diskSaveStats.saved} tersimpan
            {diskSaveStats.failed > 0 && ` · ${diskSaveStats.failed} gagal`}
          </Badge>
        </div>

        <Separator className="bg-[#533485]/40 shrink-0" />

        {/* ── Participant list ── */}
        <div className="flex flex-col min-h-0 flex-1">
          <div className="flex items-center justify-between mb-1.5 shrink-0">
            <span className="text-[10px] font-semibold uppercase tracking-wider text-[#c4b5fd]/70">
              Detail Peserta
            </span>
            {sentCount > 0 && (
              <span className="text-[10px] text-cyan-300/80">
                {sentCount} sedang dipotret
              </span>
            )}
          </div>

          {/* Column header */}
          <div
            className={`shrink-0 grid gap-1.5 px-2 py-1.5 text-[9px] font-semibold uppercase tracking-wider rounded-t-md ${isMobile ? 'grid-cols-[60px_1fr_60px]' : 'grid-cols-[28px_70px_1fr_70px]'}`}
            style={{ backgroundColor: '#1a0b2e80', color: '#c4b5fd99' }}
          >
            {!isMobile && <span>No</span>}
            <span>NIM</span>
            <span>Nama</span>
            <span className="text-right">Status</span>
          </div>

          <ScrollArea className={`flex-1 min-h-0 ${isMobile ? 'max-h-64' : 'max-h-72'}`}>
            <div className="flex flex-col">
              {database.length === 0 ? (
                <div className="flex items-center justify-center py-8">
                  <p className="text-xs text-[#c4b5fd]/50">Belum ada peserta</p>
                </div>
              ) : (
                database.map((student, idx) => {
                  const isDone = student.status === 'done'
                  const isSent = student.status === 'sent'
                  const isActive = student.status.startsWith('active')
                  return (
                    <div
                      key={student.id}
                      className={`grid gap-1.5 items-center px-2 py-1.5 border-b border-[#533485]/20 transition-colors hover:bg-white/5 ${isMobile ? 'grid-cols-[60px_1fr_60px]' : 'grid-cols-[28px_70px_1fr_70px]'}`}
                      style={{
                        backgroundColor: isDone
                          ? 'rgba(34,197,94,0.05)'
                          : isSent
                            ? 'rgba(6,182,212,0.05)'
                            : isActive
                              ? 'rgba(212,175,55,0.05)'
                              : 'transparent',
                      }}
                    >
                      {!isMobile && <span className="text-[10px] font-mono text-[#c4b5fd]/50">{idx + 1}</span>}
                      <span className={`font-mono truncate text-[#c4b5fd]/80 ${isMobile ? 'text-[9px]' : 'text-[10px]'}`}>{student.nim}</span>
                      <span
                        className={`text-xs font-medium truncate ${isDone ? 'line-through text-[#c4b5fd]/50' : 'text-[#e0e0ff]'}`}
                      >
                        {student.nama}
                      </span>
                      <div className="flex justify-end">
                        {isDone ? (
                          <Badge
                            className="text-[9px] px-1 py-0"
                            style={{
                              backgroundColor: 'rgba(74,222,128,0.15)',
                              color: '#4ade80',
                              border: '1px solid rgba(74,222,128,0.3)',
                            }}
                          >
                            <CheckCircle2 className="size-2.5 mr-0.5" />
                            Selesai
                          </Badge>
                        ) : isSent ? (
                          <Badge
                            className="text-[9px] px-1 py-0 animate-pulse"
                            style={{
                              backgroundColor: 'rgba(6,182,212,0.15)',
                              color: CYAN,
                              border: '1px solid rgba(6,182,212,0.3)',
                            }}
                          >
                            <Camera className="size-2.5 mr-0.5" />
                            Proses
                          </Badge>
                        ) : isActive ? (
                          <Badge
                            className="text-[9px] px-1 py-0 animate-pulse"
                            style={{
                              backgroundColor: 'rgba(212,175,55,0.15)',
                              color: GOLD,
                              border: '1px solid rgba(212,175,55,0.3)',
                            }}
                          >
                            <Camera className="size-2.5 mr-0.5" />
                            Aktif
                          </Badge>
                        ) : (
                          <Badge
                            className="text-[9px] px-1 py-0"
                            style={{
                              backgroundColor: 'rgba(251,191,36,0.1)',
                              color: '#fbbf24',
                              border: '1px solid rgba(251,191,36,0.25)',
                            }}
                          >
                            <Clock className="size-2.5 mr-0.5" />
                            Belum
                          </Badge>
                        )}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </ScrollArea>
        </div>
      </CardContent>
    </Card>
  )

  // ── Render: Usage Guide (Panduan Penggunaan) ──────────────────────
  const [showGuide, setShowGuide] = useState(false)
  const renderUsageGuide = () => {
    return (
      <Card className={`${PANEL} shadow-lg`} style={{ borderColor: GOLD }}>
        <CardHeader className="pb-2">
          <CardTitle className="flex items-center gap-2 text-sm font-semibold tracking-wide text-[#c4b5fd]">
            <Monitor className="size-4" style={{ color: GOLD }} />
            Panduan Penggunaan
            <Button
              variant="outline"
              size="sm"
              className="ml-auto text-xs h-6 px-2"
              style={{ borderColor: GOLD, color: GOLD }}
              onClick={() => setShowGuide(!showGuide)}
            >
              {showGuide ? '▲ Tutup' : '▼ Buka'}
            </Button>
          </CardTitle>
        </CardHeader>
        {showGuide && (
        <CardContent className="flex flex-col gap-3 pt-0">
          {/* MODE 1: Admin APK + MC + Operator (1 HP) */}
          <div>
            <p className="text-xs font-bold mb-1" style={{ color: '#4ade80' }}>
              📱 MODE 1: 1 HP untuk Semua (Admin+MC+Operator)
            </p>
            <ul className="space-y-0.5 pl-2">
              <li className="text-xs text-[#c4b5fd]">• Buat proyek → pilih mode → import Excel → pilih folder → MULAI</li>
              <li className="text-xs text-[#c4b5fd]">• Tab Prosesi: MC panggil + Operator foto dalam 1 layar</li>
              <li className="text-xs text-[#c4b5fd]">• Foto tersimpan otomatis ke folder yang dipilih</li>
              <li className="text-xs text-[#c4b5fd]">• Cocok untuk wisuda kecil (&lt;100 mahasiswa)</li>
            </ul>
          </div>

          {/* MODE 2: Admin + Operator via WiFi */}
          <div>
            <p className="text-xs font-bold mb-1" style={{ color: CYAN }}>
              📶 MODE 2: Admin HP + Operator via WiFi (LAN)
            </p>
            <ul className="space-y-0.5 pl-2">
              <li className="text-xs text-[#c4b5fd]">• Admin HP: buat proyek → start server → aktifkan hotspot/router</li>
              <li className="text-xs text-[#c4b5fd]">• Operator HP/Tablet: install APK → pilih Operator → input IP admin</li>
              <li className="text-xs text-[#c4b5fd]">• MC: scan QR MC → browser → /mc → connect via WiFi</li>
              <li className="text-xs text-[#c4b5fd]">• Operator: scan QR Op → browser → /operator → kamera + shutter</li>
              <li className="text-xs text-[#c4b5fd]">• Cocok untuk wisuda sedang (100-500 mahasiswa, &lt;500 orang)</li>
            </ul>
          </div>

          {/* MODE 3A: BLE Remote (MC=Client, Admin APK=Server) */}
          <div>
            <p className="text-xs font-bold mb-1" style={{ color: '#3b82f6' }}>
              🔵 MODE 3A: MC BLE Remote — Admin HP (MC=Client)
            </p>
            <ul className="space-y-0.5 pl-2">
              <li className="text-xs text-[#c4b5fd]">• Admin HP: buat proyek → BLE server otomatis aktif</li>
              <li className="text-xs text-[#c4b5fd]">• MC HP: install saatiril-mc.apk → pilih 'BLE REMOTE' → scan → connect</li>
              <li className="text-xs text-[#c4b5fd]">• MC tekan PANGGIL → WiFi trigger → Admin foto</li>
              <li className="text-xs text-[#c4b5fd]">• 100% immune WiFi — cocok untuk 3000+ orang</li>
            </ul>
          </div>

          {/* MODE 3B: BLE Server (MC=Server, Electron=Client) */}
          <div>
            <p className="text-xs font-bold mb-1" style={{ color: '#8b5cf6' }}>
              🟣 MODE 3B: MC BLE Server — Admin Laptop (MC=Server)
            </p>
            <ul className="space-y-0.5 pl-2">
              <li className="text-xs text-[#c4b5fd]">• MC HP: install saatiril-mc.apk → pilih 'BLE SERVER' → advertise</li>
              <li className="text-xs text-[#c4b5fd]">• Laptop Electron: buka /admin-ble → klik Connect → scan Bluetooth</li>
              <li className="text-xs text-[#c4b5fd]">• MC tekan PANGGIL → Bluetooth notify → Laptop foto</li>
              <li className="text-xs text-[#c4b5fd]">• 100% immune WiFi — 1 laptop + 1 HP, no WiFi needed</li>
            </ul>
          </div>

          {/* MODE 4: Electron + Wired LAN */}
          <div>
            <p className="text-xs font-bold mb-1" style={{ color: '#fbbf24' }}>
              🖥️ MODE 4: Laptop Electron + Wired LAN (Kabel)
            </p>
            <ul className="space-y-0.5 pl-2">
              <li className="text-xs text-[#c4b5fd]">• Laptop Admin: run saatiril-portable.exe → buat proyek</li>
              <li className="text-xs text-[#c4b5fd]">• Colok semua laptop via Ethernet Switch (kabel LAN)</li>
              <li className="text-xs text-[#c4b5fd]">• MC: install saatiril-mc.apk → pilih 'WIFI/LAN' → input IP laptop</li>
              <li className="text-xs text-[#c4b5fd]">• Operator: buka browser → /operator → connect</li>
              <li className="text-xs text-[#c4b5fd]">• 100% immune WiFi — cocok untuk wisuda besar (3000+ orang)</li>
              <li className="text-xs text-[#c4b5fd]">• Google Drive backup: pilih folder G:\My Drive\ di tab Admin</li>
            </ul>
          </div>

          {/* MODE 5: External touch screen via HDMI (NEW — instant, no WiFi) */}
          <div>
            <p className="text-xs font-bold mb-1" style={{ color: '#a78bfa' }}>
              🖥️✨ MODE 5: External Touch Screen via HDMI (NEW — instant, no WiFi)
            </p>
            <ul className="space-y-0.5 pl-2">
              <li className="text-xs text-[#c4b5fd]">• Laptop Admin: run portable.exe → buat proyek (admin + socket server)</li>
              <li className="text-xs text-[#c4b5fd]">• Colok layar sentuh external via HDMI kabel / transmitter (jarak berapa pun)</li>
              <li className="text-xs text-[#c4b5fd]">• Launch saatiril-mc-electron.exe --host=127.0.0.1 → auto fullscreen di external screen</li>
              <li className="text-xs text-[#c4b5fd]">• Sync MC↔Operator = localhost (INSTANT, no WiFi congestion — HDMI hanya bawa sinyal display)</li>
              <li className="text-xs text-[#c4b5fd]">• --display=1 force layar primer; --display=2/3 pilih display spesifik</li>
              <li className="text-xs text-[#c4b5fd]">• Dual photo: 2 kamera USB capture → 2 Operator App (--channel=1/--channel=2) auto-pakai kamera berbeda</li>
              <li className="text-xs text-[#c4b5fd]">• Cocok untuk wisuda 2 kamera + 2 operator + 2 MC di 1 laptop admin</li>
            </ul>
          </div>

          {/* Tips */}
          <div className="rounded-md p-2.5" style={{ backgroundColor: 'rgba(212,175,55,0.08)', border: '1px solid rgba(212,175,55,0.2)' }}>
            <p className="text-xs font-bold mb-1.5" style={{ color: GOLD }}>💡 Tips Penting</p>
            <ul className="space-y-1">
              <li className="text-xs text-[#c4b5fd]">• Mode Prosesi (APK): blokir WhatsApp/Instagram selama prosesi</li>
              <li className="text-xs text-[#c4b5fd]">• Google Drive: foto tersimpan lokal dulu, upload async ke cloud</li>
              <li className="text-xs text-[#c4b5fd]">• Folder foto: otomatis dibuat subfolder dengan nama proyek</li>
              <li className="text-xs text-[#c4b5fd]">• Hand Trigger (✋): MediaPipe Hands untuk foto hands-free</li>
              <li className="text-xs text-[#c4b5fd]">• Frame Overlay: tambahkan frame PNG dengan logo wisuda</li>
              <li className="text-xs text-[#c4b5fd]">• Export Excel: download daftar peserta dengan status</li>
              <li className="text-xs text-[#c4b5fd]">• MC-Only APK (saatiril-mc.apk): APK terpisah untuk MC, terkunci penuh</li>
              <li className="text-xs text-[#c4b5fd]">• Operator Plan B: tombol 'PANGGIL MANUAL' jika MC terputus</li>
              <li className="text-xs text-[#c4b5fd]">• 3 APK: saatiril-andro.apk (full) + saatiril-mc.apk (MC) + saatiril-portable.exe (Electron)</li>
              <li className="text-xs text-[#c4b5fd]">• ✨ Dual 50/50: mode dual photo/photoshoot → dropdown Jalur "Jalur 1 dan 2 — 50/50" → 2 kolom resizable (MC+Operator per channel, no scroll)</li>
              <li className="text-xs text-[#c4b5fd]">• ✨ Jalur dropdown: "Jalur 1" / "Jalur 2" (full single channel) / "Jalur 1 dan 2" (compact 50/50 kedua channel)</li>
              <li className="text-xs text-[#c4b5fd]">• ✨ Highlight: peserta teratas (siap dipanggil) = button-like gold ▶; peserta aktif (difoto) = gold prominent ◆</li>
              <li className="text-xs text-[#c4b5fd]">• ✨ Per-channel camera: 2 Operator App di laptop sama auto-pakai kamera berbeda (no conflict, pilihan tersimpan per-channel)</li>
              <li className="text-xs text-[#c4b5fd]">• ✨ Wisuda instant: STUDENT_DONE re-emit untuk browser MC + no stale-reset scan → MC panggil langsung setelah operator foto</li>
            </ul>
          </div>

          {/* Shutter modes + Camera + Filename */}
          <div className="flex flex-col gap-1.5">
            <div>
              <p className="text-xs font-bold" style={{ color: GOLD }}>📸 Mode Shutter:</p>
              <p className="text-xs text-[#c4b5fd]">M=Manual | 3s/5s/10s=Timer | ✋=Hand Trigger (MediaPipe)</p>
            </div>
            <div>
              <p className="text-xs font-bold" style={{ color: GOLD }}>📷 Sumber Kamera (3 pilihan):</p>
              <p className="text-xs text-[#c4b5fd]">Kamera Belakang HP | Kamera Depan HP | USB Capture Card (DSLR+HDMI)</p>
            </div>
            <div>
              <p className="text-xs font-bold" style={{ color: GOLD }}>📁 Format Nama File:</p>
              <p className="text-xs text-[#c4b5fd]">Standar: NIM_Nama_1_Toga.jpg + NIM_Nama_2_Ijazah.jpg</p>
              <p className="text-xs text-[#c4b5fd]">Photoshoot: NIM_Nama.jpg (atau _Ch2.jpg untuk dual)</p>
              <p className="text-xs text-[#c4b5fd]">Retake: NIM_Nama_1_Toga_v2.jpg (otomatis versi)</p>
            </div>
          </div>
        </CardContent>
        )}
      </Card>
    )
  }

  // ── Render: Google Drive / Cloud Backup (PER-PROJECT, opt-in) ──────
  const renderGoogleDriveBackup = () => {
    const api = window.saatirilAPI
    if (!api?.isElectron || !api.selectBackupFolder) return null // Only show in Electron

    // PER-PROJECT drive folder (from the current project's config). NOT
    // auto-connected on project creation — the admin explicitly picks a
    // folder per-project. Each project can have its own folder (or none).
    const driveFolder = currentProject?.config?.driveFolder ?? null
    const isConnected = driveFolder != null

    return (
      <Card className={`${PANEL} shadow-lg`} style={{ borderColor: isConnected ? '#06b6d4' : BORDER }}>
        <CardHeader className="pb-2">
          <CardTitle className="flex items-center gap-2 text-sm font-semibold tracking-wide text-[#c4b5fd]">
            <CloudUpload className="size-4" style={{ color: isConnected ? '#06b6d4' : GOLD }} />
            Google Drive Backup
            {isConnected ? (
              <span className="ml-auto text-xs font-normal" style={{ color: '#06b6d4' }}>
                ● Terhubung (proyek ini)
              </span>
            ) : (
              <span className="ml-auto text-xs font-normal" style={{ color: '#c4b5fd', opacity: 0.7 }}>
                Per-proyek
              </span>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent className="pt-0">
          {isConnected ? (
            <div className="flex flex-col gap-2">
              <div className="rounded-md bg-[#1a0b2e]/60 border border-[#533485]/50 p-2">
                <p className="break-all text-xs font-mono" style={{ color: '#c4b5fd' }}>
                  {driveFolder}
                </p>
              </div>
              <p className="text-xs" style={{ color: '#c4b5fd' }}>
                ✅ Foto proyek ini otomatis di-copy ke folder di atas setelah
                disimpan lokal. Google Drive Desktop akan sync ke cloud.
              </p>
              <p className="text-xs" style={{ color: '#c4b5fd', opacity: 0.7 }}>
                ⚠️ Hanya berlaku untuk proyek <strong>{currentProject?.name ?? 'ini'}</strong>. Proyek lain perlu diatur folder-nya sendiri.
              </p>
              <Button
                variant="outline"
                size="sm"
                className="w-full text-xs"
                style={{ borderColor: '#ef4444', color: '#ef4444' }}
                onClick={() => {
                  // Clear THIS project's drive folder (set to null).
                  if (currentProject) {
                    updateCurrentProject({
                      ...currentProject,
                      config: { ...currentProject.config, driveFolder: null },
                    })
                  }
                }}
              >
                <Link2Off className="size-3 mr-1" />
                Putuskan Backup (proyek ini)
              </Button>
            </div>
          ) : (
            <div className="flex flex-col gap-2">
              <p className="text-xs" style={{ color: '#c4b5fd' }}>
                Backup otomatis foto ke Google Drive atau folder cloud lain —
                <strong> per-proyek</strong>. TIDAK otomatis terhubung saat
                membuat proyek. Aktifkan hanya untuk proyek yang membutuhkan,
                tiap proyek bisa punya folder sendiri (atau tidak sama sekali).
              </p>
              <Button
                size="sm"
                className="w-full font-semibold"
                style={{ backgroundColor: '#06b6d4', color: '#1a0b2e' }}
                onClick={async () => {
                  const folder = await api.selectBackupFolder()
                  if (folder && currentProject) {
                    // Save the folder to THIS project's config (per-project).
                    updateCurrentProject({
                      ...currentProject,
                      config: { ...currentProject.config, driveFolder: folder },
                    })
                  }
                }}
              >
                <Folder className="size-4 mr-2" />
                Pilih Folder untuk Proyek Ini
              </Button>
              <p className="text-xs" style={{ color: '#c4b5fd', opacity: 0.6 }}>
                💡 Install Google Drive for Desktop, lalu pilih folder
                "G:\My Drive\Saatiril" untuk auto-sync ke cloud. Tiap proyek
                bisa punya folder sendiri (atau tidak sama sekali).
              </p>
            </div>
          )}
        </CardContent>
      </Card>
    )
  }

  // ── Render: LAN Access Distribution ──────────────────────────────
  const renderLanAccess = () => {
    const lanIP = lanInfo?.ips?.[0]?.address ?? ''

    return (
    <Card className={`${PANEL} ${BORDER} shadow-lg`}>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-sm font-semibold tracking-wide text-[#c4b5fd]">
          <Wifi className="size-4" style={{ color: GOLD }} />
          LAN Access Distribution
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {/* ── Section A: APK Android (Native App) ── */}
        <div className="rounded-md p-3 text-xs" style={{ backgroundColor: '#22c55e15', border: '1px solid #22c55e33', color: '#86efac' }}>
          <p className="font-semibold mb-1.5" style={{ color: '#4ade80' }}>📱 Operator Android — APK Saatiril:</p>
          <p className="mb-1.5 opacity-80">Cara paling mudah untuk operator kamera di HP Android. Tidak perlu Chrome Flag. Langsung install dan jalankan.</p>
          <ol className="space-y-1 pl-1">
            <li><strong>1. Download & install APK</strong> — Gunakan tombol "📱 APK Android" di bawah untuk download, salin link, atau scan QR Code</li>
            <li><strong>2. Aktifkan USB OTG</strong> — Buka Pengaturan → Cari "OTG" → Aktifkan (beberapa HP menyebutnya "USB Host" atau "Koneksi USB")</li>
            <li><strong>3. Hubungkan USB capture card</strong> ke HP menggunakan kabel OTG. Pastikan HDMI terhubung ke kamera DSLR</li>
            <li><strong>4. Periksa notifikasi USB</strong> — Saat colok USB, akan muncul notifikasi. Tap dan izinkan akses</li>
            <li><strong>5. Izinkan akses kamera</strong> — Pengaturan → Aplikasi → Saatiril Operator → Izin → Kamera → Izinkan</li>
            <li><strong>6. Buka aplikasi Saatiril Operator</strong> — Pilih kamera USB dari daftar kamera yang tersedia</li>
          </ol>
          <div className="mt-2 p-2 rounded" style={{ backgroundColor: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.2)' }}>
            <p className="font-semibold" style={{ color: '#f87171' }}>⚠️ Jika USB capture card tidak terdeteksi:</p>
            <ul className="space-y-0.5 mt-1 pl-3" style={{ color: '#fca5a5' }}>
              <li>• Pastikan USB OTG sudah aktif di Pengaturan HP</li>
              <li>• Cabut dan pasang ulang USB capture card</li>
              <li>• Tutup aplikasi sepenuhnya (swipe close) lalu buka kembali</li>
              <li>• Pastikan tidak ada aplikasi lain yang menggunakan USB camera</li>
              <li>• Coba gunakan kabel OTG yang berbeda</li>
              <li>• Jika masih tidak terdeteksi, gunakan kamera HP (depan/belakang) sebagai alternatif</li>
            </ul>
          </div>
        </div>

        {/* ── Section B: Google Chrome Browser ── */}
        <div className="rounded-md p-3 text-xs" style={{ backgroundColor: '#f59e0b15', border: '1px solid #f59e0b33', color: '#fde68a' }}>
          <p className="font-semibold mb-1.5" style={{ color: GOLD }}>💻 Operator Chrome (PC / Laptop / Android):</p>
          <p className="mb-1.5 opacity-80">Jika menggunakan browser Chrome, WAJIB aktifkan Chrome Flag agar kamera bisa diakses melalui HTTP.</p>
          <ol className="space-y-1 pl-1">
            <li><strong>1. Buka Chrome</strong> — Di PC, laptop, atau HP Android</li>
            <li><strong>2. Aktifkan Chrome Flag</strong> (WAJIB untuk akses kamera via HTTP):
              <ul className="mt-0.5 ml-3 space-y-0.5" style={{ color: '#fbbf24' }}>
                <li>• Buka tab baru → ketik <code className="px-1 py-0.5 rounded" style={{ backgroundColor: 'rgba(255,255,255,0.1)' }}>chrome://flags</code></li>
                <li>• Cari <code className="px-1 py-0.5 rounded" style={{ backgroundColor: 'rgba(255,255,255,0.1)' }}>insecure origin</code></li>
                <li>• Pada "Insecure origins treated as secure", masukkan URL server (contoh: <code className="px-1 py-0.5 rounded" style={{ backgroundColor: 'rgba(255,255,255,0.1)' }}>http://192.168.x.x:3000</code>)</li>
                <li>• Pilih <strong>Enabled</strong> → Klik/Tap <strong>Relaunch</strong></li>
              </ul>
            </li>
            <li><strong>3. Jika di Android</strong> — Aktifkan USB OTG, hubungkan USB capture card via kabel OTG, izinkan akses kamera Chrome di Pengaturan</li>
            <li><strong>4. Jika di PC/Laptop</strong> — Hubungkan USB capture card langsung ke port USB, Chrome akan otomatis mendeteksi</li>
            <li><strong>5. Buka link Operator</strong> — Gunakan tombol "Copy Link Operator" di bawah untuk mendapatkan link</li>
            <li><strong>6. Izinkan akses kamera</strong> — Saat diminta oleh Chrome, klik/tap "Izinkan"</li>
          </ol>
          <div className="mt-2 p-2 rounded" style={{ backgroundColor: 'rgba(245,158,11,0.1)', border: '1px solid rgba(245,158,11,0.2)' }}>
            <p className="font-semibold" style={{ color: '#fbbf24' }}>💡 Tips Chrome:</p>
            <ul className="space-y-0.5 mt-1 pl-3 opacity-80">
              <li>• Chrome Flag hanya perlu diatur sekali per perangkat</li>
              <li>• Jika kamera tidak muncul, pastikan Chrome Flag sudah di-enable dan Chrome sudah di-relaunch</li>
              <li>• Di PC/Laptop, cek juga Pengaturan Privasi Windows → izinkan Chrome akses kamera</li>
              <li>• Pastikan tidak ada tab lain yang sedang menggunakan kamera</li>
            </ul>
          </div>
        </div>

        <p className="text-xs opacity-60 px-1">📌 MC tidak perlu USB OTG atau kamera — cukup buka link MC di browser saja. Hanya Operator yang perlu kamera.</p>
        {/* Session Password Display */}
        {currentProject?.config?.sessionPassword && currentProject.config.sessionPassword !== '__PASSWORD_SET__' && (
          <div className="rounded-md p-3 text-xs" style={{ backgroundColor: '#22c55e15', border: '1px solid #22c55e33', color: '#86efac' }}>
            <p className="font-semibold mb-1" style={{ color: '#4ade80' }}>🔐 Password Sesi LAN:</p>
            <p className="font-mono text-sm font-bold tracking-wider" style={{ color: '#4ade80' }}>
              {currentProject.config.sessionPassword}
            </p>
            <p className="mt-1 opacity-70">Berikan password ini kepada Operator dan MC agar bisa bergabung ke sesi.</p>
          </div>
        )}
        {mode === 'single' || mode === 'single-photoshoot' ? (
          <div className="flex flex-col gap-2">
            <div className="flex gap-2">
              <Button
                variant="outline"
                className="flex-1 justify-start gap-2 border-[#533485] bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                onClick={() => copyLink('mc', 1)}
              >
                <Copy className="size-3.5" />
                Copy Link MC
              </Button>
              <Button
                variant="outline"
                className="shrink-0 gap-1.5 border-[#533485] bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                onClick={() => showQrCode('mc', 1)}
                title="Tampilkan Kode QR untuk MC"
              >
                <QrCode className="size-3.5" />
              </Button>
            </div>
            <div className="flex gap-2">
              <Button
                variant="outline"
                className="flex-1 justify-start gap-2 border-[#533485] bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                onClick={() => copyLink('operator', 1)}
              >
                <Copy className="size-3.5" />
                Copy Link Operator
              </Button>
              <Button
                variant="outline"
                className="shrink-0 gap-1.5 border-[#533485] bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                onClick={() => showQrCode('operator', 1)}
                title="Tampilkan Kode QR untuk Operator"
              >
                <QrCode className="size-3.5" />
              </Button>
            </div>
            {/* ── Download Section: APK + MC APK + Portable ── */}
            <Separator className="bg-[#533485]/40" />
            <div className="mb-1 text-xs font-semibold uppercase tracking-wider" style={{ color: '#4ade80' }}>
              <Package className="size-3 inline mr-1" />APK Saatiril Android (Full)
            </div>
            {apkInfo?.available ? (
              <>
                <div className="text-xs mb-1.5 opacity-70" style={{ color: '#86efac' }}>
                  ✅ APK tersedia ({apkInfo.sizeMB} MB)
                </div>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1 justify-start gap-2 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                    onClick={() => {
                      const url = apkInfo?.downloadUrl || `https://github.com/${GITHUB_REPO}/releases/download/latest/saatiril-andro.apk`
                      if (url) window.open(url, '_blank')
                    }}
                  >
                    <Download className="size-3.5" />
                    Download APK
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                    onClick={() => {
                      const url = `https://github.com/${GITHUB_REPO}/releases/download/latest/saatiril-andro.apk`
                      if (navigator.clipboard) navigator.clipboard.writeText(url)
                    }}
                    title="Salin Link APK"
                  >
                    <Copy className="size-3.5" />
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                    onClick={() => {
                      setQrLink(`https://github.com/${GITHUB_REPO}/releases/download/latest/saatiril-andro.apk`)
                      setQrLabel('APK Saatiril Android')
                      setQrDialogOpen(true)
                    }}
                    title="QR Code APK Android"
                  >
                    <QrCode className="size-3.5" />
                  </Button>
                </div>
              </>
            ) : (
              <div className="text-xs mb-1.5 opacity-70" style={{ color: '#fca5a5' }}>
                ⚠️ APK belum tersedia
              </div>
            )}

            {/* MC-Only APK download */}
            <Separator className="bg-[#533485]/40" />
            <div className="mb-1 text-xs font-semibold uppercase tracking-wider" style={{ color: '#8b5cf6' }}>
              APK Saatiril MC (BLE Remote)
            </div>
            {mcApkInfo?.available ? (
              <>
                <div className="text-xs mb-1.5 opacity-70" style={{ color: '#c4b5fd' }}>
                  ✅ MC APK tersedia ({mcApkInfo.sizeMB} MB)
                </div>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1 justify-start gap-2 border-[#8b5cf6]/40 bg-[#8b5cf610] text-[#8b5cf6] hover:bg-[#3b2263] hover:text-[#c4b5fd]"
                    onClick={() => {
                      const url = mcApkInfo?.downloadUrl || `https://github.com/${GITHUB_REPO}/releases/download/latest/saatiril-mc.apk`
                      if (url) window.open(url, '_blank')
                    }}
                  >
                    <Download className="size-3.5" />
                    Download MC APK
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#8b5cf6]/40 bg-[#8b5cf610] text-[#8b5cf6] hover:bg-[#3b2263] hover:text-[#c4b5fd]"
                    onClick={() => {
                      const url = `https://github.com/${GITHUB_REPO}/releases/download/latest/saatiril-mc.apk`
                      if (navigator.clipboard) navigator.clipboard.writeText(url)
                    }}
                    title="Salin Link MC APK"
                  >
                    <Copy className="size-3.5" />
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#8b5cf6]/40 bg-[#8b5cf610] text-[#8b5cf6] hover:bg-[#3b2263] hover:text-[#c4b5fd]"
                    onClick={() => {
                      setQrLink(`https://github.com/${GITHUB_REPO}/releases/download/latest/saatiril-mc.apk`)
                      setQrLabel('APK Saatiril MC (BLE)')
                      setQrDialogOpen(true)
                    }}
                    title="QR Code MC APK"
                  >
                    <QrCode className="size-3.5" />
                  </Button>
                </div>
              </>
            ) : (
              <div className="text-xs mb-1.5 opacity-70" style={{ color: '#fca5a5' }}>
                ⚠️ MC APK belum tersedia
              </div>
            )}

            {/* Portable Windows download */}
            <Separator className="bg-[#533485]/40" />
            <div className="mb-1 text-xs font-semibold uppercase tracking-wider" style={{ color: CYAN }}>
              <Monitor className="size-3 inline mr-1" />Saatiril Portable Windows
            </div>
            {portableInfo?.available ? (
              <>
                <div className="text-xs mb-1.5 opacity-70" style={{ color: '#67e8f9' }}>
                  ✅ Portable tersedia ({portableInfo.sizeMB} MB)
                </div>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1 justify-start gap-2 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                    onClick={() => {
                      const url = portableInfo?.downloadUrl || generatePortableLink()
                      if (url) window.open(url, '_blank')
                    }}
                  >
                    <Download className="size-3.5" />
                    Download Portable
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                    onClick={copyPortableLink}
                    title="Salin Link Portable"
                  >
                    <Copy className="size-3.5" />
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                    onClick={showPortableQrCode}
                    title="QR Code Portable Windows"
                  >
                    <QrCode className="size-3.5" />
                  </Button>
                </div>
              </>
            ) : (
              <div className="text-xs mb-1.5 opacity-70" style={{ color: '#fca5a5' }}>
                ⚠️ Portable belum tersedia
              </div>
            )}
          </div>
        ) : mode === 'dual-photoshoot' ? (
          /* Dual Photoshoot: 1 MC + 2 Operators */
          <div className="flex flex-col gap-4">
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: '#4ade80' }}>
                MC (1 orang)
              </div>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  className="flex-1 justify-start gap-2 border-emerald-400/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#4ade80]"
                  onClick={() => copyLink('mc', 1)}
                >
                  <Copy className="size-3.5" />
                  MC
                </Button>
                <Button
                  variant="outline"
                  className="shrink-0 gap-1.5 border-emerald-400/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#4ade80]"
                  onClick={() => showQrCode('mc', 1)}
                  title="QR Code MC"
                >
                  <QrCode className="size-3.5" />
                </Button>
              </div>
            </div>

            <Separator className="bg-[#533485]/40" />

            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: GOLD }}>
                Operator Kamera 1
              </div>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  className="flex-1 justify-start gap-2 border-[#d4af37]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                  onClick={() => copyLink('operator', 1)}
                >
                  <Copy className="size-3.5" />
                  Operator 1
                </Button>
                <Button
                  variant="outline"
                  className="shrink-0 gap-1.5 border-[#d4af37]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                  onClick={() => showQrCode('operator', 1)}
                  title="QR Code Operator 1"
                >
                  <QrCode className="size-3.5" />
                </Button>
              </div>
            </div>

            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: CYAN }}>
                Operator Kamera 2
              </div>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  className="flex-1 justify-start gap-2 border-[#06b6d4]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#06b6d4]"
                  onClick={() => copyLink('operator', 2)}
                >
                  <Copy className="size-3.5" />
                  Operator 2
                </Button>
                <Button
                  variant="outline"
                  className="shrink-0 gap-1.5 border-[#06b6d4]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#06b6d4]"
                  onClick={() => showQrCode('operator', 2)}
                  title="QR Code Operator 2"
                >
                  <QrCode className="size-3.5" />
                </Button>
              </div>
            </div>

            <Separator className="bg-[#533485]/40" />

            {/* ── Download Section: APK + Portable ── */}
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: '#4ade80' }}>
                <Package className="size-3 inline mr-1" />APK Saatiril Android
              </div>
              {apkInfo?.available ? (
                <>
                  <div className="text-xs mb-1.5 opacity-70" style={{ color: '#86efac' }}>
                    ✅ APK tersedia ({apkInfo.sizeMB} MB)
                  </div>
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      className="flex-1 justify-start gap-2 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                      onClick={() => {
                        const url = apkInfo?.downloadUrl || generateApkLink()
                        if (url) window.open(url, '_blank')
                      }}
                    >
                      <Download className="size-3.5" />
                      Download APK
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                      onClick={copyApkLink}
                      title="Salin Link APK"
                    >
                      <Copy className="size-3.5" />
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                      onClick={showApkQrCode}
                      title="QR Code APK Android"
                    >
                      <QrCode className="size-3.5" />
                    </Button>
                  </div>
                </>
              ) : (
                <div className="text-xs mb-1.5 opacity-70" style={{ color: '#fca5a5' }}>
                  ⚠️ APK belum tersedia
                </div>
              )}
            </div>

            {/* Portable Windows download */}
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: CYAN }}>
                <Monitor className="size-3 inline mr-1" />Saatiril Portable Windows
              </div>
              {portableInfo?.available ? (
                <>
                  <div className="text-xs mb-1.5 opacity-70" style={{ color: '#67e8f9' }}>
                    ✅ Portable tersedia ({portableInfo.sizeMB} MB)
                  </div>
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      className="flex-1 justify-start gap-2 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                      onClick={() => {
                        const url = portableInfo?.downloadUrl || generatePortableLink()
                        if (url) window.open(url, '_blank')
                      }}
                    >
                      <Download className="size-3.5" />
                      Download Portable
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                      onClick={copyPortableLink}
                      title="Salin Link Portable"
                    >
                      <Copy className="size-3.5" />
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                      onClick={showPortableQrCode}
                      title="QR Code Portable Windows"
                    >
                      <QrCode className="size-3.5" />
                    </Button>
                  </div>
                </>
              ) : (
                <div className="text-xs mb-1.5 opacity-70" style={{ color: '#fca5a5' }}>
                  ⚠️ Portable belum tersedia
                </div>
              )}
            </div>
          </div>
        ) : (
          <div className="flex flex-col gap-4">
            {/* Jalur Kiri */}
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: GOLD }}>
                Jalur Kiri
              </div>
              <div className="flex flex-col gap-2">
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1 justify-start gap-2 border-[#d4af37]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                    onClick={() => copyLink('mc', 1)}
                  >
                    <Copy className="size-3.5" />
                    MC 1
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#d4af37]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                    onClick={() => showQrCode('mc', 1)}
                    title="QR Code MC 1"
                  >
                    <QrCode className="size-3.5" />
                  </Button>
                </div>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1 justify-start gap-2 border-[#d4af37]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                    onClick={() => copyLink('operator', 1)}
                  >
                    <Copy className="size-3.5" />
                    Operator 1
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#d4af37]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#d4af37]"
                    onClick={() => showQrCode('operator', 1)}
                    title="QR Code Operator 1"
                  >
                    <QrCode className="size-3.5" />
                  </Button>
                </div>
              </div>
            </div>

            <Separator className="bg-[#533485]/40" />

            {/* Jalur Kanan */}
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: CYAN }}>
                Jalur Kanan
              </div>
              <div className="flex flex-col gap-2">
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1 justify-start gap-2 border-[#06b6d4]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#06b6d4]"
                    onClick={() => copyLink('mc', 2)}
                  >
                    <Copy className="size-3.5" />
                    MC 2
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#06b6d4]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#06b6d4]"
                    onClick={() => showQrCode('mc', 2)}
                    title="QR Code MC 2"
                  >
                    <QrCode className="size-3.5" />
                  </Button>
                </div>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    className="flex-1 justify-start gap-2 border-[#06b6d4]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#06b6d4]"
                    onClick={() => copyLink('operator', 2)}
                  >
                    <Copy className="size-3.5" />
                    Operator 2
                  </Button>
                  <Button
                    variant="outline"
                    className="shrink-0 gap-1.5 border-[#06b6d4]/30 bg-[#1a0b2e]/60 text-[#c4b5fd] hover:bg-[#3b2263] hover:text-[#06b6d4]"
                    onClick={() => showQrCode('operator', 2)}
                    title="QR Code Operator 2"
                  >
                    <QrCode className="size-3.5" />
                  </Button>
                </div>
              </div>
            </div>

            <Separator className="bg-[#533485]/40" />

            {/* ── Download Section: APK + Portable ── */}
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: '#4ade80' }}>
                <Package className="size-3 inline mr-1" />APK Saatiril Android
              </div>
              {apkInfo?.available ? (
                <>
                  <div className="text-xs mb-1.5 opacity-70" style={{ color: '#86efac' }}>
                    ✅ APK tersedia ({apkInfo.sizeMB} MB)
                  </div>
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      className="flex-1 justify-start gap-2 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                      onClick={() => {
                        const url = apkInfo?.downloadUrl || generateApkLink()
                        if (url) window.open(url, '_blank')
                      }}
                    >
                      <Download className="size-3.5" />
                      Download APK
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                      onClick={copyApkLink}
                      title="Salin Link APK"
                    >
                      <Copy className="size-3.5" />
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#4ade80]/40 bg-[#22c55e10] text-[#4ade80] hover:bg-[#3b2263] hover:text-[#86efac]"
                      onClick={showApkQrCode}
                      title="QR Code APK Android"
                    >
                      <QrCode className="size-3.5" />
                    </Button>
                  </div>
                </>
              ) : (
                <div className="text-xs mb-1.5 opacity-70" style={{ color: '#fca5a5' }}>
                  ⚠️ APK belum tersedia
                </div>
              )}
            </div>

            {/* Portable Windows download */}
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wider" style={{ color: CYAN }}>
                <Monitor className="size-3 inline mr-1" />Saatiril Portable Windows
              </div>
              {portableInfo?.available ? (
                <>
                  <div className="text-xs mb-1.5 opacity-70" style={{ color: '#67e8f9' }}>
                    ✅ Portable tersedia ({portableInfo.sizeMB} MB)
                  </div>
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      className="flex-1 justify-start gap-2 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                      onClick={() => {
                        const url = portableInfo?.downloadUrl || generatePortableLink()
                        if (url) window.open(url, '_blank')
                      }}
                    >
                      <Download className="size-3.5" />
                      Download Portable
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                      onClick={copyPortableLink}
                      title="Salin Link Portable"
                    >
                      <Copy className="size-3.5" />
                    </Button>
                    <Button
                      variant="outline"
                      className="shrink-0 gap-1.5 border-[#06b6d4]/40 bg-[#06b6d410] text-[#06b6d4] hover:bg-[#3b2263] hover:text-[#67e8f9]"
                      onClick={showPortableQrCode}
                      title="QR Code Portable Windows"
                    >
                      <QrCode className="size-3.5" />
                    </Button>
                  </div>
                </>
              ) : (
                <div className="text-xs mb-1.5 opacity-70" style={{ color: '#fca5a5' }}>
                  ⚠️ Portable belum tersedia
                </div>
              )}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
  }


  // ── Render: Network Tips ──────────────────────────────────────────
  const renderNetworkTips = () => {
    const quality = networkHealth.networkQuality
    const avgLatency = networkHealth.avgLatencyMs
    const isPoor = quality === 'poor' || quality === 'fair'

    return (
      <Card className={`${PANEL} ${BORDER} shadow-lg`}>
        <CardHeader className="pb-2">
          <CardTitle className="flex items-center gap-2 text-sm font-semibold tracking-wide text-[#c4b5fd]">
            <Zap className="size-4" style={{ color: isPoor ? '#f87171' : GOLD }} />
            Tips Jaringan
            {avgLatency >= 0 && (
              <Badge
                className="text-[9px] ml-auto px-1.5 py-0.5 border-0"
                style={{
                  backgroundColor: quality === 'excellent' ? 'rgba(74,222,128,0.2)'
                    : quality === 'good' ? 'rgba(163,230,53,0.2)'
                    : quality === 'fair' ? 'rgba(251,191,36,0.2)'
                    : quality === 'poor' ? 'rgba(248,113,113,0.2)'
                    : 'rgba(196,181,253,0.15)',
                  color: quality === 'excellent' ? '#4ade80'
                    : quality === 'good' ? '#a3e635'
                    : quality === 'fair' ? '#fbbf24'
                    : quality === 'poor' ? '#f87171'
                    : '#c4b5fd',
                }}
              >
                {avgLatency}ms
              </Badge>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          {/* Best practice */}
          <div className="rounded-md p-2.5 text-xs" style={{ backgroundColor: 'rgba(74,222,128,0.08)', border: '1px solid rgba(74,222,128,0.2)', color: '#bbf7d0' }}>
            <p className="font-semibold mb-1.5" style={{ color: '#4ade80' }}>✅ Rekomendasi Terbaik</p>
            <ul className="space-y-1 pl-1">
              <li className="flex items-start gap-1.5">
                <Cable className="size-3 shrink-0 mt-0.5" style={{ color: '#4ade80' }} />
                <span>Gunakan <strong>kabel LAN</strong> untuk semua perangkat (Admin, MC, Operator) — koneksi ke <strong>switch/router yang sama</strong></span>
              </li>
              <li className="flex items-start gap-1.5">
                <Cable className="size-3 shrink-0 mt-0.5" style={{ color: '#4ade80' }} />
                <span>Latency kabel LAN: {'<1ms'} — <strong>hampir tanpa lag</strong></span>
              </li>
            </ul>
          </div>

          {/* WiFi alternative */}
          <div className="rounded-md p-2.5 text-xs" style={{ backgroundColor: 'rgba(251,191,36,0.08)', border: '1px solid rgba(251,191,36,0.2)', color: '#fef3c7' }}>
            <p className="font-semibold mb-1.5" style={{ color: '#fbbf24' }}>⚠️ WiFi — Bisa Digunakan Tapi...</p>
            <ul className="space-y-1 pl-1">
              <li>• Semua perangkat harus terhubung ke <strong>router WiFi yang sama</strong></li>
              <li>• Letakkan perangkat <strong>sedekat mungkin</strong> dengan router</li>
              <li>• Latency WiFi: 5-30ms — <strong>mungkin ada lag sesekali</strong></li>
              <li>• Hindari WiFi public/campus — terlalu banyak interferensi</li>
            </ul>
          </div>

          {/* Current status */}
          {isPoor && (
            <div className="rounded-md p-2.5 text-xs" style={{ backgroundColor: 'rgba(248,113,113,0.08)', border: '1px solid rgba(248,113,113,0.2)', color: '#fecaca' }}>
              <p className="font-semibold mb-1" style={{ color: '#f87171' }}>🔴 Jaringan Anda Saat Ini: {quality.toUpperCase()}</p>
              <p>Pertimbangkan untuk beralih ke kabel LAN untuk koneksi yang lebih stabil.</p>
            </div>
          )}
        </CardContent>
      </Card>
    )
  }

  // ── Render: Photo History Item ───────────────────────────────────
  const renderPhotoItem = (item: PhotoHistoryItem, index: number) => {
    // GUARD: skip corrupted entries (missing student object) instead of
    // crashing the entire gallery render. This can happen if a photoHistory
    // entry got malformed during reset+retake cycles or cross-client sync.
    if (!item || !item.student || !item.student.id) {
      console.warn('[SAATIRIL ADMIN] Skipping corrupted photoHistory item at index', index, item)
      return null
    }
    const { student, channel, photos } = item
    const photoshoot = isPhotoshootMode(mode)

    // Look up the version from captureVersions (synced via SYNC_DB).
    // version 1 = first capture, 2+ = retake after MC reset. This makes the
    // gallery show the versioned filename (e.g. `NIM_Nama_v2.jpg`) matching
    // the actual file on disk.
    const versionKey = `${student.id}_${channel}`
    const version = currentProject?.captureVersions?.[versionKey] ?? 1

    // BUG #7 (audit-electron-side): targetFolder for lazy thumbnail
    // rehydration. When `photos` is empty (after app restart — base64 stripped
    // by saveProjectsToStorage to fit localStorage), we lazy-load the file
    // from disk via the read-photo IPC + LazyThumbnail component.
    const targetFolder = currentProject?.config?.targetFolder ?? ''

    const channelLabel = mode === 'dual' ? (channel === 1 ? 'Kiri' : 'Kanan')
      : mode === 'dual-photoshoot' ? (channel === 1 ? 'Cam 1' : 'Cam 2')
      : 'Ch.1'
    const channelColor = mode === 'dual' ? (channel === 1 ? GOLD : CYAN)
      : mode === 'dual-photoshoot' ? (channel === 1 ? '#4ade80' : CYAN)
      : GOLD

    // Photoshoot: 1 photo with data-based name
    if (photoshoot) {
      const filename = buildPhotoshootFilename(student.nim, student.nama, channel, version, student.id)
      return (
        <div
          key={`${student.id}-${channel}-${index}`}
          className="rounded-lg border border-[#533485]/50 bg-[#1a0b2e]/50 p-3"
        >
          <div className="mb-2 flex items-center gap-2">
            <CheckCircle2 className="size-3.5 text-emerald-400 shrink-0" />
            <span className="truncate text-sm font-medium text-[#c4b5fd]">
              {student.nama}
            </span>
          </div>
          <div className="mb-2 flex items-center gap-1.5">
            <Badge
              className="text-[10px]"
              style={{
                backgroundColor: channel === 1 && mode === 'dual-photoshoot' ? 'rgba(74,222,128,0.15)' : channel === 1 ? 'rgba(212,175,55,0.15)' : 'rgba(6,182,212,0.15)',
                color: channelColor,
                borderColor: channel === 1 && mode === 'dual-photoshoot' ? 'rgba(74,222,128,0.3)' : channel === 1 ? 'rgba(212,175,55,0.3)' : 'rgba(6,182,212,0.3)',
              }}
            >
              {channelLabel}
            </Badge>
            {version > 1 && (
              <Badge className="text-[10px]" style={{ backgroundColor: 'rgba(212,175,55,0.2)', color: GOLD, borderColor: 'rgba(212,175,55,0.4)' }}>
                FOTO ULANG v{version}
              </Badge>
            )}
          </div>
          <div className="flex gap-2">
            <div className="flex flex-1 flex-col gap-1">
              <div className="flex h-20 items-center justify-center overflow-hidden rounded-md bg-[#2a164a]/80 border border-[#533485]/30">
                {photos[0] ? (
                  <img src={photos[0]} alt="Foto" loading="lazy" decoding="async" className="h-full w-full object-cover" />
                ) : (
                  /* BUG #7: lazy-load from disk after restart */
                  <LazyThumbnail filename={filename} targetFolder={targetFolder} alt="Foto" className="h-full w-full object-cover" />
                )}
              </div>
              <span className="truncate text-[10px] text-[#c4b5fd]/60" title={filename}>
                {filename}
              </span>
            </div>
          </div>
        </div>
      )
    }

    // Standard mode: 2 photos (Toga + Ijazah)
    const togaFilename = buildFilename(student.nim, student.nama, 1, 'Toga', version, student.id)
    const ijazahFilename = buildFilename(student.nim, student.nama, 2, 'Ijazah', version, student.id)

    return (
      <div
        key={`${student.id}-${channel}-${index}`}
        className="rounded-lg border border-[#533485]/50 bg-[#1a0b2e]/50 p-3"
      >
        {/* Student name row */}
        <div className="mb-2 flex items-center gap-2">
          <CheckCircle2 className="size-3.5 text-emerald-400 shrink-0" />
          <span className="truncate text-sm font-medium text-[#c4b5fd]">
            {student.nama}
          </span>
        </div>

        {/* Channel badge + version badge */}
        <div className="mb-2 flex items-center gap-1.5">
          <Badge
            className="text-[10px]"
            style={{
              backgroundColor:
                channel === 1 ? 'rgba(212,175,55,0.15)' : 'rgba(6,182,212,0.15)',
              color: channelColor,
              borderColor:
                channel === 1 ? 'rgba(212,175,55,0.3)' : 'rgba(6,182,212,0.3)',
            }}
          >
            {channelLabel}
          </Badge>
          {version > 1 && (
            <Badge className="text-[10px]" style={{ backgroundColor: 'rgba(212,175,55,0.2)', color: GOLD, borderColor: 'rgba(212,175,55,0.4)' }}>
              FOTO ULANG v{version}
            </Badge>
          )}
        </div>

        {/* Photo thumbnails */}
        <div className="flex gap-2">
          {/* Toga */}
          <div className="flex flex-1 flex-col gap-1">
            <div className="flex h-16 items-center justify-center overflow-hidden rounded-md bg-[#2a164a]/80 border border-[#533485]/30">
              {photos[0] ? (
                <img src={photos[0]} alt="Toga" loading="lazy" decoding="async" className="h-full w-full object-cover" />
              ) : (
                /* BUG #7: lazy-load from disk after restart */
                <LazyThumbnail filename={togaFilename} targetFolder={targetFolder} alt="Toga" className="h-full w-full object-cover" />
              )}
            </div>
            <span className="truncate text-[10px] text-[#c4b5fd]/60" title={togaFilename}>
              {togaFilename}
            </span>
          </div>
          {/* Ijazah */}
          <div className="flex flex-1 flex-col gap-1">
            <div className="flex h-16 items-center justify-center overflow-hidden rounded-md bg-[#2a164a]/80 border border-[#533485]/30">
              {photos[1] ? (
                <img src={photos[1]} alt="Ijazah" loading="lazy" decoding="async" className="h-full w-full object-cover" />
              ) : (
                /* BUG #7: lazy-load from disk after restart */
                <LazyThumbnail filename={ijazahFilename} targetFolder={targetFolder} alt="Ijazah" className="h-full w-full object-cover" />
              )}
            </div>
            <span className="truncate text-[10px] text-[#c4b5fd]/60" title={ijazahFilename}>
              {ijazahFilename}
            </span>
          </div>
        </div>
      </div>
    )
  }

  // ── Render: Photo Gallery ────────────────────────────────────────
  const renderPhotoGallery = () => (
    <Card className={`${PANEL} ${BORDER} shadow-lg flex flex-col h-full`}>
      <CardHeader className="pb-2 shrink-0">
        <CardTitle className="flex items-center justify-between">
          <div className="flex items-center gap-2 text-sm font-semibold tracking-wide text-[#c4b5fd]">
            <ImageIcon className="size-4" style={{ color: GOLD }} />
            Log Render & Penyimpanan
          </div>
          <Badge
            className="text-[10px] border-[#533485]/50"
            style={{ backgroundColor: 'rgba(212,175,55,0.15)', color: GOLD }}
          >
            {photoHistory.length} foto{photoHistory.length > 30 ? ' (30 terbaru)' : ''}
          </Badge>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex-1 min-h-0 pt-0 overflow-hidden">
        {photoHistory.length === 0 ? (
          <div className="flex h-full min-h-[200px] flex-col items-center justify-center gap-3 py-8">
            <div className="flex size-16 items-center justify-center rounded-full bg-[#3b2263]/50">
              <Camera className="size-7 text-[#533485]" />
            </div>
            <p className="max-w-[240px] text-center text-sm text-[#c4b5fd]/60">
              Server aktif. Menunggu jepretan dari Operator Kamera...
            </p>
            <div className="flex items-center gap-1.5 text-xs text-[#533485]">
              <Clock className="size-3" />
              <span>Menunggu aktivitas</span>
            </div>
          </div>
        ) : (
          <ScrollArea className="h-full">
            {/* PERF: only render the 30 most-recent items (newest first) instead
                of all 200. Each item has 1-2 <img src="data:...2.5MB"> tags —
                rendering all 200 (= up to 400 images) on every PHOTOS_SAVED
                blocked the admin's main thread for 100s of ms to seconds, which
                delayed the NEXT student's STUDENT_DONE re-emit and caused
                intermittent lag on the browser MC Page. The full 200 stay in
                the store (for SYNC_DB + disk saves) — only the RENDER is capped.
                Disk saves in handlePhotosSaved are unaffected (they use the full
                photo data, not the rendered gallery). */}
            <div className="grid grid-cols-1 gap-3 pr-2 sm:grid-cols-2">
              {photoHistory.slice(-30).reverse().map((item, idx) => renderPhotoItem(item, idx))}
            </div>
          </ScrollArea>
        )}
      </CardContent>
    </Card>
  )

  // ── Main render ──────────────────────────────────────────────────
  return (
    <div className={`${BG} h-full w-full p-2 sm:p-4 md:p-6 overflow-hidden`}>
      <div className="mx-auto flex h-full max-w-7xl flex-col gap-3 sm:gap-4 md:flex-row md:gap-6">
        {/* ── Left Column (1/3 on desktop, full width on mobile) ── */}
        <div className={`flex w-full flex-col gap-3 sm:gap-4 md:w-1/3 shrink-0 overflow-y-auto custom-scroll ${isMobile ? '' : 'max-h-[50vh] md:max-h-none'}`}>
          {renderUsageGuide()}
          {renderDaftarPeserta()}
          {renderGoogleDriveBackup()}
          {renderLanAccess()}
          {renderNetworkTips()}
        </div>

        {/* ── Right Column (2/3 on desktop, full width on mobile) ── */}
        <div className={`w-full md:w-2/3 min-h-0 flex-1 ${isMobile ? '' : 'max-h-[45vh] md:max-h-none'}`}>
          {renderPhotoGallery()}
        </div>
      </div>

      {/* ── QR Code Dialog ─────────────────────────────────────────────────── */}
      <Dialog open={qrDialogOpen} onOpenChange={setQrDialogOpen}>
        <DialogContent className="sm:max-w-md border-[#533485] bg-[#2a164a] text-white">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2 text-white">
              <QrCode className="size-5" style={{ color: GOLD }} />
              Kode QR — {qrLabel}
            </DialogTitle>
            <DialogDescription className="text-[#c4b5fd]">
              Scan kode QR ini dengan perangkat yang akan digunakan untuk bergabung ke sesi.
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col items-center gap-4 py-4">
            {/* QR Code */}
            <div className="rounded-xl bg-white p-4 shadow-lg">
              <QRCodeSVG
                value={qrLink}
                size={220}
                level="M"
                bgColor="#ffffff"
                fgColor="#1a0b2e"
                includeMargin={false}
              />
            </div>
            {/* Link text */}
            <div className="w-full rounded-md bg-[#1a0b2e]/60 border border-[#533485]/50 p-3">
              <p className="break-all text-center text-xs font-mono" style={{ color: '#c4b5fd' }}>
                {qrLink}
              </p>
            </div>
            {/* Copy button */}
            <Button
              className="w-full font-semibold"
              style={{ backgroundColor: GOLD, color: '#1a0b2e' }}
              onClick={async () => {
                try {
                  if (navigator.clipboard) {
                    await navigator.clipboard.writeText(qrLink)
                  } else {
                    const textarea = document.createElement('textarea')
                    textarea.value = qrLink
                    document.body.appendChild(textarea)
                    textarea.select()
                    document.execCommand('copy')
                    document.body.removeChild(textarea)
                  }
                  toast({ title: 'Link disalin!', description: qrLink })
                } catch {
                  toast({ title: 'Gagal menyalin', variant: 'destructive' })
                }
              }}
            >
              <Copy className="mr-2 size-4" />
              Salin Link
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}
