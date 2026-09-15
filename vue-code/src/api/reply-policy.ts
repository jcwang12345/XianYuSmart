import { request } from '@/utils/request'

export interface ReplyPolicySimulation {
  simulationId: number
  accountId: number
  goodsId: string
  buyerUserId?: string
  sessionId?: string
  message: string
  requestId: string
  selectedStrategy: string
  selectedRuleId?: number
  knowledgeVersionId?: number
  knowledgeVersionNo?: number
  safetyVerdict: string
  handoffReasonCode?: string
  answerPreview?: string
  decisionTrace: Array<{ step: string; outcome: string; reason: string }>
  productionWouldSend: boolean | number
  platformWrite: false | 0
  testConsoleWouldSend: false
  aiNetworkCalls: false
  idempotentReplay: boolean
  dataNotice: string
  createdUsername?: string
  createdTime: string
}

export function simulateReplyPolicy(data: {
  accountId: number
  goodsId: string
  buyerUserId?: string
  sessionId?: string
  message: string
  requestId: string
}) {
  return request<ReplyPolicySimulation>({ url: '/reply-policy/simulate', method: 'POST', data })
}
