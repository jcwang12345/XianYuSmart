import assert from 'node:assert/strict'
import test from 'node:test'
import { resolveLoginFailure } from '../login-challenge.ts'

test('enters challenge only from the structured TOTP_REQUIRED code', () => {
  const structured = resolveLoginFailure('TOTP_REQUIRED', '任意本地化文案', false)
  assert.equal(structured.totpRequired, true)
  assert.equal(structured.focusTotp, true)

  const localizedOnly = resolveLoginFailure(undefined, '请输入两步验证码或恢复码', false)
  assert.equal(localizedOnly.totpRequired, false)
})

test('invalid second factor preserves challenge and password but clears the code', () => {
  const state = resolveLoginFailure('TOTP_INVALID', '', true)
  assert.equal(state.totpRequired, true)
  assert.equal(state.clearTotp, true)
  assert.equal(state.clearPassword, false)
  assert.equal(state.focusTotp, true)
})

test('credential and login rate failures cannot retain a stale challenge', () => {
  const invalid = resolveLoginFailure('INVALID_CREDENTIALS', '', true)
  assert.equal(invalid.totpRequired, false)
  assert.equal(invalid.clearPassword, true)

  const limited = resolveLoginFailure('LOGIN_RATE_LIMITED', '', true)
  assert.equal(limited.totpRequired, false)
  assert.equal(limited.clearTotp, true)
})

test('TOTP rate limit keeps the verified-password stage without encouraging retries', () => {
  const state = resolveLoginFailure('TOTP_RATE_LIMITED', '', true)
  assert.equal(state.totpRequired, true)
  assert.equal(state.focusTotp, false)
  assert.match(state.message, /稍后重试/)
})
