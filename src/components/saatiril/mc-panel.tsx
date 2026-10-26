'use client'

import { useEffect, useMemo, useRef, useCallback, useState } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Megaphone, Users, Clock, CheckCircle2, Loader2, Camera, Search, Send, RotateCcw, ArrowRight, CheckSquare, Square, ListChecks, X } from 'lucide-react'
import { useSaatirilStore, type Student, type StudentStatus, type PhotoHistoryItem, type CameraMode, mergeDatabases, stripFrameForSync, preserveFrameOnSync, preservePhotoHistoryOnSync, mergeCaptureVersions, isPhotoshootMode, isDualPhotoshootMode, channelCount } from '@/store/use-saatiril-store'
import { emitLocal, onLocal, offLocal } from '@/lib/socket'
import { useIsMobile } from '@/hooks/use-mobile'
import { NetworkQualityBadge } from '@/components/saatiril/network-quality-badge'

// ─── Theme tokens ───────────────────────────────────────────────────────────
const THEME = {
  bg: '#1a0b2e',
  panel: '#2a164a',
  card: '#3b2263',
  border: '#533485',
  gold: '#d4af37',
  muted: '#c4b5fd',
  emerald: '#4ade80',
  cyan: '#06b6d4',
} as const

// ─── Helpers ────────────────────────────────────────────────────────────────
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

// ─── Socket event data shapes ───────────────────────────────────────────────
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

interface PhotosSavedData {
  student: Student
  photos: string[]
  channel: number
}

interface OpProgressData {
  channel: number
  status: string
}

// ─── Component ──────────────────────────────────────────────────────────────
export function McPanel({ compact = false, channel }: { compact?: boolean; channel?: number }) {
  const isMobile = useIsMobile()

  const currentProject = useSaatirilStore((s) => s.currentProject)
  // ── Per-instance channel override ──────────────────────────────────────
  // When `channel` is passed (e.g. dual-photo mode renders 2 McPanels side
  // by side — channel 1 left, channel 2 right), this instance uses that
  // channel for ALL its logic (channelStudents, currentlyActive,
  // handleCallNow, handleStudentDone, etc.) instead of the store's
  // myChannel. This lets 2 instances coexist in one screen, each handling
  // its own channel. myChannelRef tracks the override so socket listeners
  // filter by the right channel. The store's myChannel is still used when
  // no override is given (single-channel / default behaviour).
  const storeChannel = useSaatirilStore((s) => s.myChannel)
  const myChannel = channel ?? storeChannel
  const setMyChannel = useSaatirilStore((s) => s.setMyChannel)
  const updateStudentStatus = useSaatirilStore((s) => s.updateStudentStatus)
  const updateCurrentProject = useSaatirilStore((s) => s.updateCurrentProject)
  const saveProjectsToStorageNow = useSaatirilStore((s) => s.saveProjectsToStorageNow)

  const [opProgressText, setOpProgressText] = useState<string>('')
  const [opProgressChannel, setOpProgressChannel] = useState<number>(0)
  // ── Photoshoot mode: search state ─────────────────────────────────────────
  const [searchQuery, setSearchQuery] = useState('')
  const [selesaiSearchQuery, setSelesaiSearchQuery] = useState('')
  const [monitorLocked, setMonitorLocked] = useState(false)
  const [selectedStudent, setSelectedStudent] = useState<Student | null>(null)
  // ── Multi-select for photoshoot mode (single + dual) ─────────────────────────
  // Allows MC to select more than one (or all) pending students and send
  // them to the operator(s) in a single batch. Each selected student is
  // marked 'sent' + emitted as MC_CALL; the operator's existing queue UI
  // (opQueue in operator-panel.tsx) already handles a list of 'sent'
  // students — the operator clicks whichever to photograph next.
  const [selectedStudentIds, setSelectedStudentIds] = useState<string[]>([])
  // ── Mobile/compact: active tab for the 3-section workspace ────────────────
  const [activeTab, setActiveTab] = useState<'antrean' | 'proses' | 'selesai'>('antrean')

  const myChannelRef = useRef(myChannel)
  const currentProjectRef = useRef(currentProject)
  useEffect(() => { myChannelRef.current = myChannel }, [myChannel])
  useEffect(() => { currentProjectRef.current = currentProject }, [currentProject])

  const mode = currentProject?.config.mode ?? 'single'
  const photoshoot = isPhotoshootMode(mode)
  const dualPhotoshoot = isDualPhotoshootMode(mode)

  // ── For non-photoshoot modes: channel-filtered students ──────────────────
  const channelStudents = useMemo<Student[]>(() => {
    if (!currentProject) return []
    if (photoshoot) {
      // In photoshoot modes, all students are in one pool
      return currentProject.database
    }
    return currentProject.database.filter((s) => s.assignedChannel === myChannel)
  }, [currentProject, myChannel, photoshoot])

  // Non-photoshoot: currently active student for our channel
  const currentlyActive = useMemo<Student | null>(() => {
    if (photoshoot) return null // Not used in photoshoot mode
    const targetStatus: StudentStatus = `active_${myChannel}`
    return channelStudents.find((s) => s.status === targetStatus) ?? null
  }, [channelStudents, myChannel, photoshoot])

  const nextPending = useMemo<Student | null>(() => {
    return channelStudents.find((s) => s.status === 'pending') ?? null
  }, [channelStudents])

  const remainingCount = useMemo<number>(() => {
    return channelStudents.filter((s) => s.status === 'pending').length
  }, [channelStudents])

  const isPhotographing = !photoshoot && currentlyActive !== null

  // ── Photoshoot: students sent to operators (kept for backward compatibility) ─
  const sentStudents = useMemo<Student[]>(() => {
    if (!photoshoot) return []
    return channelStudents.filter((s) => s.status === 'sent')
  }, [photoshoot, channelStudents])

  // ── 3-section workspace filters (NEW) — split channelStudents by status ──
  // ANTREAN: pending only ( belum dipanggil / dikirim )
  const antreanStudents = useMemo<Student[]>(() => {
    return channelStudents.filter((s) => s.status === 'pending')
  }, [channelStudents])

  // PROSES: sent OR active_N ( sedang dengan operator / difoto )
  const prosesStudents = useMemo<Student[]>(() => {
    return channelStudents.filter((s) => s.status === 'sent' || isActiveStatus(s.status))
  }, [channelStudents])

  // SELESAI: done ( sudah disimpan )
  const selesaiStudents = useMemo<Student[]>(() => {
    return channelStudents.filter((s) => s.status === 'done')
  }, [channelStudents])

  const totalCount = channelStudents.length

  // ── ANTREAN list filtered by searchQuery (photoshoot free-order selection) ─
  const displayedAntrean = useMemo<Student[]>(() => {
    if (!searchQuery.trim()) return antreanStudents
    const q = searchQuery.toLowerCase().trim()
    return antreanStudents.filter(
      (s) => s.nim.toLowerCase().includes(q) || s.nama.toLowerCase().includes(q),
    )
  }, [antreanStudents, searchQuery])

  // ── SELESAI list filtered by selesaiSearchQuery (car peserta yang sudah disimpan —
  // berguna saat antrean sudah 4000+ peserta selesai dan MC butuh cari untuk reset+resend).
  const displayedSelesai = useMemo<Student[]>(() => {
    if (!selesaiSearchQuery.trim()) return selesaiStudents
    const q = selesaiSearchQuery.toLowerCase().trim()
    return selesaiStudents.filter(
      (s) => s.nim.toLowerCase().includes(q) || s.nama.toLowerCase().includes(q),
    )
  }, [selesaiStudents, selesaiSearchQuery])

  // ── Photoshoot: check per-channel completion from photoHistory ──────────
  const getStudentChannelCompletion = useCallback((studentId: string): Record<number, boolean> => {
    const proj = currentProjectRef.current
    if (!proj) return {}
    const result: Record<number, boolean> = {}
    const chCount = channelCount(proj.config.mode)
    for (let ch = 1; ch <= chCount; ch++) {
      result[ch] = proj.photoHistory.some((h) => h.student.id === studentId && h.channel === ch)
    }
    return result
  }, [])

  const activeRowRef = useRef<HTMLDivElement>(null)
  const nextRowRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const target = activeRowRef.current ?? nextRowRef.current
    if (target) {
      target.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  }, [currentlyActive, nextPending])

  // ── Socket: STUDENT_DONE — lightweight event for immediate MC unblocking
  // In non-photoshoot mode, MC only needs to know the student is done.
  // This event fires BEFORE the heavy PHOTOS_SAVED payload arrives, so MC
  // can immediately call the next student without waiting for photo transfer.
  useEffect(() => {
    const handleStudentDone = (data: { studentId: string; channel: number; _relayed?: boolean }) => {
      if (photoshoot) return // photoshoot mode uses PHOTOS_SAVED for channel completion
      if (data.channel !== myChannelRef.current) return
      console.log('[SAATIRIL MC] STUDENT_DONE received — immediate unblock:', data.studentId, 'Ch.', data.channel)
      updateStudentStatus(data.studentId, 'done')
      setOpProgressText('')
      saveProjectsToStorageNow()
      // ── Re-emit STUDENT_DONE from the admin's side for browser MC clients ──
      // The admin's MC panel (portable.exe) gets STUDENT_DONE via the relay
      // (admin's socket → server is localhost = instant) — that's why the
      // portable's MC unblocks instantly. But a browser MC Page on a SEPARATE
      // device gets STUDENT_DONE from the OPERATOR APP (operator → WiFi →
      // server → WiFi → browser MC = 2 WiFi hops, AND may be batched behind
      // the heavy PHOTOS_SAVED 5MB → stuck for seconds). By re-emitting from
      // the admin (localhost → server → WiFi → browser MC = only 1 WiFi hop,
      // ~50 bytes, instant), the browser MC Page gets unblocked FAST — matching
      // the portable's instant behaviour. The _relayed flag prevents infinite
      // loops (a re-emitted event won't re-emit again).
      if (!data._relayed) {
        emitLocal('STUDENT_DONE', { ...data, _relayed: true })
      }
    }

    onLocal('STUDENT_DONE', handleStudentDone)
    return () => { offLocal('STUDENT_DONE', handleStudentDone) }
  }, [updateStudentStatus, saveProjectsToStorageNow, photoshoot])

  // ── Socket: SYNC_DB
  useEffect(() => {
    const handleSyncDb = (data: SyncDbData) => {
      if (!data.project) return
      const proj = data.project
      // Read latest state synchronously (avoids stale currentProjectRef race).
      // CRITICAL: the MC receives its OWN SYNC_DB echo from the socket server.
      // If we used currentProjectRef.current (updated only after render), the
      // echo would see the PRE-reset photoHistory and preservePhotoHistoryOnSync
      // would RE-ADD entries that STUDENT_RESET just cleared — defeating the reset.
      const curProj = useSaatirilStore.getState().currentProject
      if (!curProj) {
        // No existing project — accept incoming directly (first connect)
        updateCurrentProject(proj)
        console.log('[SAATIRIL MC] SYNC_DB: accepted new project (no existing):', proj.name)
        return
      }
      if (proj.id === curProj.id) {
        const mergedDb = mergeDatabases(curProj.database, proj.database)
        const mergedConfig = preserveFrameOnSync(proj.config, curProj.config)
        const mergedPhotoHistory = preservePhotoHistoryOnSync(
          proj.photoHistory ?? [],
          curProj.photoHistory,
        )
        const mergedVersions = mergeCaptureVersions(
          curProj.captureVersions,
          (proj as any).captureVersions,
        )
        updateCurrentProject({
          ...curProj,
          database: mergedDb,
          photoHistory: mergedPhotoHistory,
          config: mergedConfig,
          captureVersions: mergedVersions,
        })
      } else {
        // Different project ID — REPLACE entirely (admin switched projects)
        updateCurrentProject(proj)
        console.log('[SAATIRIL MC] SYNC_DB: replaced project (different ID):', proj.name)
      }
    }

    onLocal('SYNC_DB', handleSyncDb)
    return () => { offLocal('SYNC_DB', handleSyncDb) }
  }, [updateCurrentProject])

  // ── Socket: PHOTOS_SAVED
  // In non-photoshoot mode: STUDENT_DONE already unblocked MC, this is a no-op.
  // In photoshoot mode: adds photo to history and checks per-channel completion.
  useEffect(() => {
    const handlePhotosSaved = (data: PhotosSavedData) => {
      console.log('[SAATIRIL MC] PHOTOS_SAVED received:', data.student?.nama, 'channel:', data.channel)

      // Non-photoshoot: already handled by STUDENT_DONE event — skip here
      if (!photoshoot) return

      // For photoshoot: add to photoHistory and check per-channel completion
      const curProj = currentProjectRef.current
      if (!curProj) return

      const historyItem: PhotoHistoryItem = {
        student: data.student,
        photos: data.photos,
        channel: data.channel,
      }

      const existing = curProj.photoHistory.findIndex(
        (h) => h.student.id === data.student.id && h.channel === data.channel,
      )
      let newHistory: PhotoHistoryItem[]
      if (existing !== -1) {
        newHistory = [...curProj.photoHistory]
        newHistory[existing] = historyItem
      } else {
        newHistory = [...curProj.photoHistory, historyItem]
      }

      // Check completion: in dual-photoshoot mode, BOTH cameras must photograph
      // (the participant is done only after channel 1 AND channel 2 each have
      // a photoHistory entry with photos). In single-photoshoot mode, the
      // single channel is sufficient.
      // (matches Android AdminViewModel.handlePhotosSaved after fix in d07fdeb —
      // the old `||` marked done after either channel, causing the second
      // channel operator to skip the student and miss the second photo.)
      let allChannelsDone = true
      if (isDualPhotoshootMode(curProj.config.mode)) {
        const ch1Done = newHistory.some((h) => h.student.id === data.student.id && h.channel === 1 && h.photos.length > 0)
        const ch2Done = newHistory.some((h) => h.student.id === data.student.id && h.channel === 2 && h.photos.length > 0)
        allChannelsDone = ch1Done && ch2Done
      } else {
        // Single-photoshoot: one channel is enough
        allChannelsDone = true
      }

      const updatedProject = {
        ...curProj,
        database: curProj.database.map((s) =>
          s.id === data.student.id && allChannelsDone ? { ...s, status: 'done' as StudentStatus } : s
        ),
        photoHistory: newHistory,
      }
      updateCurrentProject(updatedProject)
      saveProjectsToStorageNow()

      console.log('[SAATIRIL MC] PHOTOS_SAVED: allChannelsDone =', allChannelsDone, 'for', data.student.nama)
    }

    onLocal('PHOTOS_SAVED', handlePhotosSaved)
    return () => { offLocal('PHOTOS_SAVED', handlePhotosSaved) }
  }, [updateStudentStatus, updateCurrentProject, saveProjectsToStorageNow, photoshoot])

  // ── Socket: OP_PROGRESS
  useEffect(() => {
    const handleOpProgress = (data: OpProgressData) => {
      if (!photoshoot && data.channel !== myChannelRef.current) return
      console.log('[SAATIRIL MC] OP_PROGRESS:', data.status, 'channel:', data.channel)
      setOpProgressText(data.status)
      setOpProgressChannel(data.channel)
    }

    onLocal('OP_PROGRESS', handleOpProgress)
    return () => { offLocal('OP_PROGRESS', handleOpProgress) }
  }, [photoshoot])

  // ── Socket: MC_CALL
  useEffect(() => {
    const handleMcCall = (data: { student: Student; channel: number }) => {
      if (!photoshoot && data.channel !== myChannelRef.current) return
      // v11-rebuild: Use data.student.status when the sender has set it to a
      // non-pending value (matches the new mc.html which emits student objects
      // with the new status already applied). Fall back to active_<channel>
      // for older clients that send students with stale 'pending' status.
      const status = (data.student.status && data.student.status !== 'pending')
        ? data.student.status
        : `active_${data.channel}` as StudentStatus
      updateStudentStatus(data.student.id, status as StudentStatus)
    }

    onLocal('MC_CALL', handleMcCall)
    return () => { offLocal('MC_CALL', handleMcCall) }
  }, [updateStudentStatus, photoshoot])

  // ── Call action (non-photoshoot: sequential call)
  const handleCallNow = useCallback(() => {
    if (!nextPending || !currentProject) return

    const newStatus: StudentStatus = `active_${myChannel}`
    updateStudentStatus(nextPending.id, newStatus)
    saveProjectsToStorageNow()

    const latestProject = useSaatirilStore.getState().currentProject
    if (!latestProject) return

    // Imitate Saatiril-Fullset: simple map — only update the called student's
    // status to active_N. No stale-reset scan (the O(n × photoHistory) scan on
    // every call was the wisuda-specific slowdown — photoshoot uses
    // handleSendToOperator so it was unaffected, which is why only wisuda felt
    // slow). In normal flow STUDENT_DONE marks the previous student 'done'
    // before the MC calls the next, so there are no stale active_N to reset.
    const updatedProject = {
      ...latestProject,
      database: latestProject.database.map((s) =>
        s.id === nextPending.id ? { ...s, status: newStatus } : s
      ),
    }
    updateCurrentProject(updatedProject)
    setOpProgressText('')

    // PRIORITY: MC_CALL first (lightweight, operator gets student immediately)
    emitLocal('MC_CALL', {
      student: { ...nextPending, status: newStatus },
      channel: myChannel,
    })
    // Then SYNC_DB for consistency (now lightweight — photos stripped)
    emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })
  }, [
    nextPending,
    currentProject,
    myChannel,
    updateStudentStatus,
    updateCurrentProject,
    saveProjectsToStorageNow,
  ])

  // ── Photoshoot: send selected student to operator(s)
  const handleSendToOperator = useCallback(() => {
    if (!selectedStudent || !currentProject) return

    if (dualPhotoshoot) {
      // Send to BOTH channels
      const newStatus: StudentStatus = 'sent'

      const latestProject = useSaatirilStore.getState().currentProject
      if (!latestProject) return

      // Update student status to 'sent' in database
      const updatedProject = {
        ...latestProject,
        database: latestProject.database.map((s) =>
          s.id === selectedStudent.id ? { ...s, status: newStatus } : s
        ),
      }

      updateStudentStatus(selectedStudent.id, newStatus)
      updateCurrentProject(updatedProject)
      saveProjectsToStorageNow()

      // Send MC_CALL to both channels FIRST (operators get student immediately)
      emitLocal('MC_CALL', {
        student: { ...selectedStudent, status: newStatus, assignedChannel: 1 },
        channel: 1,
      })
      emitLocal('MC_CALL', {
        student: { ...selectedStudent, status: newStatus, assignedChannel: 2 },
        channel: 2,
      })
      // Then SYNC_DB for consistency (now lightweight — photos stripped)
      emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })
    } else {
      // Single photoshoot: send to channel 1
      const newStatus: StudentStatus = 'sent'

      const latestProject = useSaatirilStore.getState().currentProject
      if (!latestProject) return

      const updatedProject = {
        ...latestProject,
        database: latestProject.database.map((s) =>
          s.id === selectedStudent.id ? { ...s, status: newStatus } : s
        ),
      }

      updateStudentStatus(selectedStudent.id, newStatus)
      updateCurrentProject(updatedProject)
      saveProjectsToStorageNow()

      emitLocal('MC_CALL', {
        student: { ...selectedStudent, status: newStatus },
        channel: 1,
      })
      emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })
    }

    setSearchQuery('')
    setSelectedStudent(null)
  }, [selectedStudent, currentProject, dualPhotoshoot, updateStudentStatus, updateCurrentProject, saveProjectsToStorageNow])

  // ── Photoshoot: multi-select helpers ─────────────────────────────────────
  // Toggle a student in/out of the multi-select set (preserves order so the
  // batch send emits MC_CALLs in selection order, matching operator queue).
  const toggleStudentSelection = useCallback((id: string) => {
    setSelectedStudentIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]
    )
  }, [])

  // Select all currently-displayed pending students (respects the search
  // filter — if MC filtered to e.g. "toga", only those get selected).
  const selectAllDisplayed = useCallback(() => {
    setSelectedStudentIds((prev) => {
      const seen = new Set(prev)
      // Append displayed IDs that aren't already selected (so calling this
      // multiple times with different search filters accumulates the
      // selection rather than replacing it).
      const merged = [...prev]
      for (const s of displayedAntrean) {
        if (!seen.has(s.id)) {
          merged.push(s.id)
          seen.add(s.id)
        }
      }
      return merged
    })
  }, [displayedAntrean])

  // Deselect all currently-displayed (respects search filter — only removes
  // the ones currently visible so MC can prune part of a big selection).
  const deselectAllDisplayed = useCallback(() => {
    setSelectedStudentIds((prev) => {
      const displayedIds = new Set(displayedAntrean.map((s) => s.id))
      return prev.filter((id) => !displayedIds.has(id))
    })
  }, [displayedAntrean])

  const clearSelection = useCallback(() => {
    setSelectedStudentIds([])
  }, [])

  // Resolve the selected IDs to actual Student objects (filter out any that
  // are no longer 'pending' — e.g. already sent in a previous batch).
  const selectedStudents = useMemo<Student[]>(() => {
    if (!currentProject) return []
    const db = currentProject.database
    const result: Student[] = []
    for (const id of selectedStudentIds) {
      const s = db.find((x) => x.id === id)
      if (s && s.status === 'pending') result.push(s)
    }
    return result
  }, [selectedStudentIds, currentProject])

  // ── Photoshoot: send ALL selected students to operator(s) in one batch ──
  // For each selected student: mark 'sent' + emit MC_CALL (to both channels
  // in dual mode, to channel 1 only in single mode). The operator's existing
  // opQueue UI (operator-panel.tsx) lists every 'sent' student — the operator
  // clicks whichever to photograph next. SYNC_DB is emitted ONCE at the end
  // (lightweight — photos are stripped) instead of N times.
  const handleSendBatchToOperator = useCallback(() => {
    if (selectedStudents.length === 0 || !currentProject) return

    const newStatus: StudentStatus = 'sent'
    const selectedIdSet = new Set(selectedStudents.map((s) => s.id))

    const latestProject = useSaatirilStore.getState().currentProject
    if (!latestProject) return

    // Single pass: mark all selected as 'sent' in the project DB.
    const updatedProject = {
      ...latestProject,
      database: latestProject.database.map((s) =>
        selectedIdSet.has(s.id) ? { ...s, status: newStatus } : s
      ),
    }

    selectedStudents.forEach((s) => updateStudentStatus(s.id, newStatus))
    updateCurrentProject(updatedProject)
    saveProjectsToStorageNow()

    // Emit MC_CALL for each selected student. Order matters in dual mode
    // (both channels must see the same student before the next).
    if (dualPhotoshoot) {
      for (const student of selectedStudents) {
        emitLocal('MC_CALL', {
          student: { ...student, status: newStatus, assignedChannel: 1 },
          channel: 1,
        })
        emitLocal('MC_CALL', {
          student: { ...student, status: newStatus, assignedChannel: 2 },
          channel: 2,
        })
      }
    } else {
      // Single photoshoot: channel 1 only
      for (const student of selectedStudents) {
        emitLocal('MC_CALL', {
          student: { ...student, status: newStatus },
          channel: 1,
        })
      }
    }

    // Lightweight SYNC_DB once at the end (photos stripped — see
    // use-saatiril-store.ts stripFrameForSync).
    emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })

    setSearchQuery('')
    setSelectedStudentIds([])
  }, [selectedStudents, currentProject, dualPhotoshoot, updateStudentStatus, updateCurrentProject, saveProjectsToStorageNow])

  // ── Photoshoot: reset (for retake)
  // Clears the student's photoHistory + resets status to 'pending' + emits a
  // dedicated STUDENT_RESET event so operators (and admin) explicitly clear
  // their buffer / active target / photoHistory. This is necessary because
  // the normal SYNC_DB merge (mergeDatabases) BLOCKS status regression
  // (pending priority 0 < sent/done), and preservePhotoHistoryOnSync does
  // not propagate photoHistory deletions — so a reset via SYNC_DB alone would
  // be silently dropped on the receiver side.
  const handleResetForRetake = useCallback((student: Student) => {
    const latestProject = useSaatirilStore.getState().currentProject
    if (!latestProject) return

    // Remove ALL photoHistory entries for this student (every channel) so the
    // operator queue (which filters via `alreadyPhotographed`) will re-show
    // the student after re-send.
    const cleanedPhotoHistory = latestProject.photoHistory.filter(
      (h) => h.student.id !== student.id,
    )

    const updatedProject: typeof latestProject = {
      ...latestProject,
      database: latestProject.database.map((s) =>
        s.id === student.id ? { ...s, status: 'pending' as StudentStatus } : s,
      ),
      photoHistory: cleanedPhotoHistory,
    }

    updateStudentStatus(student.id, 'pending')
    updateCurrentProject(updatedProject)
    saveProjectsToStorageNow()

    // Emit STUDENT_RESET to every relevant channel — this bypasses the merge
    // priority logic and tells each operator to: clear mcCallBuffer entry,
    // clear opCurrentTarget if it matches, remove their photoHistory entry,
    // and set the student status to 'pending' locally.
    const channels = dualPhotoshoot ? [1, 2] : [1]
    for (const ch of channels) {
      emitLocal('STUDENT_RESET', { studentId: student.id, channel: ch })
    }
    // Also emit SYNC_DB for consistency (photoHistory + status now cleaned)
    emitLocal('SYNC_DB', { project: stripFrameForSync(updatedProject) })

    // Pre-select the student for easy re-send
    setSelectedStudent({ ...student, status: 'pending' })
    setSearchQuery(student.nama)
    // Switch to ANTREAN tab so MC immediately sees the reset student at the
    // top of the pending list and can re-send.
    setActiveTab('antrean')
  }, [dualPhotoshoot, updateStudentStatus, updateCurrentProject, saveProjectsToStorageNow])

  // ── Render helpers
  const renderCallButton = () => {
    if (photoshoot) {
      // Photoshoot mode: always allow sending — NO BLOCKING.
      // If the selected student is already 'done' (photographed), show a
      // RESET & KIRIM ULANG button instead — this clears their photoHistory
      // and resets status to 'pending' so the operator can retake.
      if (selectedStudent && selectedStudent.status === 'done') {
        return (
          <Button
            onClick={() => handleResetForRetake(selectedStudent)}
            className={`w-full font-bold cursor-pointer transition-all duration-200 active:scale-[0.98] ${isMobile ? 'h-14 text-base' : compact ? 'h-9 text-xs hover:scale-[1.02]' : 'h-14 text-lg hover:scale-[1.02]'}`}
            style={{
              backgroundColor: THEME.gold,
              color: THEME.bg,
              border: `2px solid ${THEME.gold}`,
              boxShadow: `0 0 20px ${THEME.gold}44`,
            }}
          >
            <RotateCcw className={compact ? 'size-3' : 'size-4'} />
            RESET & KIRIM ULANG
          </Button>
        )
      }
      return (
        <Button
          disabled={selectedStudents.length === 0}
          onClick={handleSendBatchToOperator}
          className={`w-full font-bold cursor-pointer transition-all duration-200 active:scale-[0.98] ${isMobile ? 'h-14 text-base' : compact ? 'h-9 text-xs hover:scale-[1.02]' : 'h-14 text-lg hover:scale-[1.02]'}`}
          style={{
            backgroundColor: selectedStudents.length > 0 ? THEME.emerald : THEME.panel,
            color: selectedStudents.length > 0 ? THEME.bg : THEME.muted,
            border: `2px solid ${selectedStudents.length > 0 ? THEME.emerald : THEME.border}`,
            boxShadow: selectedStudents.length > 0 ? `0 0 20px ${THEME.emerald}44` : 'none',
          }}
        >
          <Send className={compact ? 'size-3' : 'size-4'} />
          {selectedStudents.length > 0
            ? (dualPhotoshoot ? `KIRIM ${selectedStudents.length} KE 2 KAMERA` : `KIRIM ${selectedStudents.length} KE OPERATOR`)
            : (dualPhotoshoot ? 'PILIH PESERTA DULU' : 'PILIH PESERTA DULU')}
        </Button>
      )
    }

    // Non-photoshoot: existing blocking flow
    if (isPhotographing) {
      return (
        <div className="space-y-2">
          <Button
            disabled
            className={`w-full font-bold cursor-not-allowed ${isMobile ? 'h-12 text-sm' : compact ? 'h-9 text-xs' : 'h-14 text-lg'}`}
            style={{
              backgroundColor: THEME.panel,
              color: THEME.muted,
              border: `2px solid ${THEME.border}`,
            }}
          >
            <Loader2 className={compact ? 'size-3 animate-spin' : 'size-4 animate-spin'} />
            {opProgressText || 'TUNGGU KAMERA...'}
          </Button>
        </div>
      )
    }

    if (nextPending) {
      return (
        <Button
          onClick={handleCallNow}
          className={`w-full font-bold cursor-pointer transition-all duration-200 active:scale-[0.98] ${isMobile ? 'h-14 text-base' : compact ? 'h-9 text-xs hover:scale-[1.02]' : 'h-14 text-lg hover:scale-[1.02]'}`}
          style={{
            backgroundColor: THEME.gold,
            color: THEME.bg,
            border: `2px solid ${THEME.gold}`,
            boxShadow: `0 0 20px ${THEME.gold}44`,
          }}
        >
          <Megaphone className={compact ? 'size-3' : 'size-5'} />
          PANGGIL SEKARANG
        </Button>
      )
    }

    return (
      <Button
        disabled
        className={`w-full font-bold cursor-not-allowed ${isMobile ? 'h-12 text-sm' : compact ? 'h-9 text-xs' : 'h-14 text-lg'}`}
        style={{
          backgroundColor: THEME.panel,
          color: THEME.muted,
          border: `2px solid ${THEME.border}`,
          opacity: 0.6,
        }}
      >
        <Users className={compact ? 'size-3' : 'size-5'} />
        ANTREAN HABIS
      </Button>
    )
  }

  const getRowStyle = (student: Student): React.CSSProperties => {
    const isActive = isActiveStatus(student.status)
    const isNext = !photoshoot && student.id === nextPending?.id && student.status === 'pending'
    const isDone = student.status === 'done'
    const isSent = student.status === 'sent'
    const isSelected = photoshoot && selectedStudent?.id === student.id

    if (isActive) {
      // Active participant being photographed — MOST prominent so the operator
      // can compare this name with the one MC sent. Full gold bg + thick border
      // + strong glow + bold text.
      return {
        backgroundColor: `${THEME.gold}33`,
        borderLeft: `6px solid ${THEME.gold}`,
        boxShadow: `0 0 20px ${THEME.gold}66`,
        fontWeight: 700,
      }
    }

    if (isSelected) {
      return {
        backgroundColor: `${THEME.emerald}22`,
        borderLeft: `4px solid ${THEME.emerald}`,
        boxShadow: `0 0 12px ${THEME.emerald}44`,
      }
    }

    if (isSent) {
      return {
        backgroundColor: `${THEME.cyan}11`,
        borderLeft: `4px solid ${THEME.cyan}`,
      }
    }

    if (isNext) {
      // Top pending participant — BUTTON-like so MC easily reads who to call
      // next + presses PANGGIL SEKARANG. Prominent gold border + subtle glow
      // + semi-bold text. Distinct from isActive (which is even more prominent).
      return {
        backgroundColor: `${THEME.gold}1a`,
        borderLeft: `5px solid ${THEME.gold}`,
        boxShadow: `0 0 14px ${THEME.gold}44`,
        fontWeight: 600,
      }
    }

    if (isDone) {
      return {
        backgroundColor: '#22c55e0d',
        opacity: 0.55,
        borderLeft: `4px solid #22c55e66`,
      }
    }

    return {
      backgroundColor: THEME.panel,
      borderLeft: `4px solid ${THEME.border}`,
    }
  }

  const renderStatusBadge = (status: StudentStatus) => {
    if (status === 'done') {
      return (
        <Badge
          className={`px-1 py-0 ${compact ? 'text-[8px]' : 'text-[10px]'}`}
          style={{ backgroundColor: '#22c55e33', color: '#4ade80', border: '1px solid #22c55e55' }}
        >
          <CheckCircle2 className="size-3 mr-0.5" />
          {compact ? '✓' : 'Selesai'}
        </Badge>
      )
    }

    if (status === 'sent') {
      return (
        <Badge
          className={`px-1 py-0 ${compact ? 'text-[8px]' : 'text-[10px]'}`}
          style={{ backgroundColor: `${THEME.cyan}33`, color: THEME.cyan, border: `1px solid ${THEME.cyan}66` }}
        >
          <Camera className="size-3 mr-0.5" />
          {compact ? '→' : 'Dikirim'}
        </Badge>
      )
    }

    if (isActiveStatus(status)) {
      return (
        <Badge
          className={`px-1 py-0 animate-pulse ${compact ? 'text-[8px]' : 'text-[10px]'}`}
          style={{
            backgroundColor: `${THEME.gold}33`,
            color: THEME.gold,
            border: `1px solid ${THEME.gold}66`,
          }}
        >
          <Camera className="size-3 mr-0.5" />
          {compact ? statusLabel(status).replace('Foto ', '') : statusLabel(status)}
        </Badge>
      )
    }

    return (
      <Badge
        className={`px-1 py-0 ${compact ? 'text-[8px]' : 'text-[10px]'}`}
        style={{
          backgroundColor: `${THEME.border}44`,
          color: THEME.muted,
          border: `1px solid ${THEME.border}`,
        }}
      >
        <Clock className="size-3 mr-0.5" />
        Menunggu
      </Badge>
    )
  }

  // ── Minimalist status dot (for compact queue list) ─────────────────────
  const renderStatusDot = (status: StudentStatus) => {
    if (status === 'done') return <span className="size-2 rounded-full shrink-0" style={{ backgroundColor: '#4ade80', boxShadow: '0 0 4px #4ade8066' }} title="Selesai" />
    if (status === 'sent') return <span className="size-2 rounded-full shrink-0" style={{ backgroundColor: THEME.cyan, boxShadow: `0 0 4px ${THEME.cyan}66` }} title="Dikirim" />
    if (isActiveStatus(status)) return <span className="size-2 rounded-full shrink-0 animate-pulse" style={{ backgroundColor: THEME.gold, boxShadow: `0 0 6px ${THEME.gold}88` }} title={statusLabel(status)} />
    return <span className="size-2 rounded-full shrink-0" style={{ backgroundColor: THEME.border }} title="Menunggu" />
  }

  // ── NEW: Top progress bar (sticky, full-width) — "informasi proses progres"
  // 4 stat pills: ANTREAN / PROSES / SELESAI / TOTAL + mode indicator + network badge.
  // On condensed (compact/mobile), pills double as tabs to switch active column.
  const renderTopBar = (opts: { condensed: boolean }) => {
    const { condensed } = opts
    const pills: Array<{
      label: string
      count: number
      color: string
      bg: string
      tab: 'antrean' | 'proses' | 'selesai' | null
    }> = [
      { label: 'ANTREAN', count: antreanStudents.length, color: THEME.muted, bg: `${THEME.border}33`, tab: 'antrean' },
      { label: 'PROSES', count: prosesStudents.length, color: THEME.cyan, bg: `${THEME.cyan}22`, tab: 'proses' },
      { label: 'SELESAI', count: selesaiStudents.length, color: THEME.emerald, bg: `${THEME.emerald}22`, tab: 'selesai' },
      { label: 'TOTAL', count: totalCount, color: THEME.gold, bg: `${THEME.gold}22`, tab: null },
    ]
    return (
      <div
        className={`shrink-0 flex items-center justify-between gap-2 ${condensed ? 'px-2 py-1.5' : 'px-3 py-2'}`}
        style={{
          backgroundColor: THEME.panel,
          borderBottom: `1px solid ${THEME.border}`,
        }}
      >
        <div className="flex items-center gap-1.5 min-w-0 flex-1 overflow-x-auto">
          {pills.map((p) => {
            const active = p.tab !== null && p.tab === activeTab
            const dimmed = condensed && p.tab !== null && !active
            return (
              <button
                key={p.label}
                type="button"
                disabled={p.tab === null}
                onClick={() => { if (p.tab) setActiveTab(p.tab) }}
                className={`flex flex-col items-center justify-center rounded-md transition-all shrink-0
                  ${condensed ? 'px-2 py-0.5 min-w-[48px]' : 'px-3 py-1 min-w-[72px]'}
                  ${p.tab === null ? 'cursor-default' : 'cursor-pointer hover:brightness-125'}`}
                style={{
                  backgroundColor: p.bg,
                  border: `1px solid ${p.color}44`,
                  boxShadow: active ? `0 0 10px ${p.color}66` : 'none',
                  opacity: dimmed ? 0.5 : 1,
                }}
                aria-pressed={active}
              >
                <span className={`font-bold leading-none ${condensed ? 'text-sm' : 'text-xl'}`} style={{ color: p.color }}>
                  {p.count}
                </span>
                <span className={`uppercase tracking-wider font-semibold leading-tight ${condensed ? 'text-[7px]' : 'text-[9px]'}`} style={{ color: p.color }}>
                  {p.label}
                </span>
              </button>
            )
          })}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {photoshoot ? (
            <span className={`hidden sm:inline ${condensed ? 'text-[9px]' : 'text-xs'}`} style={{ color: THEME.muted }}>
              {dualPhotoshoot ? '2 Kamera' : 'Photoshoot'}
            </span>
          ) : !channel && channelCount(mode) > 1 ? (
            /* ── Jalur dropdown (single-panel layout, dual wisuda mode) ──
               When this McPanel is rendered ONCE (no `channel` prop override)
               but the project is dual-wisuda (2 channels), let the user pick
               which Jalur to view via this dropdown. Calls setMyChannel on the
               store so the whole panel switches channel. In the admin's dual
               50/50 layout, each McPanel has channel={1}/{2} fixed → this
               branch is skipped (the fixed label below shows instead). */
            <select
              value={storeChannel}
              onChange={(e) => setMyChannel(Number(e.target.value))}
              className={`cursor-pointer rounded-md ${condensed ? 'text-[9px] px-1 py-0.5' : 'text-xs px-2 py-1'}`}
              style={{
                backgroundColor: `${THEME.border}33`,
                color: THEME.gold,
                border: `1px solid ${THEME.border}`,
              }}
              title="Pilih Jalur (channel)"
            >
              {Array.from({ length: channelCount(mode) }, (_, i) => i + 1).map((ch) => (
                <option key={ch} value={ch}>Jalur {ch}</option>
              ))}
            </select>
          ) : (
            <span className={`hidden sm:inline ${condensed ? 'text-[9px]' : 'text-xs'}`} style={{ color: THEME.muted }}>
              {channel ? `Jalur ${channel}` : `Channel ${myChannel}`}
            </span>
          )}
          <NetworkQualityBadge />
          {typeof window !== 'undefined' && (window as any).saatirilAPI?.backToLogin && (
            <button
              onClick={() => (window as any).saatirilAPI.backToLogin()}
              className={`flex items-center gap-1 rounded-md cursor-pointer transition-all hover:brightness-125 ${condensed ? 'px-1.5 py-0.5 text-[8px]' : 'px-2 py-1 text-[10px]'}`}
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
      </div>
    )
  }

  // ── NEW: ANTREAN column (status === 'pending') ──────────────────────────
  // - Photoshoot: click a row → setSelectedStudent + setSearchQuery (free order).
  // - Wisuda: nextPending auto-highlighted (no click). Footer: PANGGIL/TUNGGU.
  const renderAntreanColumn = (opts: { isCompact: boolean }) => {
    const { isCompact } = opts
    return (
      <Card
        className="flex flex-col min-h-0 border rounded-xl overflow-hidden min-w-0 h-full"
        style={{
          backgroundColor: THEME.card,
          borderColor: THEME.border,
          flex: 4,
        }}
      >
        {/* Header */}
        <div
          className={`shrink-0 flex items-center justify-between gap-2 ${isCompact ? 'px-2 py-1.5' : 'px-3 py-2'}`}
          style={{ borderBottom: `1px solid ${THEME.border}` }}
        >
          <div className="flex items-center gap-2 min-w-0">
            <h3 className={`font-semibold uppercase tracking-wider truncate ${isCompact ? 'text-[9px]' : 'text-xs'}`} style={{ color: THEME.gold }}>
              Antrean
            </h3>
            <span
              className={`font-bold rounded-full shrink-0 ${isCompact ? 'text-[9px] px-1.5 py-0' : 'text-xs px-2 py-0.5'}`}
              style={{
                backgroundColor: `${THEME.gold}33`,
                color: THEME.gold,
                border: `1px solid ${THEME.gold}55`,
              }}
            >
              {antreanStudents.length}
            </span>
          </div>
          <span className={`truncate shrink-0 ${isCompact ? 'text-[8px]' : 'text-[10px]'}`} style={{ color: THEME.muted }}>
            Belum dipanggil
          </span>
        </div>

        {/* Search (photoshoot only — free-order selection) */}
        {photoshoot && (
          <div
            className={`shrink-0 relative ${isCompact ? 'px-2 py-1.5' : 'px-3 py-2'}`}
            style={{ borderBottom: `1px solid ${THEME.border}`, backgroundColor: THEME.panel }}
          >
            <Search className={`absolute top-1/2 -translate-y-1/2 ${isCompact ? 'left-3.5 size-3' : 'left-4 size-3.5'}`} style={{ color: THEME.muted }} />
            <Input
              placeholder="Cari NIM atau Nama..."
              value={searchQuery}
              onChange={(e) => {
                setSearchQuery(e.target.value)
                setSelectedStudent(null)
              }}
              className={`${isCompact ? 'pl-7 h-7 text-[10px]' : 'pl-8 h-9 text-xs'} border-[#533485] bg-[#3b2263] text-white placeholder:text-[#533485] focus-visible:border-[#4ade80] focus-visible:ring-[#4ade80]/30`}
            />
          </div>
        )}

        {/* Multi-select toolbar (photoshoot only) — Pilih Semua / Kosongkan / counter */}
        {photoshoot && (
          <div
            className={`shrink-0 flex items-center justify-between gap-2 ${isCompact ? 'px-2 py-1' : 'px-3 py-1.5'}`}
            style={{ borderBottom: `1px solid ${THEME.border}`, backgroundColor: THEME.panel }}
          >
            <div className="flex items-center gap-1.5 min-w-0">
              <button
                type="button"
                onClick={selectAllDisplayed}
                disabled={displayedAntrean.length === 0}
                className={`flex items-center gap-1 rounded font-medium transition-colors hover:bg-white/5 disabled:opacity-50 disabled:cursor-not-allowed ${isCompact ? 'text-[9px] px-1.5 py-0.5' : 'text-[10px] px-2 py-1'}`}
                style={{ color: THEME.emerald, border: `1px solid ${THEME.emerald}44` }}
              >
                <ListChecks className={isCompact ? 'size-2.5' : 'size-3'} />
                Pilih Semua
              </button>
              <button
                type="button"
                onClick={deselectAllDisplayed}
                disabled={selectedStudents.length === 0}
                className={`flex items-center gap-1 rounded font-medium transition-colors hover:bg-white/5 disabled:opacity-50 disabled:cursor-not-allowed ${isCompact ? 'text-[9px] px-1.5 py-0.5' : 'text-[10px] px-2 py-1'}`}
                style={{ color: THEME.muted, border: `1px solid ${THEME.border}` }}
              >
                <X className={isCompact ? 'size-2.5' : 'size-3'} />
                Kosongkan
              </button>
            </div>
            <span className={`font-bold shrink-0 ${isCompact ? 'text-[9px]' : 'text-[10px]'}`} style={{ color: selectedStudents.length > 0 ? THEME.emerald : THEME.muted }}>
              {selectedStudents.length} terpilih
            </span>
          </div>
        )}

        {/* List */}
        <ScrollArea className="flex-1 min-h-0">
          <div className="flex flex-col">
            {displayedAntrean.length === 0 ? (
              <div className={`flex items-center justify-center ${isCompact ? 'py-6' : 'py-12'}`}>
                <p className={`${isCompact ? 'text-[10px]' : 'text-sm'}`} style={{ color: THEME.muted }}>
                  {antreanStudents.length === 0 ? 'Antrean kosong' : 'Tidak ditemukan'}
                </p>
              </div>
            ) : (
              displayedAntrean.map((student, idx) => {
                const isNext = !photoshoot && student.id === nextPending?.id
                const isSelected = photoshoot && selectedStudentIds.includes(student.id)
                return (
                  <div
                    key={student.id}
                    ref={isNext ? nextRowRef : undefined}
                    className={`flex items-center gap-2 transition-colors duration-200 min-w-0
                      ${photoshoot ? 'cursor-pointer' : ''}
                      ${isCompact ? 'px-2 py-1.5' : 'px-3 py-2'}`}
                    style={getRowStyle(student)}
                    onClick={() => {
                      if (photoshoot) {
                        toggleStudentSelection(student.id)
                      }
                    }}
                  >
                    <span className={`font-mono shrink-0 ${isCompact ? 'text-[9px] w-3' : 'text-[10px] w-5'}`} style={{ color: THEME.muted }}>
                      {idx + 1}
                    </span>
                    <span className={`font-mono truncate shrink-0 ${isCompact ? 'text-[9px] w-12' : 'text-[10px] w-16'}`} style={{ color: THEME.muted }}>
                      {student.nim}
                    </span>
                    <span
                      className={`font-medium truncate flex-1 min-w-0 ${isCompact ? 'text-[10px]' : 'text-xs'}`}
                      style={{
                        color: isSelected ? THEME.emerald : '#ffffff',
                      }}
                    >
                      {student.nama}
                    </span>
                    {photoshoot ? (
                      isSelected ? (
                        <CheckSquare className={`shrink-0 ${isCompact ? 'size-3' : 'size-3.5'}`} style={{ color: THEME.emerald }} />
                      ) : (
                        <Square className={`shrink-0 ${isCompact ? 'size-3' : 'size-3.5'}`} style={{ color: THEME.muted }} />
                      )
                    ) : isNext ? (
                      <Megaphone className={`shrink-0 ${isCompact ? 'size-3' : 'size-3.5'}`} style={{ color: THEME.gold }} />
                    ) : null}
                  </div>
                )
              })
            )}
          </div>
        </ScrollArea>

        {/* Footer: CALL (wisuda) / SEND (photoshoot) button */}
        <div
          className={`shrink-0 ${isCompact ? 'p-1.5' : 'p-3'}`}
          style={{ borderTop: `1px solid ${THEME.border}`, backgroundColor: THEME.panel }}
        >
          {/* Selected students preview (photoshoot only, not done) */}
          {photoshoot && selectedStudent && selectedStudent.status !== 'done' && selectedStudents.length === 0 && (
            <div
              className={`rounded-md mb-2 min-w-0 ${isCompact ? 'p-1.5' : 'p-2'}`}
              style={{ backgroundColor: `${THEME.emerald}15`, border: `1px solid ${THEME.emerald}33` }}
            >
              <p className={`font-semibold uppercase tracking-wider truncate ${isCompact ? 'text-[8px]' : 'text-[10px]'}`} style={{ color: THEME.emerald }}>
                Peserta Dipilih
              </p>
              <p className={`font-bold truncate ${isCompact ? 'text-[10px]' : 'text-xs'}`} style={{ color: '#ffffff' }}>
                {selectedStudent.nama}
              </p>
              <p className={`font-mono truncate ${isCompact ? 'text-[9px]' : 'text-[10px]'}`} style={{ color: THEME.muted }}>
                {selectedStudent.nim}
              </p>
            </div>
          )}
          {/* Multi-select preview (photoshoot only) — shows count + first 3 names */}
          {photoshoot && selectedStudents.length > 0 && (
            <div
              className={`rounded-md mb-2 min-w-0 ${isCompact ? 'p-1.5' : 'p-2'}`}
              style={{ backgroundColor: `${THEME.emerald}15`, border: `1px solid ${THEME.emerald}33` }}
            >
              <p className={`font-semibold uppercase tracking-wider truncate ${isCompact ? 'text-[8px]' : 'text-[10px]'}`} style={{ color: THEME.emerald }}>
                {selectedStudents.length} Peserta Dipilih
              </p>
              {selectedStudents.slice(0, 3).map((s) => (
                <p key={s.id} className={`font-bold truncate ${isCompact ? 'text-[10px]' : 'text-xs'}`} style={{ color: '#ffffff' }}>
                  {s.nama}
                </p>
              ))}
              {selectedStudents.length > 3 && (
                <p className={`font-medium italic ${isCompact ? 'text-[9px]' : 'text-[10px]'}`} style={{ color: THEME.muted }}>
                  +{selectedStudents.length - 3} lainnya
                </p>
              )}
            </div>
          )}
          {/* When the selected student is already 'done', redirect reset to SELESAI column */}
          {photoshoot && selectedStudent && selectedStudent.status === 'done' ? (
            <p className={`text-center italic ${isCompact ? 'text-[9px]' : 'text-xs'}`} style={{ color: THEME.gold }}>
              ↳ Lihat kolom SELESAI untuk reset
            </p>
          ) : (
            renderCallButton()
          )}
        </div>
      </Card>
    )
  }

  // ── NEW: PROSES column (status === 'sent' || isActiveStatus) ─────────────
  // Display-only — NO click, NO reset from here. The user explicitly required
  // that sent-but-not-photographed students can NOT be re-clicked/reset by MC
  // (they're already with the operator).
  const renderProsesColumn = (opts: { isCompact: boolean }) => {
    const { isCompact } = opts
    return (
      <Card
        className="flex flex-col min-h-0 border rounded-xl overflow-hidden min-w-0 h-full"
        style={{
          backgroundColor: THEME.card,
          borderColor: THEME.border,
          flex: 3,
        }}
      >
        {/* Header */}
        <div
          className={`shrink-0 flex items-center justify-between gap-2 ${isCompact ? 'px-2 py-1.5' : 'px-3 py-2'}`}
          style={{ borderBottom: `1px solid ${THEME.border}` }}
        >
          <div className="flex items-center gap-2 min-w-0">
            <h3 className={`font-semibold uppercase tracking-wider truncate ${isCompact ? 'text-[9px]' : 'text-xs'}`} style={{ color: THEME.cyan }}>
              Proses
            </h3>
            <span
              className={`font-bold rounded-full shrink-0 ${isCompact ? 'text-[9px] px-1.5 py-0' : 'text-xs px-2 py-0.5'}`}
              style={{
                backgroundColor: `${THEME.cyan}33`,
                color: THEME.cyan,
                border: `1px solid ${THEME.cyan}55`,
              }}
            >
              {prosesStudents.length}
            </span>
          </div>
          <span className={`truncate shrink-0 ${isCompact ? 'text-[8px]' : 'text-[10px]'}`} style={{ color: THEME.muted }}>
            Dikirim ke operator
          </span>
        </div>

        {/* List — display only */}
        <ScrollArea className="flex-1 min-h-0">
          <div className="flex flex-col">
            {prosesStudents.length === 0 ? (
              <div className={`flex items-center justify-center ${isCompact ? 'py-6' : 'py-12'}`}>
                <p className={`${isCompact ? 'text-[10px]' : 'text-sm'}`} style={{ color: THEME.muted }}>
                  Belum ada yang dikirim
                </p>
              </div>
            ) : (
              prosesStudents.map((student, idx) => {
                const isActive = isActiveStatus(student.status)
                const completion = photoshoot ? getStudentChannelCompletion(student.id) : {}
                const showOpProgress = !!opProgressText && (isActive || (photoshoot && student.status === 'sent'))
                return (
                  <div
                    key={student.id}
                    ref={isActive ? activeRowRef : undefined}
                    className={`flex flex-col min-w-0 ${isCompact ? 'px-2 py-1.5' : 'px-3 py-2'}`}
                    style={getRowStyle(student)}
                  >
                    <div className="flex items-center gap-2 min-w-0">
                      <span className={`font-mono shrink-0 ${isCompact ? 'text-[9px] w-3' : 'text-[10px] w-5'}`} style={{ color: THEME.muted }}>
                        {idx + 1}
                      </span>
                      <span className={`font-mono truncate shrink-0 ${isCompact ? 'text-[9px] w-12' : 'text-[10px] w-16'}`} style={{ color: THEME.muted }}>
                        {student.nim}
                      </span>
                      <span
                        className={`font-medium truncate flex-1 min-w-0 ${isCompact ? 'text-[10px]' : 'text-xs'}`}
                        style={{ color: isActive ? THEME.gold : THEME.cyan }}
                      >
                        {student.nama}
                      </span>
                      <div className="shrink-0">
                        {renderStatusBadge(student.status)}
                      </div>
                    </div>
                    {/* Per-channel completion (photoshoot + sent) */}
                    {photoshoot && student.status === 'sent' && (
                      <div className={`flex items-center gap-1 min-w-0 ${isCompact ? 'mt-0.5 pl-4' : 'mt-1 pl-6'}`}>
                        {dualPhotoshoot ? (
                          [1, 2].map((ch) => {
                            const done = !!completion[ch]
                            return (
                              <Badge
                                key={ch}
                                className={`px-1 py-0 ${isCompact ? 'text-[8px]' : 'text-[9px]'}`}
                                style={{
                                  backgroundColor: done ? '#22c55e33' : `${THEME.cyan}22`,
                                  color: done ? '#4ade80' : THEME.cyan,
                                  border: `1px solid ${done ? '#22c55e55' : `${THEME.cyan}44`}`,
                                }}
                              >
                                Ch.{ch} {done ? '✓' : '...'}
                              </Badge>
                            )
                          })
                        ) : (
                          <Badge
                            className={`px-1 py-0 ${isCompact ? 'text-[8px]' : 'text-[9px]'}`}
                            style={{
                              backgroundColor: !!completion[1] ? '#22c55e33' : `${THEME.cyan}22`,
                              color: !!completion[1] ? '#4ade80' : THEME.cyan,
                              border: `1px solid ${!!completion[1] ? '#22c55e55' : `${THEME.cyan}44`}`,
                            }}
                          >
                            {!!completion[1] ? '✓ Selesai' : 'Memotret...'}
                          </Badge>
                        )}
                      </div>
                    )}
                    {/* opProgressText under active/sent student */}
                    {showOpProgress && (
                      <div className={`flex items-center gap-1.5 min-w-0 ${isCompact ? 'mt-0.5 pl-4' : 'mt-1 pl-6'}`}>
                        <Loader2 className={`shrink-0 animate-spin ${isCompact ? 'size-2.5' : 'size-3'}`} style={{ color: isActive ? THEME.gold : THEME.cyan }} />
                        <span className={`truncate ${isCompact ? 'text-[8px]' : 'text-[10px]'}`} style={{ color: isActive ? THEME.gold : THEME.cyan }}>
                          {opProgressText}
                        </span>
                      </div>
                    )}
                  </div>
                )
              })
            )}
          </div>
        </ScrollArea>

        {/* Footer: hint (no reset from here) */}
        <div
          className={`shrink-0 ${isCompact ? 'px-2 py-1.5' : 'px-3 py-2'}`}
          style={{ borderTop: `1px solid ${THEME.border}`, backgroundColor: THEME.panel }}
        >
          <p className={`text-center italic ${isCompact ? 'text-[8px]' : 'text-[10px]'}`} style={{ color: THEME.muted }}>
            Display only — reset hanya dari SELESAI
          </p>
        </div>
      </Card>
    )
  }

  // ── NEW: SELESAI column (status === 'done') ─────────────────────────────
  // The ONLY column where reset+resend is allowed. Click a row → selectedStudent
  // (status 'done') → renderCallButton renders RESET & KIRIM ULANG.
  const renderSelesaiColumn = (opts: { isCompact: boolean }) => {
    const { isCompact } = opts
    return (
      <Card
        className="flex flex-col min-h-0 border rounded-xl overflow-hidden min-w-0 h-full"
        style={{
          backgroundColor: THEME.card,
          borderColor: THEME.border,
          flex: 3,
        }}
      >
        {/* Header */}
        <div
          className={`shrink-0 flex items-center justify-between gap-2 ${isCompact ? 'px-2 py-1.5' : 'px-3 py-2'}`}
          style={{ borderBottom: `1px solid ${THEME.border}` }}
        >
          <div className="flex items-center gap-2 min-w-0">
            <h3 className={`font-semibold uppercase tracking-wider truncate ${isCompact ? 'text-[9px]' : 'text-xs'}`} style={{ color: THEME.emerald }}>
              Selesai
            </h3>
            <span
              className={`font-bold rounded-full shrink-0 ${isCompact ? 'text-[9px] px-1.5 py-0' : 'text-xs px-2 py-0.5'}`}
              style={{
                backgroundColor: `${THEME.emerald}33`,
                color: THEME.emerald,
                border: `1px solid ${THEME.emerald}55`,
              }}
            >
              {selesaiStudents.length}
            </span>
          </div>
          <span className={`truncate shrink-0 ${isCompact ? 'text-[8px]' : 'text-[10px]'}`} style={{ color: THEME.muted }}>
            Sudah disimpan
          </span>
        </div>

        {/* Search — cari peserta di SELESAI (Disimpan) untuk reset+resend.
            Berguna saat ribuan peserta sudah selesai; MC bisa langsung cari tanpa scroll. */}
        <div
          className={`shrink-0 relative ${isCompact ? 'px-2 py-1.5' : 'px-3 py-2'}`}
          style={{ borderBottom: `1px solid ${THEME.border}`, backgroundColor: THEME.panel }}
        >
          <Search className={`absolute top-1/2 -translate-y-1/2 ${isCompact ? 'left-3.5 size-3' : 'left-4 size-3.5'}`} style={{ color: THEME.muted }} />
          <Input
            placeholder="Cari NIM atau Nama..."
            value={selesaiSearchQuery}
            onChange={(e) => {
              setSelesaiSearchQuery(e.target.value)
              setSelectedStudent(null)
            }}
            className={`${isCompact ? 'pl-7 h-7 text-[10px]' : 'pl-8 h-9 text-xs'} border-[#533485] bg-[#3b2263] text-white placeholder:text-[#533485] focus-visible:border-[#4ade80] focus-visible:ring-[#4ade80]/30`}
          />
        </div>

        {/* List — click to select for reset (photoshoot only) */}
        <ScrollArea className="flex-1 min-h-0">
          <div className="flex flex-col">
            {displayedSelesai.length === 0 ? (
              <div className={`flex items-center justify-center ${isCompact ? 'py-6' : 'py-12'}`}>
                <p className={`${isCompact ? 'text-[10px]' : 'text-sm'}`} style={{ color: THEME.muted }}>
                  {selesaiStudents.length === 0 ? 'Belum ada yang selesai' : 'Tidak ditemukan'}
                </p>
              </div>
            ) : (
              displayedSelesai.map((student, idx) => {
                const isSelected = photoshoot && selectedStudent?.id === student.id
                return (
                  <div
                    key={student.id}
                    className={`flex items-center gap-2 transition-colors duration-200 min-w-0
                      ${photoshoot ? 'cursor-pointer' : ''}
                      ${isCompact ? 'px-2 py-1.5' : 'px-3 py-2'}`}
                    style={getRowStyle(student)}
                    onClick={() => {
                      if (photoshoot) {
                        setSelectedStudent(student)
                      }
                    }}
                  >
                    <span className={`font-mono shrink-0 ${isCompact ? 'text-[9px] w-3' : 'text-[10px] w-5'}`} style={{ color: THEME.muted }}>
                      {idx + 1}
                    </span>
                    <span className={`font-mono truncate shrink-0 ${isCompact ? 'text-[9px] w-12' : 'text-[10px] w-16'}`} style={{ color: THEME.muted }}>
                      {student.nim}
                    </span>
                    <span
                      className={`font-medium truncate flex-1 min-w-0 line-through ${isCompact ? 'text-[10px]' : 'text-xs'}`}
                      style={{ color: isSelected ? THEME.gold : THEME.muted }}
                    >
                      {student.nama}
                    </span>
                    {photoshoot && isSelected && (
                      <RotateCcw className={`shrink-0 ${isCompact ? 'size-3' : 'size-3.5'}`} style={{ color: THEME.gold }} />
                    )}
                  </div>
                )
              })
            )}
          </div>
        </ScrollArea>

        {/* Footer: RESET button (photoshoot only) */}
        {photoshoot ? (
          <div
            className={`shrink-0 ${isCompact ? 'p-1.5' : 'p-3'}`}
            style={{ borderTop: `1px solid ${THEME.border}`, backgroundColor: THEME.panel }}
          >
            {selectedStudent && selectedStudent.status === 'done' ? (
              <>
                <div
                  className={`rounded-md mb-2 min-w-0 ${isCompact ? 'p-1.5' : 'p-2'}`}
                  style={{ backgroundColor: `${THEME.gold}15`, border: `1px solid ${THEME.gold}55` }}
                >
                  <p className={`font-semibold uppercase tracking-wider truncate ${isCompact ? 'text-[8px]' : 'text-[10px]'}`} style={{ color: THEME.gold }}>
                    ⚠ Peserta Sudah Difoto
                  </p>
                  <p className={`font-bold truncate ${isCompact ? 'text-[10px]' : 'text-xs'}`} style={{ color: '#ffffff' }}>
                    {selectedStudent.nama}
                  </p>
                  <p className={`font-mono truncate ${isCompact ? 'text-[9px]' : 'text-[10px]'}`} style={{ color: THEME.muted }}>
                    {selectedStudent.nim}
                  </p>
                  <p className={`truncate mt-1 ${isCompact ? 'text-[8px]' : 'text-[10px]'}`} style={{ color: THEME.gold }}>
                    Klik RESET & KIRIM ULANG untuk memfoto ulang.
                  </p>
                </div>
                {renderCallButton()}
              </>
            ) : (
              <p className={`text-center italic ${isCompact ? 'text-[9px]' : 'text-xs'}`} style={{ color: THEME.muted }}>
                Klik peserta selesai untuk reset & kirim ulang
              </p>
            )}
          </div>
        ) : (
          <div
            className={`shrink-0 ${isCompact ? 'px-2 py-1.5' : 'px-3 py-2'}`}
            style={{ borderTop: `1px solid ${THEME.border}`, backgroundColor: THEME.panel }}
          >
            <p className={`text-center italic ${isCompact ? 'text-[8px]' : 'text-[10px]'}`} style={{ color: THEME.muted }}>
              Selesai — alur wisuda (no reset)
            </p>
          </div>
        )}
      </Card>
    )
  }

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
          Layar terkunci untuk menghindari gangguan.<br />Klik di mana saja untuk membuka.
        </div>
        <button
          style={{ marginTop: '8px', padding: '10px 24px', background: '#d4af37', color: '#1a0b2e', border: 'none', borderRadius: '8px', fontSize: '14px', fontWeight: 'bold' as const, cursor: 'pointer' as const }}
          onClick={(e) => { e.stopPropagation(); setMonitorLocked(false) }}
        >🔓 BUKA KUNCI</button>
      </div>
    )
  }

  // Floating toggle button (top-right, always visible)
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
      title={monitorLocked ? 'Buka kunci' : 'Kunci layar (mode monitor)'}
    >
      {monitorLocked ? '🔓 Terkunci' : '🔒 Kunci'}
    </button>
  )

  // ── Main render
  if (!currentProject) {
    return (
      <div
        className="flex items-center justify-center h-full"
        style={{ backgroundColor: THEME.bg, color: THEME.muted }}
      >
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="size-8 animate-spin" style={{ color: THEME.gold }} />
          <p className="text-sm opacity-60">Memuat proyek...</p>
        </div>
      </div>
    )
  }

  // ── MOBILE LAYOUT — tabs (3 columns won't fit a phone) ──────────────────
  // Top bar pills double as tabs. The active tab's column renders full-height.
  if (isMobile) {
    return (
      <>
        <div className="flex flex-col h-full min-w-0 touch-no-select" style={{ backgroundColor: THEME.bg }}>
          {renderTopBar({ condensed: true })}
          <div className="flex-1 min-h-0 p-2 min-w-0">
            {activeTab === 'antrean' && renderAntreanColumn({ isCompact: true })}
            {activeTab === 'proses' && renderProsesColumn({ isCompact: true })}
            {activeTab === 'selesai' && renderSelesaiColumn({ isCompact: true })}
          </div>
        </div>
        {renderMonitorLock()}
        {renderLockToggle()}
      </>
    )
  }

  // ── COMPACT DESKTOP LAYOUT (Portable's tab) — tabbed (narrow sidebar) ───
  // Same tabbed UX as mobile, slightly tighter padding. Pills in top bar act
  // as tabs.
  if (compact) {
    return (
      <>
        <div className="flex flex-col h-full min-w-0" style={{ backgroundColor: THEME.bg }}>
          {renderTopBar({ condensed: true })}
          <div className="flex-1 min-h-0 p-1.5 min-w-0">
            {activeTab === 'antrean' && renderAntreanColumn({ isCompact: true })}
            {activeTab === 'proses' && renderProsesColumn({ isCompact: true })}
            {activeTab === 'selesai' && renderSelesaiColumn({ isCompact: true })}
          </div>
        </div>
        {renderMonitorLock()}
        {renderLockToggle()}
      </>
    )
  }

  // ── DESKTOP LAYOUT — 3 columns side-by-side + top progress bar ──────────
  // ANTREAN (4fr) | PROSES (3fr) | SELESAI (3fr), all full-height. Pills in
  // top bar are stats (no tab switching needed — all 3 visible).
  return (
    <>
      <div className="flex flex-col h-full min-w-0" style={{ backgroundColor: THEME.bg }}>
        {renderTopBar({ condensed: false })}
        <div className="flex flex-row gap-3 flex-1 min-h-0 p-3 min-w-0">
          {renderAntreanColumn({ isCompact: false })}
          {renderProsesColumn({ isCompact: false })}
          {renderSelesaiColumn({ isCompact: false })}
        </div>
      </div>
      {renderMonitorLock()}
      {renderLockToggle()}
    </>
  )
}
export default McPanel
