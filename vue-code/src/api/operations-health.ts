import { request } from '@/utils/request'

export interface HealthCheck {
  key: string
  name: string
  count: number
  status: 'HEALTHY' | 'WARNING'
  action: string
}

export interface HealthOverview {
  overallStatus: 'HEALTHY' | 'WARNING' | 'CRITICAL'
  criticalCount: number
  warningCount: number
  checks: HealthCheck[]
}

export interface OperationException {
  exceptionType: string
  exceptionId: number
  exceptionVersion: number
  accountId?: number
  targetId?: string
  title: string
  reason: string
  status: string
  occurredAt: string
}

export interface NotificationChannel {
  id: number
  channelName: string
  channelType: NotificationChannelType
  webhookUrl?: string
  endpointConfigured: boolean
  secretConfigured: boolean
  config: Record<string, string>
  messageTemplate?: string
  eventTypes: string[]
  scopeType: 'ALL' | 'GROUPS' | 'ACCOUNTS'
  scopeIds: number[]
  enabled: boolean
  lastSuccessTime?: string
  lastErrorMessage?: string
  updateTime?: string
}

export type NotificationChannelType =
  'WEBHOOK' | 'WECHAT_WORK' | 'DINGTALK' | 'FEISHU' | 'BARK' | 'PUSHPLUS' | 'TELEGRAM'

export interface NotificationLog {
  id: number
  channelId?: number
  eventId?: string
  outboxId?: number
  eventType: string
  xianyuAccountId?: number
  title: string
  sendStatus: number
  deliveryStatus?: 'SENT' | 'FAILED' | 'UNKNOWN'
  httpStatus?: number
  errorMessage?: string
  createTime: string
}

export const getHealthOverview = () => request<HealthOverview>({
  url: '/diagnostics/overview',
  method: 'GET'
})

export const getOperationExceptions = () => request<OperationException[]>({
  url: '/diagnostics/exceptions',
  method: 'GET'
})

export const acknowledgeOperationException = (data: Pick<OperationException, 'exceptionType' | 'exceptionId' | 'exceptionVersion'>) =>
  request<void>({
    url: '/diagnostics/exceptions/acknowledge',
    method: 'POST',
    data: {
      exceptionType: data.exceptionType,
      exceptionId: data.exceptionId,
      exceptionVersion: data.exceptionVersion
    }
  })

export const acknowledgeAllOperationExceptions = (items: OperationException[]) => request<number>({
  url: '/diagnostics/exceptions/acknowledge-all',
  method: 'POST',
  data: items.map(item => ({
    exceptionType: item.exceptionType,
    exceptionId: item.exceptionId,
    exceptionVersion: item.exceptionVersion
  }))
})

export const getNotificationChannels = () => request<NotificationChannel[]>({
  url: '/notifications/channels',
  method: 'GET'
})

export const saveNotificationChannel = (data: {
  id?: number
  channelName: string
  channelType: NotificationChannelType
  config: Record<string, string>
  messageTemplate: string
  eventTypes: string[]
  scopeType: 'ALL' | 'GROUPS' | 'ACCOUNTS'
  scopeIds: number[]
  enabled: boolean
  requestId: string
}) => request<NotificationChannel>({
  url: '/notifications/channels',
  method: 'POST',
  data
})

export const deleteNotificationChannel = (id: number, requestId: string) => request<void>({
  url: `/notifications/channels/${id}`,
  method: 'DELETE',
  params: { requestId }
})

export interface InboxNotification {
  id: number; eventId: string; eventType: string; accountId?: number; accountName?: string;
  severity: string; title: string; contentSummary: string; targetRoute: string;
  readTime?: string; handlingStatus: string; handlingNote?: string; occurredTime: string;
  deliveryTotal: number; deliverySent: number; deliveryFailed: number;
  deliveryStatus: 'NOT_CONFIGURED' | 'PENDING' | 'SENT' | 'FAILED' | 'PARTIAL' | 'RETRYING_OR_FAILED'
}

export const getNotificationInbox = (params: Record<string, unknown> = {}) => request<{ records: InboxNotification[]; total: number; page: number; pageSize: number; totalPages: number }>({ url: '/notifications/inbox', method: 'GET', params })
export const markNotificationRead = (id: number) => request<void>({ url: `/notifications/inbox/${id}/read`, method: 'POST' })
export const updateNotificationHandling = (id: number, status: string, note = '') => request<void>({ url: `/notifications/inbox/${id}/handling`, method: 'POST', data: { status, note } })

export const testNotificationChannel = (id: number) => request<{ httpStatus: number; message: string }>({
  url: `/notifications/channels/${id}/test`,
  method: 'POST'
})

export const getNotificationLogs = () => request<NotificationLog[]>({
  url: '/notifications/logs',
  method: 'GET'
})
