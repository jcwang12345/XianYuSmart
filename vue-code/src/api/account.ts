import { request } from '@/utils/request'
import type { ApiResponse, Account } from '@/types'

type AccountWire = Omit<Account, 'id'> & { id: number | string }

/**
 * 后端为避免浏览器丢失 Long 精度，会把 ID 序列化成字符串。账号主键在本系统
 * 必须是 JavaScript 安全整数，因此统一在 API 边界转换，避免页面用严格比较时
 * 把深链账号误判为不存在并回退到第一家店铺。
 */
export function normalizeAccountId(value: number | string): number {
  const id = Number(value)
  if (!Number.isSafeInteger(id) || id <= 0) {
    throw new Error('账号列表包含无效 ID，请刷新后重试')
  }
  return id
}

// 获取账号列表
export async function getAccountList(): Promise<ApiResponse<{ accounts: Account[]; total?: number }>> {
  const response = await request<{ accounts: AccountWire[]; total?: number }>({
    url: '/account/list',
    method: 'POST',
    data: {}
  })
  if (response.data) {
    response.data = {
      ...response.data,
      accounts: (response.data.accounts || []).map(account => ({
        ...account,
        id: normalizeAccountId(account.id)
      }))
    }
  }
  return response as ApiResponse<{ accounts: Account[]; total?: number }>
}

// 添加账号
export function addAccount(data: Partial<Account>) {
  return request({
    url: '/account/add',
    method: 'POST',
    data
  })
}

export interface AccountLocalProfile {
  accountId: number
  accountNote?: string
  groupIds: number[]
  operatingTags: string[]
  lifecycleState: 'ACTIVE' | 'DISABLED' | 'ARCHIVED'
  rowVersion: number
  autoReplyAllowed: boolean
  autoDeliveryAllowed: boolean
  canEdit: boolean
  canManageLifecycle: boolean
  canDelete: boolean
  runtimeConnection: 'CONNECTED' | 'DISCONNECTED' | 'UNKNOWN'
  verificationState: 'VERIFICATION_REQUIRED' | 'UNKNOWN'
  platformStatus?: number
  archivedFromState?: 'ACTIVE' | 'DISABLED' | null
}
export interface AccountLocalCommand {
  accountId: number
  requestId: string
  expectedVersion: number
  accountNote?: string
  groupIds?: number[]
  operatingTags?: string[]
  autoReplyAllowed?: boolean
  autoDeliveryAllowed?: boolean
}
export interface AccountCommandResult {
  commandId: string | number
  requestId: string
  replayed: boolean
  rowVersion?: number | string
  changedFields?: string[]
  localResult: 'APPLIED' | 'NO_CHANGE' | 'DELETED'
  connectionOutcome?: string
  profile?: AccountLocalProfile
}
export interface DeletePreflight {
  accountId: number
  canDelete: boolean
  rowVersion: number | string
  confirmationLabel: string
  archivePath: string
  dependencies: Array<{ dependency: string; label: string; path: string; count: number | string }>
  recommendation: string
}
function normalizeLocalProfile(profile: AccountLocalProfile): AccountLocalProfile {
  const version = Number(profile.rowVersion)
  if (!Number.isSafeInteger(version) || version < 0) throw new Error('账号版本无效，请重新加载')
  return { ...profile, accountId: normalizeAccountId(profile.accountId), rowVersion: version,
    groupIds: (profile.groupIds || []).map(normalizeAccountId) }
}
export async function getAccountLifecycle(accountId: number) {
  const response = await request<AccountLocalProfile>({ url: '/account/lifecycle', method: 'GET', params: { accountId } })
  if (response.data) response.data = normalizeLocalProfile(response.data)
  return response
}
export async function updateAccount(data: AccountLocalCommand) {
  const response = await request<AccountCommandResult>({ url: '/account/update', method: 'POST', data })
  if (response.data?.profile) response.data.profile = normalizeLocalProfile(response.data.profile)
  return response
}
export async function changeAccountLifecycle(data: Pick<AccountLocalCommand, 'accountId' | 'requestId' | 'expectedVersion'> & { action: 'DISABLE' | 'ENABLE' | 'ARCHIVE' | 'RESTORE' }) {
  const response = await request<AccountCommandResult>({ url: '/account/lifecycle', method: 'POST', data })
  if (response.data?.profile) response.data.profile = normalizeLocalProfile(response.data.profile)
  return response
}
export function getAccountDeletePreflight(accountId: number) {
  return request<DeletePreflight>({ url: '/account/delete-preflight', method: 'GET', params: { accountId } })
}
export function deleteAccount(data: { id: number; confirmationText: string; confirmationNote: string; requestId: string; expectedVersion: number }) {
  return request<AccountCommandResult>({ url: '/account/delete', method: 'POST', data: { accountId: data.id,
    confirmationText: data.confirmationText, confirmationNote: data.confirmationNote,
    requestId: data.requestId, expectedVersion: data.expectedVersion } })
}

// 手动添加账号
export function manualAddAccount(data: { accountNote: string; cookie: string }) {
  return request({
    url: '/account/manualAdd',
    method: 'POST',
    data
  })
}
