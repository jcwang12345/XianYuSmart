import assert from 'node:assert/strict'
import test from 'node:test'
import { isOfficialItemVerificationUrl } from '../official-verification.ts'

test('accepts only the two official item challenge paths', () => {
  assert.equal(isOfficialItemVerificationUrl('https://h5api.m.goofish.com/_____tmd_____/punish?token=opaque'), true)
  assert.equal(isOfficialItemVerificationUrl('https://h5api.m.goofish.com/h5/mtop.taobao.idle.pc.detail/1.0/_____tmd_____/punish?token=opaque'), true)
})

test('rejects off-site, credentialed and unrelated challenge URLs', () => {
  for (const url of [
    'http://h5api.m.goofish.com/_____tmd_____/punish',
    'https://evil.example/_____tmd_____/punish',
    'https://h5api.m.goofish.com.evil.example/_____tmd_____/punish',
    'https://user:pass@h5api.m.goofish.com/_____tmd_____/punish',
    'https://h5api.m.goofish.com/other',
    'https://h5api.m.goofish.com/_____tmd_____/punish#fragment',
    'not a URL',
  ]) assert.equal(isOfficialItemVerificationUrl(url), false, url)
})
