import { getAuthToken } from '@/utils/request'

// AI 对话请求
export interface ChatWithAIReq {
  msg: string
  goodsId: string
}

// 上传资料到 RAG 请求
export interface PutNewDataReq {
  content: string
  goodsId: string
}

// 查询 RAG 资料响应
export interface RAGDataItem {
  documentId: string
  goodsID: string
  content: string
  createTime: string
}

/** 构建带Token的headers */
function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json'
  }
  const token = getAuthToken()
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  return headers
}

// AI 对话 (SSE 流式)
// 后端 AIChatController 的 @RequestMapping 是 "/ai"（无 /api 前缀），
// 与其他控制器 @RequestMapping("/api/xxx") 不同，
// 所以不能用 request()（baseURL=/api），需用 fetch 直接请求
export function chatWithAI(data: ChatWithAIReq): Promise<Response> {
  return fetch('/ai/chat', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

// AI 对话测试（与自动回复流程一致）
export function chatTestWithAI(data: { accountId: number; goodsId: string; msg: string }): Promise<Response> {
  return fetch('/ai/chatTest', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

// 上传资料到 RAG 知识库
export function putNewDataToRAG(data: PutNewDataReq): Promise<Response> {
  return fetch('/ai/putNewData', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

// 查询 RAG 知识库资料
export function queryRAGData(data: { goodsId: string }): Promise<Response> {
  return fetch('/ai/queryRAGData', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

// 删除 RAG 知识库资料
export function deleteRAGData(data: { documentId: string }): Promise<Response> {
  return fetch('/ai/deleteRAGData', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

// AI 状态信息
export interface AIStatus {
  enabled: boolean
  available: boolean
  apiKeyConfigured: boolean
  message: string
  baseUrl: string
  model: string
  provider: string
  protocol: string
  endpoint: string
}

// 获取 AI 状态
export function getAIStatus(): Promise<Response> {
  return fetch('/ai/status', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({})
  })
}

export interface AIConnectionTestRequest {
  provider: string
  protocol: 'openai' | 'anthropic'
  customName: string
  apiKey: string
  baseUrl: string
  model: string
  message: string
}

export interface AIConnectionTestResult {
  success: boolean
  reply: string
  latencyMs: number
  provider: string
  protocol: string
  model: string
  endpoint: string
  message: string
}

/** 使用当前表单配置测试AI连接，不保存配置。 */
export function testAIConnection(data: AIConnectionTestRequest): Promise<Response> {
  return fetch('/api/setting/ai/test', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

export type KnowledgeVersionStatus = 'DRAFT' | 'ACTIVE' | 'SUPERSEDED' | 'EXPIRED'

export interface GoodsKnowledgeVersion {
  id: number
  accountId: number
  goodsId: string
  versionNo: number
  content?: string
  status: KnowledgeVersionStatus
  effectiveStatus?: 'EFFECTIVE' | 'NOT_YET_EFFECTIVE' | KnowledgeVersionStatus
  sourceType: 'MANUAL' | 'GOODS_DETAIL' | 'LEGACY_IMPORT'
  effectiveTime: string
  expiresTime?: string | null
  activatedTime?: string | null
  invalidatedTime?: string | null
  createdUsername?: string | null
  createdTime: string
  idempotentReplay?: boolean
}

export interface GoodsKnowledgeView {
  fixedMaterial?: string | null
  activeVersionId?: number | null
  activeVersionNo?: number | null
  effectiveTime?: string | null
  expiresTime?: string | null
  status: 'EFFECTIVE' | 'NO_EFFECTIVE_VERSION'
  versions: GoodsKnowledgeVersion[]
  dataNotice: string
}

// 保存不可变商品知识版本；activate=false 时只保存草稿。
export function saveFixedMaterial(data: {
  accountId: number
  goodsId: string
  fixedMaterial: string
  effectiveTime?: string | null
  expiresTime?: string | null
  activate: boolean
  requestId: string
}): Promise<Response> {
  return fetch('/ai/saveFixedMaterial', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

// 获取固定资料
export function getFixedMaterial(data: { accountId: number; goodsId: string }): Promise<Response> {
  return fetch('/ai/getFixedMaterial', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

export function activateFixedMaterialVersion(data: { versionId: number; requestId: string }): Promise<Response> {
  return fetch('/ai/activateFixedMaterialVersion', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

export function expireFixedMaterialVersion(data: { versionId: number; requestId: string }): Promise<Response> {
  return fetch('/ai/expireFixedMaterialVersion', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}

// 同步商品详情并生成一个新的有效知识版本。
export function syncDetailToFixedMaterial(data: {
  accountId: number
  goodsId: string
  expiresTime?: string | null
  requestId: string
}): Promise<Response> {
  return fetch('/ai/syncDetailToFixedMaterial', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(data)
  })
}
