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

// 更新账号
export function updateAccount(data: Partial<Account>) {
  return request({
    url: '/account/update',
    method: 'POST',
    data
  })
}

// 删除账号
export function deleteAccount(data: { id: number }) {
  return request({
    url: '/account/delete',
    method: 'POST',
    data: {
      accountId: data.id
    }
  })
}

// 手动添加账号
export function manualAddAccount(data: { accountNote: string; cookie: string }) {
  return request({
    url: '/account/manualAdd',
    method: 'POST',
    data
  })
}

