'use client'

import { create } from 'zustand'

export type StudentStatus = 'pending' | 'sent' | 'done' | `active_${number}`

export interface Student {
  id: string
  nim: string
  nama: string
  status: StudentStatus
  assignedChannel: number
}

export type CameraMode = 'single' | 'dual' | 'single-photoshoot' | 'dual-photoshoot'

export interface ProjectConfig {
  mode: CameraMode
  ratio: string
  preset: string
  targetFolder: string
  frame: string | null
  sessionPassword?: string
  // Per-project Google Drive / cloud backup folder (null = no backup for this
  // project). NOT auto-connected on project creation — the admin explicitly
  // picks a folder per-project via the Admin tab's Google Drive section.
  // Each project can have its own folder (or none).
  driveFolder?: string | null
}

// ─── Photoshoot mode helpers ──────────────────────────────────────────────────
export function isPhotoshootMode(mode: CameraMode): boolean {
  return mode === 'single-photoshoot' || mode === 'dual-photoshoot'
}

export function isDualPhotoshootMode(mode: CameraMode): boolean {
  return mode === 'dual-photoshoot'
}

export function isDualMode(mode: CameraMode): boolean {
  return mode === 'dual' || mode === 'dual-photoshoot'
}

/** How many photos per student per operator for a given mode */
export function photosPerSession(mode: CameraMode): number {
  return isPhotoshootMode(mode) ? 1 : 2
}

/** How many channels (cameras) are needed for a given mode */
export function channelCount(mode: CameraMode): number {
  return isDualMode(mode) ? 2 : 1
}

export interface PhotoHistoryItem {
  student: Student
  photos: string[]
  channel: number
}

export interface Project {
  id: string
  name: string
  config: ProjectConfig
  database: Student[]
  photoHistory: PhotoHistoryItem[]
  /**
   * Per-student+channel capture counter, used to generate VERSIONED filenames
   * so each retake (after MC reset) creates a NEW file on disk instead of
   * overwriting the previous one.
   *
   * Key: `${studentId}_${channel}`  →  value: capture count (1 = first, 2 = first retake, …)
   *
   * IMPORTANT: this map is NOT cleared on RESET — it persists across resets so
   * retakes keep incrementing the version. This is the only way to guarantee
   * that every retake produces a distinct file on disk (e.g. `NIM_Nama.jpg`,
   * `NIM_Nama_v2.jpg`, `NIM_Nama_v3.jpg`, …).
   */
  captureVersions?: Record<string, number>
}

export type Role = 'admin' | 'mc' | 'operator'
export type AppScreen = 'hub' | 'setup' | 'app'
export type AppTab = 'admin' | 'mc' | 'operator'

// ─── Memory guard: max photo history items kept in memory ──────────────────
// With thousands of participants, we can't keep all base64 photos in memory.
// Admin keeps last N items for live gallery; MC/Operator only need current target.
// Photos are saved to disk by the Admin's handlePhotosSaved handler (via savePhoto
// IPC) — disk saves are UNCAPPED and independent of this in-memory gallery cap.
// The operator exe ALSO saves a local copy for redundancy.
// NOTE: This caps ONLY the in-memory gallery for display. DISK SAVES are
// triggered per-PHOTOS_SAVED and are UNLIMITED — safe for 4000+ participants.
//
// BUG #8 (audit-electron-side): raised from 200 → 500. At 200 the admin couldn't
// scroll back to verify photos of students photographed earlier than the last
// 200 in a 4000+ participant ceremony (the first ~3800 were invisible in the
// gallery by the end). 500 entries × 2 photos × ~100KB ≈ 100MB — acceptable
// for a ceremony laptop with 8GB+ RAM. Beyond the in-memory cap, the admin can
// still verify via the file explorer on disk (disk saves are uncapped). After
// an app restart, BUG #7's LazyThumbnail rehydrates the last 500 from disk.
const MAX_PHOTO_HISTORY_IN_MEMORY = 500

// ─── Frame storage: separate localStorage keys for frame base64 data ──────────
// Frame data can be 500KB-2MB and must survive page reloads.
// We store it in separate localStorage keys so it's not lost when
// the main project list is saved with '__FRAME_SAVED__' markers.
const FRAME_KEY_PREFIX = 'saatiril_frame_'
const PASSWORD_HASH_KEY_PREFIX = 'saatiril_pwdhash_'
const PASSWORD_PLAIN_KEY_PREFIX = 'saatiril_pwd_plain_'

function savePasswordHashToStorage(projectId: string, hash: string | null) {
  try {
    if (hash) {
      localStorage.setItem(`${PASSWORD_HASH_KEY_PREFIX}${projectId}`, hash)
    } else {
      localStorage.removeItem(`${PASSWORD_HASH_KEY_PREFIX}${projectId}`)
    }
  } catch (e) {
    console.error('[SAATIRIL] Failed to save password hash to separate storage:', e)
  }
}

function loadPasswordHashFromStorage(projectId: string): string | null {
  try {
    return localStorage.getItem(`${PASSWORD_HASH_KEY_PREFIX}${projectId}`)
  } catch (e) {
    console.error('[SAATIRIL] Failed to load password hash from separate storage:', e)
    return null
  }
}

function removePasswordHashFromStorage(projectId: string) {
  try {
    localStorage.removeItem(`${PASSWORD_HASH_KEY_PREFIX}${projectId}`)
  } catch (e) {
    console.error('[SAATIRIL] Failed to remove password hash from separate storage:', e)
  }
}

function savePasswordPlainToStorage(projectId: string, password: string | null) {
  try {
    if (password && password !== '__PASSWORD_SET__') {
      localStorage.setItem(`${PASSWORD_PLAIN_KEY_PREFIX}${projectId}`, password)
    } else if (!password || password === '__PASSWORD_SET__') {
      localStorage.removeItem(`${PASSWORD_PLAIN_KEY_PREFIX}${projectId}`)
    }
  } catch (e) {
    console.error('[SAATIRIL] Failed to save plaintext password to separate storage:', e)
  }
}

function loadPasswordPlainFromStorage(projectId: string): string | null {
  try {
    return localStorage.getItem(`${PASSWORD_PLAIN_KEY_PREFIX}${projectId}`)
  } catch (e) {
    console.error('[SAATIRIL] Failed to load plaintext password from separate storage:', e)
    return null
  }
}

function removePasswordPlainFromStorage(projectId: string) {
  try {
    localStorage.removeItem(`${PASSWORD_PLAIN_KEY_PREFIX}${projectId}`)
  } catch (e) {
    console.error('[SAATIRIL] Failed to remove plaintext password from separate storage:', e)
  }
}

function saveFrameToStorage(projectId: string, frameData: string | null) {
  try {
    if (frameData && frameData !== '__FRAME_SAVED__') {
      localStorage.setItem(`${FRAME_KEY_PREFIX}${projectId}`, frameData)
    } else {
      localStorage.removeItem(`${FRAME_KEY_PREFIX}${projectId}`)
    }
  } catch (e) {
    console.error('[SAATIRIL] Failed to save frame to separate storage:', e)
  }
}

function loadFrameFromStorage(projectId: string): string | null {
  try {
    return localStorage.getItem(`${FRAME_KEY_PREFIX}${projectId}`)
  } catch (e) {
    console.error('[SAATIRIL] Failed to load frame from separate storage:', e)
    return null
  }
}

function removeFrameFromStorage(projectId: string) {
  try {
    localStorage.removeItem(`${FRAME_KEY_PREFIX}${projectId}`)
  } catch (e) {
    console.error('[SAATIRIL] Failed to remove frame from separate storage:', e)
  }
}

// ─── Student status priority for merge ───────────────────────────────────────
// When merging databases from different clients, we keep the "most advanced" status.
// pending (0) < sent (1) < active_N (2) < done (3)
function getStatusPriority(status: StudentStatus): number {
  if (status === 'pending') return 0
  if (status === 'sent') return 1
  if (status === 'done') return 3
  // active_N statuses get priority 2
  return 2
}

/**
 * Merge two student databases, keeping the "most advanced" status for each student.
 * This prevents data regression when SYNC_DB payloads from different channels
 * overwrite each other's progress in dual mode.
 *
 * BUG #20 (audit-electron-side): preserve LOCAL array order. Previously this
 * used `Array.from(studentMap.values())` whose order is Map insertion order —
 * local students first (in their original order), then any NEW incoming
 * students appended in incoming order. That happens to BE the local order for
 * existing students, BUT a Map's iteration order can be subtly disrupted if
 * a key is `set()` again after initial insertion (the key keeps its ORIGINAL
 * position — so re-setting existing students is fine). However, the audit
 * flagged that the MC's `nextPending = channelStudents.find(s => s.status ===
 * 'pending')` could pick a different student after a SYNC_DB merge if the
 * array order changed. To make the order guarantee EXPLICIT + robust against
 * any future Map-iteration changes, we now build the result by:
 *   1. Iterating localDb IN ORDER (preserves Excel-import order).
 *   2. Appending any NEW incoming students (not in localDb) in incoming order.
 *   3. Applying the status merge (max-priority) per student.
 * This guarantees the local array order never shifts due to a sync.
 */
export function mergeDatabases(
  localDb: Student[],
  incomingDb: Student[],
): Student[] {
  // Index incoming by id for O(1) lookup + dedupe.
  const incomingById = new Map<string, Student>()
  for (const s of incomingDb) {
    const ex = incomingById.get(s.id)
    if (!ex || getStatusPriority(s.status) > getStatusPriority(ex.status)) {
      incomingById.set(s.id, s)
    }
  }

  // First pass: walk localDb IN ORDER, merging in the incoming status if any.
  const seenIds = new Set<string>()
  const result: Student[] = []
  for (const s of localDb) {
    seenIds.add(s.id)
    const inc = incomingById.get(s.id)
    if (inc && getStatusPriority(inc.status) > getStatusPriority(s.status)) {
      result.push(inc)
    } else {
      result.push(s)
    }
  }

  // Second pass: append any incoming students NOT in localDb (new students
  // added by another client since our last sync) in incoming order.
  for (const s of incomingDb) {
    if (!seenIds.has(s.id)) {
      seenIds.add(s.id)
      result.push(s)
    }
  }

  return result
}

/**
 * Strip frame base64 data AND photo base64 data from a project for SYNC_DB transmission.
 * Frame data can be 500KB-2MB and doesn't need to be re-sent every time.
 * Photo data in photoHistory can grow to hundreds of MB and is ALREADY sent
 * via PHOTOS_SAVED events — no need to resend in SYNC_DB.
 * Recipients who already have the data don't need it again.
 *
 * NOTE: The initial REQUEST_STATE response should NOT use this — new clients
 * need the full data. Only use this for subsequent SYNC_DB updates.
 */
export function stripFrameForSync(project: Project): Project {
  return {
    ...project,
    config: { ...project.config, frame: project.config.frame ? '__FRAME_SAVED__' : null, sessionPassword: '__PASSWORD_SET__' },
    photoHistory: project.photoHistory.map(h => ({ ...h, photos: [] })),
  }
}

/**
 * Preserve frame data when receiving SYNC_DB with '__FRAME_SAVED__' marker.
 *
 * When a client receives a SYNC_DB where the frame was stripped (marked as
 * '__FRAME_SAVED__'), this function keeps the existing frame data from the
 * current project. This ensures the frame overlay stays visible on the
 * operator camera and photos continue to be captured with the frame applied.
 */
export function preserveFrameOnSync(
  incomingConfig: ProjectConfig,
  existingConfig: ProjectConfig | undefined,
): ProjectConfig {
  let result = { ...incomingConfig }

  if (
    incomingConfig.frame === '__FRAME_SAVED__' &&
    existingConfig?.frame &&
    existingConfig.frame !== '__FRAME_SAVED__'
  ) {
    result = { ...result, frame: existingConfig.frame }
  }

  // Preserve sessionPassword — never send actual password over LAN
  if (incomingConfig.sessionPassword === '__PASSWORD_SET__' && existingConfig?.sessionPassword && existingConfig.sessionPassword !== '__PASSWORD_SET__') {
    result = { ...result, sessionPassword: existingConfig.sessionPassword }
  }
  // If both are __PASSWORD_SET__ or no existing password, keep the marker

  return result
}

/**
 * Preserve photo data when receiving SYNC_DB with stripped photoHistory.
 *
 * When a client receives a SYNC_DB where photos were stripped (empty photos arrays
 * in photoHistory), this function merges the histories keeping existing photos.
 * This prevents photo data loss while still accepting updated metadata from the
 * incoming sync (e.g., new photoHistory entries from other channels).
 *
 * Key rules:
 * - If incoming has photos → use incoming (it's the source of truth)
 * - If incoming has no photos but existing does → keep existing photos
 * - If both have no photos → keep as-is
 * - If incoming has a new entry not in existing → add it
 */
export function preservePhotoHistoryOnSync(
  incomingHistory: PhotoHistoryItem[],
  existingHistory: PhotoHistoryItem[],
): PhotoHistoryItem[] {
  if (!incomingHistory.length) return existingHistory
  if (!existingHistory.length) return incomingHistory

  const result: PhotoHistoryItem[] = [...existingHistory]
  for (const incoming of incomingHistory) {
    const existingIdx = result.findIndex(
      (h) => h.student.id === incoming.student.id && h.channel === incoming.channel,
    )
    if (existingIdx !== -1) {
      if (incoming.photos.length === 0 && result[existingIdx].photos.length > 0) {
        // Incoming stripped photos — keep our photos, update metadata only
        result[existingIdx] = { ...incoming, photos: result[existingIdx].photos }
      } else if (incoming.photos.length > 0) {
        // Incoming has photos — use them (source of truth)
        result[existingIdx] = incoming
      }
      // else: both empty → keep as-is
    } else {
      // New entry not in existing → add it
      result.push(incoming)
    }
  }
  return result
}

/**
 * Merge two captureVersions maps, keeping the MAX version per key.
 *
 * captureVersions tracks how many times a student+channel has been
 * photographed (1 = first, 2+ = retake). It must NEVER regress — if the
 * operator has v3 locally and an incoming SYNC_DB has v2, we keep v3.
 * Taking the max guarantees the version number only goes up, so every
 * retake produces a distinct filename on disk (v1, v2, v3, …).
 */
export function mergeCaptureVersions(
  local: Record<string, number> | undefined,
  incoming: Record<string, number> | undefined,
): Record<string, number> {
  const result: Record<string, number> = { ...(local ?? {}) }
  if (incoming) {
    for (const [k, v] of Object.entries(incoming)) {
      const n = Number(v)
      if (!Number.isNaN(n) && n > 0) {
        result[k] = Math.max(result[k] ?? 0, n)
      }
    }
  }
  return result
}

// ─── Debounced save ───────────────────────────────────────────────────────
let saveTimeout: ReturnType<typeof setTimeout> | null = null
const SAVE_DEBOUNCE_MS = 500 // Debounce saves to avoid thrashing localStorage

// BUG #16 (audit-electron-side): opCapturedPhotosByChannel persistence.
// Previously opCapturedPhotos lived ONLY in memory (Zustand state). If the
// operator refreshed the page (F5, accidental, browser crash) mid-capture
// (e.g. after photo 1 of 2 in wisuda mode), the in-progress photo was LOST.
// The student's status was still 'active_N' in the DB (persisted), so the
// operator could re-capture, but the first photo was gone. We now mirror the
// per-channel photos array to sessionStorage (survives refresh, cleared on
// tab close) so a mid-capture refresh restores the in-progress photos.
// sessionStorage is sized for this (a few MB per tab) — fits 2 base64 photos.
const OP_CAPTURED_PHOTOS_KEY = 'saatiril_op_captured_photos'

function loadOpCapturedPhotosFromSession(): Record<number, string[]> {
  try {
    const raw = sessionStorage.getItem(OP_CAPTURED_PHOTOS_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    if (parsed && typeof parsed === 'object') {
      const result: Record<number, string[]> = {}
      for (const [k, v] of Object.entries(parsed)) {
        if (Array.isArray(v)) result[Number(k)] = (v as unknown[]).filter(x => typeof x === 'string') as string[]
      }
      return result
    }
  } catch (e) {
    console.warn('[SAATIRIL] Failed to load opCapturedPhotos from sessionStorage:', e)
  }
  return {}
}

function saveOpCapturedPhotosToSession(photos: Record<number, string[]>) {
  try {
    // Only persist non-empty arrays to keep sessionStorage small + skip writes
    // when nothing changed. (Clearing all → remove the key entirely.)
    const hasData = Object.values(photos).some(arr => Array.isArray(arr) && arr.length > 0)
    if (hasData) {
      sessionStorage.setItem(OP_CAPTURED_PHOTOS_KEY, JSON.stringify(photos))
    } else {
      sessionStorage.removeItem(OP_CAPTURED_PHOTOS_KEY)
    }
  } catch (e) {
    // sessionStorage quota exceeded (rare for 2 photos) — non-fatal.
    console.warn('[SAATIRIL] Failed to save opCapturedPhotos to sessionStorage:', e)
  }
}

interface SaatirilState {
  // Projects
  projects: Project[]
  currentProject: Project | null

  // Auto-save tracking
  lastSavedAt: number | null  // Timestamp of last successful save

  // User role & channel
  myRole: Role
  myChannel: number

  // Screen & Tab
  currentScreen: AppScreen
  currentTab: AppTab

  // Operator state — PER-CHANNEL (keyed by channel number) so that 2
  // OperatorPanel instances in the dual 50/50 layout (channel 1 + channel 2)
  // don't share the same target/photos. Each channel has its own slice.
  opCurrentTargets: Record<number, Student | null>
  opCapturedPhotosByChannel: Record<number, string[]>

  // Actions
  setProjects: (projects: Project[]) => void
  addProject: (project: Project) => void
  deleteProject: (id: string) => void
  setCurrentProject: (project: Project | null) => void
  updateCurrentProject: (project: Project) => void
  setMyRole: (role: Role) => void
  setMyChannel: (channel: number) => void
  setCurrentScreen: (screen: AppScreen) => void
  setCurrentTab: (tab: AppTab) => void
  setOpCurrentTarget: (target: Student | null, channel: number) => void
  setOpCapturedPhotos: (photos: string[], channel: number) => void
  addOpCapturedPhoto: (photo: string, channel: number) => void
  resetOpState: (channel?: number) => void
  loadProjectsFromStorage: () => void
  saveProjectsToStorage: () => void
  saveProjectsToStorageNow: () => void
  updateStudentStatus: (studentId: string, status: StudentStatus) => void
}

/**
 * Trim photoHistory to prevent memory bloat with thousands of participants.
 * Only keeps the most recent N items (by array order = chronological).
 * Photo data is still saved to disk via the Operator's file save logic.
 */
function trimPhotoHistory(history: PhotoHistoryItem[]): PhotoHistoryItem[] {
  if (history.length <= MAX_PHOTO_HISTORY_IN_MEMORY) return history
  // Keep the most recent items (last N)
  return history.slice(history.length - MAX_PHOTO_HISTORY_IN_MEMORY)
}

/**
 * CRITICAL: Sanitize a project's data to prevent render crashes.
 *
 * During reset+retake cycles or cross-client sync, photoHistory entries can
 * become corrupted (missing student object, missing nama/nim fields). If such
 * an entry is rendered, sanitizeNama(undefined) would crash the whole app to
 * a white screen — and because the corrupted data is persisted in localStorage,
 * reopening the project crashes AGAIN (infinite loop).
 *
 * This function:
 * 1. Removes photoHistory entries with missing/invalid student objects
 * 2. Ensures every student in `database` has required string fields (nama, nim)
 * 3. Returns a clean project that is safe to render
 *
 * Called on load and whenever a project is set as current.
 */
export function sanitizeProject(project: Project): Project {
  if (!project || typeof project !== 'object') return project

  // Sanitize database: ensure every student has string nama/nim/id
  const cleanDatabase: Student[] = (project.database || []).map((s) => ({
    id: s?.id ?? `unknown_${Math.random().toString(36).slice(2)}`,
    nim: (s?.nim ?? '').toString(),
    nama: (s?.nama ?? '').toString(),
    status: (s?.status ?? 'pending') as StudentStatus,
    assignedChannel: s?.assignedChannel ?? 1,
  })).filter((s) => s.nim || s.nama) // drop fully-empty rows

  // Sanitize photoHistory: drop entries with missing student or student.id,
  // AND drop phantom entries (student not in database — can happen if a
  // corrupted SYNC_DB created an orphan history item).
  const validStudentIds = new Set(cleanDatabase.map((s) => s.id))
  const cleanPhotoHistory: PhotoHistoryItem[] = (project.photoHistory || [])
    .filter((h) => h && h.student && h.student.id && validStudentIds.has(h.student.id))
    .map((h) => ({
      student: {
        id: h.student.id,
        nim: (h.student.nim ?? '').toString(),
        nama: (h.student.nama ?? '').toString(),
        status: (h.student.status ?? 'done') as StudentStatus,
        assignedChannel: h.student.assignedChannel ?? 1,
      },
      photos: Array.isArray(h.photos) ? h.photos : [],
      channel: h.channel ?? 1,
    }))

  // Sanitize config: ensure targetFolder is a string
  const cleanConfig: ProjectConfig = {
    mode: project.config?.mode ?? 'single',
    ratio: project.config?.ratio ?? '4:3',
    preset: project.config?.preset ?? 'original',
    targetFolder: (project.config?.targetFolder ?? '').toString(),
    frame: project.config?.frame ?? null,
    sessionPassword: (project.config as ProjectConfig).sessionPassword,
  }

  // Sanitize captureVersions: ensure it's a plain object of numbers.
  // This map PERSISTS across resets — never cleared here — so retakes keep
  // incrementing the version number (each retake → new file on disk).
  const rawVersions = (project as Project).captureVersions
  const cleanCaptureVersions: Record<string, number> = {}
  if (rawVersions && typeof rawVersions === 'object') {
    for (const [k, v] of Object.entries(rawVersions)) {
      const n = Number(v)
      if (!Number.isNaN(n) && n > 0) cleanCaptureVersions[k] = n
    }
  }

  return {
    id: project.id,
    name: (project.name ?? 'Tanpa Nama').toString(),
    config: cleanConfig,
    database: cleanDatabase,
    photoHistory: cleanPhotoHistory,
    captureVersions: cleanCaptureVersions,
  }
}

/** Sanitize an array of projects (used on load from localStorage). */
export function sanitizeProjects(projects: Project[]): Project[] {
  if (!Array.isArray(projects)) return []
  return projects.map(sanitizeProject)
}

export const useSaatirilStore = create<SaatirilState>((set, get) => ({
  projects: [],
  currentProject: null,
  lastSavedAt: null,
  myRole: 'admin',
  myChannel: 1,
  currentScreen: 'hub',
  currentTab: 'admin',
  opCurrentTargets: {},
  // BUG #16: initialize from sessionStorage so a mid-capture refresh restores
  // the in-progress photos (instead of starting from empty).
  opCapturedPhotosByChannel: loadOpCapturedPhotosFromSession(),

  setProjects: (projects) => set({ projects }),
  addProject: (project) => set((s) => {
    // Save frame data to separate localStorage key immediately
    saveFrameToStorage(project.id, project.config.frame)
    return { projects: [...s.projects, project] }
    // Note: saveProjectsToStorage is called after setCurrentProject in the setup flow
  }),
  deleteProject: (id) => set((s) => {
    const newProjects = s.projects.filter(p => p.id !== id)
    const shouldClearCurrent = s.currentProject?.id === id
    // Remove frame data, password hash, and plaintext password from separate localStorage keys
    removeFrameFromStorage(id)
    removePasswordHashFromStorage(id)
    removePasswordPlainFromStorage(id)
    return {
      projects: newProjects,
      ...(shouldClearCurrent ? { currentProject: null } : {}),
    }
  }),
  setCurrentProject: (project) => {
    // Ensure frame data and password hash are in separate storage when setting current project
    if (project) {
      // CRITICAL: sanitize to prevent render crashes from corrupted data
      project = sanitizeProject(project)
      // If frame is the marker, try to restore from separate storage
      if (project.config.frame === '__FRAME_SAVED__') {
        const savedFrame = loadFrameFromStorage(project.id)
        if (savedFrame) {
          project = { ...project, config: { ...project.config, frame: savedFrame } }
        }
      }
      // If sessionPassword is the marker, try to restore the actual password from separate storage
      if (project.config.sessionPassword === '__PASSWORD_SET__') {
        const savedPlain = loadPasswordPlainFromStorage(project.id)
        if (savedPlain) {
          project = { ...project, config: { ...project.config, sessionPassword: savedPlain } }
        }
        // Also restore the hash for server re-authentication
        const savedHash = loadPasswordHashFromStorage(project.id)
        if (savedHash) {
          ;(project as any)._sessionPasswordHash = savedHash
        }
      }
      saveFrameToStorage(project.id, project.config.frame)
      // Save plaintext password and hash to separate storage if actual password is present
      if (project.config.sessionPassword && project.config.sessionPassword !== '__PASSWORD_SET__') {
        savePasswordPlainToStorage(project.id, project.config.sessionPassword)
        // This is the plaintext password from project creation — we'll hash it in the socket layer
        // For now, save a marker so we know a password was set
        savePasswordHashToStorage(project.id, '__PWD_PENDING__')
      }
    }
    set({ currentProject: project })
  },
  updateCurrentProject: (project) => {
    // If frame is marker, restore from separate storage
    if (project.config.frame === '__FRAME_SAVED__') {
      const savedFrame = loadFrameFromStorage(project.id)
      if (savedFrame) {
        project = { ...project, config: { ...project.config, frame: savedFrame } }
      }
    }
    // If sessionPassword is marker, restore actual password and hash from separate storage
    if (project.config.sessionPassword === '__PASSWORD_SET__') {
      const savedPlain = loadPasswordPlainFromStorage(project.id)
      if (savedPlain) {
        project = { ...project, config: { ...project.config, sessionPassword: savedPlain } }
      }
      const savedHash = loadPasswordHashFromStorage(project.id)
      if (savedHash && savedHash !== '__PWD_PENDING__') {
        ;(project as any)._sessionPasswordHash = savedHash
      }
    }
    // Auto-trim photo history to prevent memory bloat
    const trimmedProject = {
      ...project,
      photoHistory: trimPhotoHistory(project.photoHistory),
    }
    // Save frame data to separate localStorage key
    saveFrameToStorage(trimmedProject.id, trimmedProject.config.frame)
    set((s) => {
      const idx = s.projects.findIndex(p => p.id === trimmedProject.id)
      const newProjects = [...s.projects]
      if (idx !== -1) newProjects[idx] = trimmedProject
      return { currentProject: trimmedProject, projects: newProjects }
    })
    // ── AUTO-SAVE: Persist project state to localStorage on every update ──
    // This ensures progress is never lost if the app crashes or is closed.
    // The debounced save prevents localStorage thrashing during rapid updates.
    get().saveProjectsToStorage()
  },
  setMyRole: (role) => set({ myRole: role }),
  setMyChannel: (channel) => set({ myChannel: channel }),
  setCurrentScreen: (screen) => set({ currentScreen: screen }),
  setCurrentTab: (tab) => set({ currentTab: tab }),
  setOpCurrentTarget: (target, channel) => set((s) => ({ opCurrentTargets: { ...s.opCurrentTargets, [channel]: target } })),
  setOpCapturedPhotos: (photos, channel) => {
    set((s) => {
      const next = { ...s.opCapturedPhotosByChannel, [channel]: photos }
      // BUG #16: mirror to sessionStorage so a refresh restores in-progress photos.
      saveOpCapturedPhotosToSession(next)
      return { opCapturedPhotosByChannel: next }
    })
  },
  addOpCapturedPhoto: (photo, channel) => {
    set((s) => {
      const next = { ...s.opCapturedPhotosByChannel, [channel]: [...(s.opCapturedPhotosByChannel[channel] ?? []), photo] }
      // BUG #16: persist to sessionStorage immediately so a refresh captures
      // the in-progress photo before finalize.
      saveOpCapturedPhotosToSession(next)
      return { opCapturedPhotosByChannel: next }
    })
  },
  resetOpState: (channel) => set((s) => {
    const next = channel
      ? { opCurrentTargets: { ...s.opCurrentTargets, [channel]: null }, opCapturedPhotosByChannel: { ...s.opCapturedPhotosByChannel, [channel]: [] } }
      : { opCurrentTargets: {}, opCapturedPhotosByChannel: {} }
    // BUG #16: clear the corresponding sessionStorage entry (or all of it).
    saveOpCapturedPhotosToSession(next.opCapturedPhotosByChannel)
    return next
  }),

  loadProjectsFromStorage: () => {
    try {
      // Simple marker check: if no version marker, this is a fresh start
      // (Electron main process handles version-change clearing before first load)
      const storedVersion = localStorage.getItem('saatiril_app_version')
      const isElectron = typeof window !== 'undefined' && !!(window as any).saatirilAPI?.isElectron
      const currentVersion = isElectron ? 'electron' : 'web'

      if (!storedVersion) {
        // First time or fresh install — ensure clean state
        localStorage.removeItem('saatiril_projects')
        localStorage.setItem('saatiril_app_version', currentVersion)
        return
      }

      // Load saved projects
      const saved = localStorage.getItem('saatiril_projects')
      if (saved) {
        const rawProjects = JSON.parse(saved)
        // CRITICAL: sanitize every project to remove corrupted photoHistory
        // entries (missing student/nama/nim) that would crash the render.
        // This is the recovery path for the "Cannot read properties of
        // undefined (reading 'trim')" white-screen loop.
        const projects = sanitizeProjects(rawProjects)
        if (projects.length !== rawProjects.length) {
          console.warn(`[SAATIRIL] Sanitized projects: ${rawProjects.length} → ${projects.length} (dropped invalid entries)`)
        }
        // Restore frame data and plaintext password from separate localStorage keys
        // (frames are saved separately because they're too large for the main JSON)
        // (passwords are saved separately to avoid __PASSWORD_SET__ marker on reload)
        const restoredProjects = projects.map((p: Project) => {
          const savedFrame = loadFrameFromStorage(p.id)
          let restored = p
          if (savedFrame && (!p.config.frame || p.config.frame === '__FRAME_SAVED__')) {
            console.log(`[SAATIRIL] Restored frame for project: ${p.name}`)
            restored = { ...restored, config: { ...restored.config, frame: savedFrame } }
          }
          // Restore plaintext password from separate storage
          if (p.config.sessionPassword === '__PASSWORD_SET__') {
            const savedPlain = loadPasswordPlainFromStorage(p.id)
            if (savedPlain) {
              console.log(`[SAATIRIL] Restored plaintext password for project: ${p.name}`)
              restored = { ...restored, config: { ...restored.config, sessionPassword: savedPlain } }
            }
          }
          return restored
        })
        set({ projects: restoredProjects })
        // Persist the cleaned version back to localStorage so future loads
        // are also clean (and so we don't re-trigger the corruption).
        try {
          const safeProjects = restoredProjects.map(p => ({
            ...p,
            photoHistory: p.photoHistory.map(h => ({ ...h, photos: [] })),
            config: { ...p.config, frame: p.config.frame ? '__FRAME_SAVED__' : null },
          }))
          localStorage.setItem('saatiril_projects', JSON.stringify(safeProjects))
        } catch (e2) {
          console.warn('[SAATIRIL] Could not persist sanitized projects:', e2)
        }
      }
    } catch (e) {
      console.error('Failed to load projects from storage', e)
      // LAST RESORT: if localStorage is so corrupted that JSON.parse fails,
      // clear it so the app at least loads to the hub instead of crashing.
      try {
        localStorage.removeItem('saatiril_projects')
        console.warn('[SAATIRIL] Cleared corrupted saatiril_projects from localStorage')
      } catch { /* ignore */ }
    }
  },

  saveProjectsToStorage: () => {
    // Debounced — prevents localStorage thrashing during rapid state changes
    if (saveTimeout) clearTimeout(saveTimeout)
    saveTimeout = setTimeout(() => {
      try {
        const { projects } = get()
        // Save frame data and plaintext password to separate localStorage keys first
        for (const p of projects) {
          saveFrameToStorage(p.id, p.config.frame)
          // Save plaintext password to separate key BEFORE replacing with marker
          if (p.config.sessionPassword && p.config.sessionPassword !== '__PASSWORD_SET__') {
            savePasswordPlainToStorage(p.id, p.config.sessionPassword)
          }
        }
        // Save lightweight metadata (no base64 photos, frame in separate keys)
        // so admin gallery shows entries after reload (just without thumbnails)
        // Also strip session password — use marker instead of plaintext
        const safeProjects = projects.map(p => ({
          ...p,
          photoHistory: p.photoHistory.map(h => ({ ...h, photos: [] })),
          config: {
            ...p.config,
            frame: p.config.frame ? '__FRAME_SAVED__' : null,
            sessionPassword: p.config.sessionPassword ? '__PASSWORD_SET__' : undefined,
          },
        }))
        localStorage.setItem('saatiril_projects', JSON.stringify(safeProjects))
        // Update lastSavedAt timestamp
        set({ lastSavedAt: Date.now() })
        console.log('[SAATIRIL] Projects saved to localStorage (debounced)')
      } catch (e) {
        console.error('Failed to save projects to storage', e)
      }
    }, SAVE_DEBOUNCE_MS)
  },

   saveProjectsToStorageNow: () => {
    // IMMEDIATE save — use before navigation to prevent data loss
    if (saveTimeout) { clearTimeout(saveTimeout); saveTimeout = null }
    try {
      const { projects } = get()
      // Save frame data and plaintext password to separate localStorage keys first
      for (const p of projects) {
        saveFrameToStorage(p.id, p.config.frame)
        // Save plaintext password to separate key BEFORE replacing with marker
        if (p.config.sessionPassword && p.config.sessionPassword !== '__PASSWORD_SET__') {
          savePasswordPlainToStorage(p.id, p.config.sessionPassword)
        }
      }
      // Save lightweight metadata (no base64 photos, frame in separate keys)
      // Also strip session password — use marker instead of plaintext
      const safeProjects = projects.map(p => ({
        ...p,
        photoHistory: p.photoHistory.map(h => ({ ...h, photos: [] })),
        config: {
          ...p.config,
          frame: p.config.frame ? '__FRAME_SAVED__' : null,
          sessionPassword: p.config.sessionPassword ? '__PASSWORD_SET__' : undefined,
        },
      }))
      localStorage.setItem('saatiril_projects', JSON.stringify(safeProjects))
      // Update lastSavedAt timestamp
      set({ lastSavedAt: Date.now() })
      console.log('[SAATIRIL] Projects saved to localStorage (immediate)')
    } catch (e) {
      console.error('Failed to save projects to storage (immediate)', e)
    }
  },

  updateStudentStatus: (studentId, status) => {
    set((s) => {
      if (!s.currentProject) return {}
      const newDb = s.currentProject.database.map(st =>
        st.id === studentId ? { ...st, status } : st
      )
      const updatedProject = { ...s.currentProject, database: newDb }
      const idx = s.projects.findIndex(p => p.id === updatedProject.id)
      const newProjects = [...s.projects]
      if (idx !== -1) newProjects[idx] = updatedProject
      return { currentProject: updatedProject, projects: newProjects }
    })
    // ── AUTO-SAVE: Persist student status changes to localStorage ──
    get().saveProjectsToStorage()
  },
}))
