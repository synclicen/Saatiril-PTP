/**
 * SAATIRIL — Electron Preload Script
 *
 * Exposes safe IPC methods to the renderer process via window.saatirilAPI.
 * This runs in a sandboxed context with contextIsolation enabled.
 */

import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('saatirilAPI', {
  isElectron: true,

  selectFolder: (defaultPath: string): Promise<string | null> => {
    return ipcRenderer.invoke('select-folder', defaultPath)
  },

  createFolder: (folderPath: string): Promise<{ success: boolean; path?: string; error?: string }> => {
    return ipcRenderer.invoke('create-folder', folderPath)
  },

  savePhoto: (data: { base64Data: string; filename: string; targetFolder: string; backupFolder?: string | null; projectName?: string | null }): Promise<string | null> => {
    return ipcRenderer.invoke('save-photo', data)
  },
  countPhotos: (data: { targetFolder: string }): Promise<number> => {
    return ipcRenderer.invoke('count-photos', data)
  },

  // BUG #7 (audit-electron-side): read a photo from disk → base64 data URL.
  // Used by the admin gallery to lazily rehydrate thumbnails after an app
  // restart (saveProjectsToStorage strips photo base64 from photoHistory to
  // stay under localStorage quotas). Returns null if the file is missing.
  readPhoto: (data: { filename: string; targetFolder: string }): Promise<string | null> => {
    return ipcRenderer.invoke('read-photo', data)
  },

  getLanInfo: (): Promise<{
    httpPort: number
    socketPort: number
    ips: { name: string; address: string }[]
  }> => {
    return ipcRenderer.invoke('get-lan-info')
  },

  // ── Live Mirror projector window ──────────────────────────────────────────
  // Opens a separate BrowserWindow on the external display (HDMI) showing
  // ONLY the /mirror page. Returns success=false if no external display is
  // detected (user needs to connect projector + Windows+P → Extend).
  openMirrorOnProjector: (channel: number): Promise<{
    success: boolean
    reason: string
    displays: number
  }> => {
    return ipcRenderer.invoke('open-mirror-on-projector', channel)
  },

  closeMirrorProjector: (): Promise<{ success: boolean; reason?: string }> => {
    return ipcRenderer.invoke('close-mirror-projector')
  },

  // ── Mirror frame IPC fast path ───────────────────────────────────────────
  // sendMirrorFrame(blob): sends a JPEG Blob directly to the mirror
  // projector BrowserWindow via IPC (structured clone — no base64, no
  // JSON, no socket.io). Much faster than the socket.io path. Called by
  // the operator panel at 10+ FPS.
  sendMirrorFrame: (blob: Blob): void => {
    ipcRenderer.send('send-mirror-frame', blob)
  },

  // onMirrorFrame(callback): registers a listener for 'mirror-frame' IPC
  // events. Called by the mirror.html page to receive frames directly
  // from the operator (bypassing socket.io). Returns a cleanup function
  // to remove the listener.
  onMirrorFrame: (callback: (blob: Blob) => void): (() => void) => {
    const handler = (_event: unknown, blob: Blob): void => {
      callback(blob)
    }
    ipcRenderer.on('mirror-frame', handler)
    return () => { ipcRenderer.removeListener('mirror-frame', handler) }
  },

  // isMirrorWindowOpen(): checks if the projector BrowserWindow is
  // currently open. The operator uses this to decide whether to use the
  // IPC fast path (window open → IPC) or socket.io slow path (closed →
  // for cross-device streaming).
  isMirrorWindowOpen: (): Promise<boolean> => {
    return ipcRenderer.invoke('is-mirror-window-open')
  },

  // ── License API ──────────────────────────────────────────────────────────
  getLicenseStatus: (): Promise<{
    isValid: boolean
    isGracePeriod: boolean
    isExpired: boolean
    daysRemaining: number
    graceDaysRemaining: number
    licenseType: string | null
    expiresAt: string | null
    machineId: string
    displayMachineId: string
    firstRunDate: string | null
  }> => {
    return ipcRenderer.invoke('get-license-status')
  },

  activateLicense: (activationCode: string): Promise<{
    success: boolean
    error?: string
    licenseType?: string
  }> => {
    return ipcRenderer.invoke('activate-license', activationCode)
  },

  getMachineId: (): Promise<{
    machineId: string
    displayMachineId: string
  }> => {
    return ipcRenderer.invoke('get-machine-id')
  },

  // ── Generate License Code (admin/developer) ────────────────────────────
  generateLicenseCode: (machineId: string, adminKey: string): Promise<{
    success: boolean
    error?: string
    data?: {
      machineId: string
      displayMachineId: string
      licenseType: string
      activationCode: string
      expiresAt: string
      expiresAtFormatted: string
      daysRemaining: number
      verified: boolean
    }
  }> => {
    return ipcRenderer.invoke('generate-license-code', machineId, adminKey)
  },

  // ── Release Info (GitHub Releases) ────────────────────────────────────
  // Uses main-process fetch (Node.js) to avoid CORS issues in Electron
  getReleaseInfo: (): Promise<{
    apk: { available: boolean; sizeMB?: string; assetName?: string; lastModified?: string; downloadUrl?: string; error?: string }
    portable: { available: boolean; sizeMB?: string; assetName?: string; lastModified?: string; downloadUrl?: string; error?: string }
  }> => {
    return ipcRenderer.invoke('get-release-info')
  },

  // ── Google Drive / Cloud Backup ───────────────────────────────────────
  // Admin picks a folder (Google Drive for Desktop, OneDrive, Dropbox, etc.)
  // Photos are copied there after saving locally. Cloud app handles sync.
  selectBackupFolder: (): Promise<string | null> => {
    return ipcRenderer.invoke('select-backup-folder')
  },

  getBackupFolder: (): Promise<string | null> => {
    return ipcRenderer.invoke('get-backup-folder')
  },

  clearBackupFolder: (): Promise<boolean> => {
    return ipcRenderer.invoke('clear-backup-folder')
  },

  getBackupStats: (): Promise<{ connected: boolean; totalFiles: number }> => {
    return ipcRenderer.invoke('get-backup-stats')
  },

  // ── Open URL in default browser (Chrome/Edge) ────────────────────────
  // Used for Web Bluetooth — Electron doesn't support navigator.bluetooth,
  // so we open /admin-ble in the user's default browser which DOES support it.
  openInBrowser: (url: string): Promise<boolean> => {
    return ipcRenderer.invoke('open-in-browser', url)
  },
})
