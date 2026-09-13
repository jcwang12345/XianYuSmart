import { request } from '@/utils/request'

export interface AccountHealth {
  accountId: number
  accountNote?: string
  unb?: string
  accountStatus: number
  cookieStatus?: number
  credentialExpireTime?: string
  pendingDeliveryCount: number
  failedDeliveryCount: number
  failedReplyCount: number
  todayOrderCount: number
  message24hCount: number
  openIssueCount: number
  websocketConnected: boolean
  riskState: string
  riskReason?: string
  attentionLevel: 'HEALTHY' | 'WARNING' | 'CRITICAL'
  lastMessageTime?: string
  lastOrderTime?: string
}

export interface OperationalIssue {
  id: number
  issueType: string
  severity: 'INFO' | 'WARNING' | 'CRITICAL'
  status: 'OPEN' | 'CLAIMED' | 'IN_PROGRESS' | 'RESOLVED' | 'IGNORED'
  accountId?: number
  accountNote?: string
  sourceType?: string
  sourceId?: string
  title: string
  description?: string
  resolutionNote?: string
  assignedUserId?: number
  assignedUsername?: string
  dueTime?: string
  occurrenceCount: number
  lastOccurredTime: string
}

export interface AccountCapability {
  id: number
  accountId: number
  accountNote?: string
  capabilityCode: string
  capabilityName: string
  status: 'READY' | 'DEGRADED' | 'REQUIRES_PLATFORM_PERMISSION' | 'NOT_IMPLEMENTED' | 'UNKNOWN'
  source: 'LOCAL_PROBE' | 'MANUAL'
  detail?: string
  checkedTime: string
}

export interface ConversationAssignment {
  id: number
  accountId: number
  accountNote?: string
  sessionId: string
  buyerUserId?: string
  status: 'OPEN' | 'IN_PROGRESS' | 'CLOSED'
  priority: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
  assignedUsername?: string
  firstMessageTime?: string
  firstResponseTime?: string
  lastMessageTime?: string
  slaDueTime?: string
  slaBreached: number
  note?: string
}

export const getCommandCenterAccounts = () => request<AccountHealth[]>({
  url: '/command-center/accounts', method: 'GET'
})

export const getOperationalIssues = (params: { status?: string; severity?: string; accountId?: number } = {}) =>
  request<OperationalIssue[]>({ url: '/command-center/issues', method: 'GET', params })

export const transitionOperationalIssue = (id: number, status: string, note?: string) =>
  request<void>({ url: `/command-center/issues/${id}/transition`, method: 'POST', data: { status, note } })

export const getAccountCapabilities = () => request<AccountCapability[]>({
  url: '/command-center/capabilities', method: 'GET'
})

export const probeAccountCapabilities = () => request<number>({
  url: '/command-center/capabilities/probe', method: 'POST'
})

export const getConversationAssignments = (status = 'OPEN') => request<ConversationAssignment[]>({
  url: '/command-center/conversations', method: 'GET', params: { status }
})

export const updateConversationAssignment = (id: number, data: { status?: string; priority?: string; claim?: boolean; note?: string }) =>
  request<void>({ url: `/command-center/conversations/${id}`, method: 'POST', data })
