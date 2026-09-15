import { request } from '@/utils/request'

export interface BackupModule {
  moduleKey: string
  moduleName: string
  dependencies: string[]
  recordCount: number | null
  estimatedSizeBytes: number | null
  scope: string
  containsSensitiveSecrets: boolean
}

export interface BackupExportResult {
  jsonData: string
}

export interface BackupImportResult {
  totalCount: number
  successCount: number
  failedModules: string[]
}

export interface BackupRestoreModulePreview {
  moduleKey: string
  moduleName: string
  dependencies: string[]
  incomingCount: number
  currentCount: number
  potentialCreateCount: number
  potentialOverwriteCount: number
  incomingSizeBytes: number
  estimated: boolean
}

export interface BackupManifest {
  formatVersion: string
  applicationVersion: string
  schemaVersion: string
  tenantId: number
  exportedAt: string
  exportedBy?: number
  encrypted: boolean
  containsSensitiveSecrets: boolean
  payloadChecksum: string
  modules: Array<Record<string, unknown>>
}

export interface BackupRestorePreview {
  jobId: number
  status?: string
  executable?: boolean
  writePerformed: boolean
  previewToken: string
  requiredConfirmation: string
  expiresAt: string
  scope?: string
  modules?: BackupRestoreModulePreview[]
  warnings?: string[]
  manifest: BackupManifest
  preview?: {
    executable: boolean
    writePerformed: boolean
    scope: string
    modules: BackupRestoreModulePreview[]
    warnings: string[]
  }
  idempotentReplay?: boolean
}

export interface BackupRestoreJob extends BackupRestorePreview {
  status: 'PREVIEWED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'ROLLED_BACK'
  selectedModules: string[]
  startedAt?: string
  finishedAt?: string
  rolledBackAt?: string
  errorMessage?: string
  restorePoints?: Array<{
    moduleKey: string
    moduleName: string
    recordCount: number
    snapshotChecksum: string
    createdTime: string
  }>
}

export function getBackupModules() {
  return request<BackupModule[]>({
    url: '/backup/modules',
    method: 'post',
    data: {}
  })
}

export function exportBackup(data: { modules: string[] }) {
  return request<BackupExportResult>({
    url: '/backup/export',
    method: 'post',
    data
  })
}

export function previewBackupRestore(data: { jsonData: string; modules: string[]; requestId: string }) {
  return request<BackupRestorePreview>({
    url: '/backup/restore/preview',
    method: 'post',
    data
  })
}

export function executeBackupRestore(data: {
  jsonData: string
  modules: string[]
  requestId: string
  previewToken: string
  confirmationText: string
}) {
  return request<BackupRestoreJob>({
    url: '/backup/restore/execute',
    method: 'post',
    data
  })
}

export function getBackupRestoreJob(jobId: number) {
  return request<BackupRestoreJob>({
    url: `/backup/restore/jobs/${jobId}`,
    method: 'get'
  })
}

export function rollbackBackupRestore(jobId: number, data: { requestId: string; confirmationText: string }) {
  return request<BackupRestoreJob>({
    url: `/backup/restore/jobs/${jobId}/rollback`,
    method: 'post',
    data
  })
}

export function getLogDates() {
  return request<string[]>({
    url: '/backup/log-dates',
    method: 'get'
  })
}

export function downloadLog(date: string): Promise<Blob> {
  const token = localStorage.getItem('xianyu_auth_token')
  return fetch(`/api/backup/log-download?date=${encodeURIComponent(date)}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  }).then(res => {
    if (!res.ok) throw new Error('下载失败')
    return res.blob()
  })
}
