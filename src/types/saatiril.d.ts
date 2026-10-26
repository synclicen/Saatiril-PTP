// Global type declarations for SAATIRIL

interface LicenseStatusResult {
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
}

interface LicenseActivationResult {
  success: boolean
  error?: string
  licenseType?: string
}

interface MachineIdResult {
  machineId: string
  displayMachineId: string
}

interface GenerateLicenseCodeResult {
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
}

interface SaatirilAPI {
  isElectron: boolean
  isAndroid?: boolean
  platform?: string
  connectToServer?: (url: string) => void
  backToLogin?: () => void
  selectFolder: (defaultPath: string) => Promise<string | null>
  createFolder: (path: string) => Promise<{ success: boolean; path?: string; error?: string }>
  savePhoto: (data: { base64Data: string; filename: string; targetFolder: string; backupFolder?: string | null }) => Promise<string | null>
  countPhotos: (data: { targetFolder: string }) => Promise<number>
  // BUG #7 (audit-electron-side): lazy thumbnail rehydration after restart.
  readPhoto: (data: { filename: string; targetFolder: string }) => Promise<string | null>
  getLanInfo: () => Promise<{
    httpPort: number
    socketPort: number
    ips: { name: string; address: string }[]
  }>
  // License API (only available in Electron)
  getLicenseStatus: () => Promise<LicenseStatusResult>
  activateLicense: (activationCode: string) => Promise<LicenseActivationResult>
  getMachineId: () => Promise<MachineIdResult>
  generateLicenseCode: (machineId: string, adminKey: string) => Promise<GenerateLicenseCodeResult>
  // Google Drive / Cloud Backup API
  selectBackupFolder: () => Promise<string | null>
  getBackupFolder: () => Promise<string | null>
  clearBackupFolder: () => Promise<boolean>
  getBackupStats: () => Promise<{ connected: boolean; totalFiles: number }>
}

declare global {
  interface Window {
    saatirilAPI: SaatirilAPI
  }
}

export {}
