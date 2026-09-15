import { request } from '@/utils/request';
import type { ApiResponse } from '@/types';

// 消息信息
export interface ChatMessage {
  id: number;
  xianyuAccountId: number;
  lwp: string;
  pnmId: string;
  sid: string;
  contentType: number;
  msgContent: string;
  senderUserName: string;
  senderUserId: string;
  senderAppV: string;
  senderOsType: string;
  reminderUrl: string;
  xyGoodsId: string;
  completeMsg: string;
  messageTime: string | number;
  createTime: string;
  isNew?: boolean;
}

// 消息列表响应
export interface MessageListResponse {
  list: ChatMessage[];
  totalCount: number;
  totalPage: number;
  pageNum: number;
  pageSize: number;
}

export interface ConversationProfile {
  sid: string;
  avatar: string;
  nick: string;
}

// 获取消息列表
export function getMessageList(data: {
  xianyuAccountId: number;
  xyGoodsId?: string;
  pageNum?: number;
  pageSize?: number;
  filterCurrentAccount?: boolean; // 过滤当前账号消息
}, silent = false) {
  return request<MessageListResponse>({
    url: '/msg/list',
    method: 'POST',
    data,
    silent
  });
}

// 根据会话ID获取上下文消息
export function getContextMessages(data: {
  xianyuAccountId: number;
  sid: string;
  limit?: number;
  offset?: number;
}) {
  return request<ChatMessage[]>({
    url: '/msg/context',
    method: 'POST',
    data: {
      xianyuAccountId: data.xianyuAccountId,
      sid: data.sid,
      limit: data.limit || 20,
      offset: data.offset || 0
    }
  });
}

export function syncContextMessages(data: {
  xianyuAccountId: number;
  sid: string;
  maxMessages?: number;
}, silent = false) {
  return request<{ received: number; saved: number }>({
    url: '/msg/context/sync',
    method: 'POST',
    data,
    silent
  });
}

export function getConversationProfiles(data: {
  xianyuAccountId: number;
  sessionIds: string[];
}, silent = false) {
  return request<ConversationProfile[]>({
    url: '/msg/conversation-profiles',
    method: 'POST',
    data,
    silent
  });
}

// 发送消息
export function sendMessage(data: {
  xianyuAccountId: number;
  cid: string;
  toId: string;
  text: string;
  xyGoodsId?: string;
}) {
  return request<string>({
    url: '/websocket/sendMessage',
    method: 'POST',
    data
  });
}

export interface WorkspaceConversation {
  accountId: number
  accountName: string
  sessionId: string
  buyerUserId: string
  buyerName?: string
  unreadCount: number
  status: string
  priority?: string
  pinned?: boolean
  keywordFlag?: string
  customerNote?: string
  customerBlacklisted?: boolean
  manualTakeoverState?: string
  manualTakeoverUntil?: string
  autoReplyState?: string
  handoffStatus?: string
  handoffReasonCode?: string
  handoffTaskId?: number
  handoffCreatedTime?: string
  historyCoverageStatus?: string
  lastMessage?: string
  relatedOrderId?: string
  relatedGoodsId?: string
  slaBreached?: boolean
}

export function getWorkspaceConversations(params: Record<string, unknown> = {}) {
  return request<{ records: WorkspaceConversation[]; returnedCount: number; unreadInResult: number; dataNotice: string }>({
    url: '/message-workspace/conversations', method: 'GET', params
  })
}

export function getWorkspaceConversation(accountId: number, sessionId: string, limit = 100, offset = 0) {
  return request<Record<string, any>>({
    url: '/message-workspace/conversation', method: 'GET', params: { accountId, sessionId, limit, offset }, silent: true
  })
}

export function markWorkspaceConversationRead(accountId: number, sessionId: string) {
  return request<void>({ url: '/message-workspace/conversation/read', method: 'POST', data: { accountId, sessionId } })
}

export function takeoverWorkspaceConversation(accountId: number, sessionId: string, goodsId?: string, minutes = 15) {
  return request<Record<string, any>>({ url: '/message-workspace/conversation/takeover', method: 'POST', data: { accountId, sessionId, goodsId, minutes } })
}

export function updateWorkspaceConversation(data: {
  accountId: number; sessionId: string; pinned?: boolean; keywordFlag?: string;
  customerNote?: string; blacklisted?: boolean; requestId: string
}) {
  return request<Record<string, any>>({ url: '/message-workspace/conversation/update', method: 'POST', data })
}

export interface WorkspaceSendCommand {
  accountId: number
  sessionId: string
  recipientUserId: string
  goodsId?: string
  content: string
  width?: number
  height?: number
  requestId: string
}

export interface MessageSendAttempt {
  accountId: number
  sessionId: string
  recipientUserId?: string
  goodsId?: string
  contentType: 'TEXT' | 'IMAGE' | string
  contentExcerpt?: string
  requestId: string
  attemptToken?: string
  eventId?: string
  outcomeState: 'SENT' | 'UNKNOWN' | 'FAILED' | 'MANUAL_CONFIRMED_SENT' | 'CONFIRMED_NOT_SENT' | string
  platformAckCode?: string
  platformReceipt?: Record<string, unknown>
  platformReceiptJson?: string
  errorMessage?: string
  resolutionStatus?: 'PENDING_VERIFICATION' | 'CONFIRMED_SENT' | 'CONFIRMED_NOT_SENT' | string
  resolutionNote?: string
  verifiedMessageId?: string
  resolutionRequestId?: string
  resolvedUsername?: string
  resolvedTime?: string
  operatorUsername?: string
  createdTime?: string
  updatedTime?: string
  canResolve?: boolean
}

export interface WorkspaceConversationDetail {
  accountId: number
  sessionId: string
  messages: Record<string, unknown>[]
  relatedOrders: Record<string, unknown>[]
  sendAttempts: MessageSendAttempt[]
  replyDecisions: Record<string, unknown>[]
  historyPage: { limit: number; offset: number }
}

export interface MessageResolutionCommand {
  accountId: number
  resolution: 'CONFIRMED_SENT' | 'CONFIRMED_NOT_SENT'
  note: string
  verifiedMessageId?: string
  requestId: string
}

export function sendWorkspaceText(data: WorkspaceSendCommand) {
  return request<{ requestId: string; outcomeState: 'SENT' | 'UNKNOWN' | 'FAILED'; attemptToken?: string; eventId?: string; resolutionStatus?: string; recoveryHint?: string }>({ url: '/message-workspace/send/text', method: 'POST', data })
}

export function sendWorkspaceImage(data: WorkspaceSendCommand) {
  return request<{ requestId: string; outcomeState: 'SENT' | 'UNKNOWN' | 'FAILED'; attemptToken?: string; eventId?: string; resolutionStatus?: string; recoveryHint?: string }>({ url: '/message-workspace/send/image', method: 'POST', data })
}

export function getMessageSendAttempt(accountId: number, requestId: string) {
  return request<MessageSendAttempt>({ url: `/message-workspace/send-attempts/${encodeURIComponent(requestId)}`, method: 'GET', params: { accountId } })
}

export function previewMessageResolution(sendRequestId: string, data: MessageResolutionCommand) {
  return request<MessageSendAttempt & { requestedResolution: string; willResend: false; stateChange: string; confirmation: string }>({
    url: `/message-workspace/send-attempts/${encodeURIComponent(sendRequestId)}/resolution/preview`, method: 'POST', data
  })
}

export function resolveMessageResolution(sendRequestId: string, data: MessageResolutionCommand) {
  return request<MessageSendAttempt & { idempotentReplay: boolean; platformWrite: false; recoveryHint?: string }>({
    url: `/message-workspace/send-attempts/${encodeURIComponent(sendRequestId)}/resolution`, method: 'POST', data
  })
}

export interface AiHandoffTask {
  id: number
  accountId: number
  accountName?: string
  sessionId: string
  goodsId?: string
  buyerUserId?: string
  sourceReplyRecordId?: number
  reasonCode: string
  reasonLabel: string
  reasonDetail?: string
  priority: 'URGENT' | 'HIGH' | 'NORMAL' | 'LOW'
  status: 'OPEN' | 'CLAIMED' | 'RESOLVED' | 'IGNORED'
  confidenceScore?: number
  modelName?: string
  requestId: string
  claimedBy?: number
  claimedUsername?: string
  claimedTime?: string
  resolvedUsername?: string
  resolvedTime?: string
  resolutionNote?: string
  createdTime: string
  updatedTime: string
}

export function getAiHandoffs(params: { status?: string; accountId?: number; search?: string; limit?: number } = {}) {
  return request<{ records: AiHandoffTask[]; returnedCount: number; dataNotice: string }>({
    url: '/message-workspace/handoffs', method: 'GET', params
  })
}

export function claimAiHandoff(id: number, requestId: string) {
  return request<AiHandoffTask>({
    url: `/message-workspace/handoffs/${id}/claim`, method: 'POST', data: { requestId }
  })
}

export function resolveAiHandoff(id: number, status: 'RESOLVED' | 'IGNORED', note: string, requestId: string) {
  return request<AiHandoffTask>({
    url: `/message-workspace/handoffs/${id}/resolve`, method: 'POST', data: { status, note, requestId }
  })
}
