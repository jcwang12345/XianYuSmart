import axios, { type AxiosInstance, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { toast } from './toast'
import type { ApiResponse } from '@/types'

export interface RequestConfig extends AxiosRequestConfig {
  silent?: boolean
}

// Token存储key
const TOKEN_KEY = 'xianyu_auth_token'
const REFRESH_TOKEN_KEY = 'xianyu_auth_refresh_token'
const USERNAME_KEY = 'xianyu_auth_username'

/** 获取Token */
export function getAuthToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

/** 设置Token */
export function setAuthToken(token: string, username: string, refreshToken?: string) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USERNAME_KEY, username)
  if (refreshToken) localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

/** 清除Token */
export function clearAuthToken() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  localStorage.removeItem(USERNAME_KEY)
}

/** 获取用户名 */
export function getAuthUsername(): string | null {
  return localStorage.getItem(USERNAME_KEY)
}

/** 是否已登录 */
export function isLoggedIn(): boolean {
  return !!getAuthToken()
}

// 创建 axios 实例
const service: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

type RetryableConfig = RequestConfig & { _retry?: boolean }
let refreshPromise: Promise<string> | null = null

async function refreshAccessToken(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) throw new Error('缺少续期凭据')
  if (!refreshPromise) {
    refreshPromise = axios.post<ApiResponse<{
      token: string
      refreshToken: string
      username: string
    }>>('/api/login/refresh', { refreshToken }, { timeout: 15000 })
      .then(({ data }) => {
        if ((data.code !== 0 && data.code !== 200) || !data.data?.token || !data.data.refreshToken) {
          throw new Error(data.msg || '会话续期失败')
        }
        setAuthToken(data.data.token, data.data.username || getAuthUsername() || '', data.data.refreshToken)
        return data.data.token
      })
      .finally(() => { refreshPromise = null })
  }
  return refreshPromise
}

async function retryAfterRefresh(config: RetryableConfig) {
  if (config._retry || config.url?.includes('/login/refresh')) throw new Error('登录已过期，请重新登录')
  config._retry = true
  const token = await refreshAccessToken()
  config.headers = config.headers || {}
  config.headers.Authorization = `Bearer ${token}`
  return service.request(config)
}

function redirectToLogin(message: string) {
  clearAuthToken()
  if (!window.location.pathname.includes('/login')) {
    toast.error(message)
    window.location.href = '/login'
  }
}

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    // 添加Token到请求头
    const token = getAuthToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截器
service.interceptors.response.use(
  async (response: AxiosResponse<ApiResponse<any>>) => {
    const res = response.data

    // 401未登录 -> 跳转登录页
    if (res.code === 401) {
      try {
        return await retryAfterRefresh(response.config as RetryableConfig)
      } catch {
        redirectToLogin(res.msg || '登录已过期，请重新登录')
        return Promise.reject(new Error(res.msg || '未登录'))
      }
    }

    // 特殊处理：1001是滑块验证码，需要业务代码自己处理，不在这里拦截
    if (res.code === 1001) {
      return response // 直接返回，让业务代码处理
    }

    // 如果响应码不是 0 或 200，认为是错误
    if (res.code !== 0 && res.code !== 200) {
      const errorMsg = res.msg || res.message || '请求失败'
      // 后台轮询和可降级查询由业务页面自行呈现，避免打断当前操作。
      if (!(response.config as RequestConfig).silent) toast.error(errorMsg)
      const error = new Error(errorMsg)
      // 标记这个错误已经显示过消息，避免重复提示
      ;(error as any).messageShown = true
      return Promise.reject(error)
    }

    return response // 保持返回完整的 AxiosResponse
  },
  async (error) => {
    if (error.response?.status === 401) {
      try {
        return await retryAfterRefresh(error.config as RetryableConfig)
      } catch {
        redirectToLogin(error.response?.data?.msg || '登录已过期，请重新登录')
        return Promise.reject(error)
      }
    }
    // 只有在错误消息未显示过时才弹出提示
    if (!(error as any).messageShown && !(error.config as RequestConfig | undefined)?.silent) {
      toast.error(error.message || '网络请求失败')
    }
    return Promise.reject(error)
  }
)

// 封装请求方法
export function request<T = any>(config: RequestConfig): Promise<ApiResponse<T>> {
  return service.request<ApiResponse<T>>(config).then(response => response.data)
}

export default service
