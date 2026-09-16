import assert from 'node:assert/strict'
import test from 'node:test'
import axios from 'axios'

class MemoryStorage {
  private values = new Map<string, string>()
  getItem(key: string) { return this.values.get(key) ?? null }
  setItem(key: string, value: string) { this.values.set(key, String(value)) }
  removeItem(key: string) { this.values.delete(key) }
  clear() { this.values.clear() }
}

test('password login never refreshes or redirects for business and HTTP 401', async () => {
  const storage = new MemoryStorage()
  storage.setItem('xianyu_auth_token', 'existing-access')
  storage.setItem('xianyu_auth_refresh_token', 'existing-refresh')
  Object.defineProperty(globalThis, 'localStorage', { value: storage, configurable: true })
  const location = { pathname: '/login', href: '/login' }
  Object.defineProperty(globalThis, 'window', { value: { location }, configurable: true })

  const { default: service, getApiErrorCode } = await import('../request.ts')
  const originalPost = axios.post
  let refreshCalls = 0
  ;(axios as any).post = async () => {
    refreshCalls += 1
    throw new Error('refresh must not run')
  }

  try {
    for (const transport of ['business', 'http'] as const) {
      service.defaults.adapter = async (config) => {
        const data = {
          code: 401,
          msg: '用户名或密码错误',
          errorCode: 'INVALID_CREDENTIALS'
        }
        if (transport === 'business') {
          return { data, status: 200, statusText: 'OK', headers: {}, config }
        }
        const error: any = new Error('Request failed with status code 401')
        error.config = config
        error.response = { data, status: 401, statusText: 'Unauthorized', headers: {}, config }
        throw error
      }

      const error = await service.request({
        url: '/login/login',
        method: 'post',
        data: { username: 'owner', password: 'not-logged' },
        silent: true,
        exposeErrorCode: true
      }).then(() => undefined, reason => reason)

      assert.equal(getApiErrorCode(error), 'INVALID_CREDENTIALS')
      assert.equal((error as any).businessCode, 401)
    }

    assert.equal(refreshCalls, 0)
    assert.equal(storage.getItem('xianyu_auth_token'), 'existing-access')
    assert.equal(storage.getItem('xianyu_auth_refresh_token'), 'existing-refresh')
    assert.equal(location.href, '/login')
  } finally {
    axios.post = originalPost
  }
})
