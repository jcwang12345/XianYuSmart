import assert from 'node:assert/strict'
import test from 'node:test'
import { loginReturnPath } from '../login-return.ts'

test('preserves exact refund notification target and query through login', () => {
  const path = '/orders?accountId=1&orderId=5127744744122083625&detailTab=refund'
  assert.equal(loginReturnPath(path), path)
  assert.equal(loginReturnPath('/orders?orderId=QA-B07-a%20b#refund'), '/orders?orderId=QA-B07-a%20b#refund')
})
test('rejects external, protocol-relative, backslash, control, and login-loop redirects', () => {
  for (const path of [undefined, [], 'https://evil.invalid', '//evil.invalid', '/\\evil.invalid', '/orders\n', '/login?redirect=/orders']) {
    assert.equal(loginReturnPath(path), '/dashboard')
  }
})
