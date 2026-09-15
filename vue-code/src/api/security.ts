import { request } from '@/utils/request'

export interface LoginSession {
  id: number
  device?: string
  loginIp?: string
  createdTime: string
  expireTime: string
}

const requestId = (prefix: string) => `${prefix}-${crypto.randomUUID()}`
export const getTwoFactorStatus = () => request<{ enabled: boolean; recoveryCodeCount: number }>({ url: '/security/2fa/status', method: 'GET' })
export const beginTwoFactor = () => request<{ secret: string; uri: string; qrCode: string }>({ url: '/security/2fa/begin', method: 'POST', data: { requestId: requestId('two-factor-begin') } })
export const confirmTwoFactor = (code: string) => request<string[]>({ url: '/security/2fa/confirm', method: 'POST', data: { code, requestId: requestId('two-factor-confirm') } })
export const disableTwoFactor = (code: string) => request<void>({ url: '/security/2fa/disable', method: 'POST', data: { code, requestId: requestId('two-factor-disable') } })
export const getLoginSessions = () => request<LoginSession[]>({ url: '/security/sessions', method: 'GET' })
export const revokeLoginSession = (id: number) => request<void>({ url: `/security/sessions/${id}/revoke`, method: 'POST' })
