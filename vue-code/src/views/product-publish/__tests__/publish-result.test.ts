import assert from 'node:assert/strict'
import test from 'node:test'
import { isConfirmedPublish, publishResultLabel } from '../publish-result.ts'

test('confirmed platform write is not mislabeled as failure when local sync or readback is pending', () => {
  const result = { valid: true, outcomeState: 'PARTIAL', verificationStatus: 'PENDING',
    platform: { platformWrite: 'CONFIRMED', itemId: '1087819954917', localSynced: true } }
  assert.equal(isConfirmedPublish(result), true)
  assert.equal(publishResultLabel(result), '闲鱼已发布，字段待核对')
  assert.equal(publishResultLabel({ ...result, platform: { ...result.platform, localSynced: false } }), '闲鱼已发布，本地同步待修复')
  assert.equal(isConfirmedPublish({ ...result, valid: undefined, status: 2 }), true)
})

test('unknown, failed, unconfirmed and isolated mock results cannot claim a platform publication', () => {
  const confirmed = { valid: true, outcomeState: 'PLATFORM_CONFIRMED',
    platform: { platformWrite: 'CONFIRMED', itemId: '1087819954917' } }
  assert.equal(publishResultLabel(confirmed), '闲鱼已确认发布')
  for (const result of [
    { ...confirmed, platform: { ...confirmed.platform, itemId: '' } },
    { ...confirmed, platform: { ...confirmed.platform, platformWrite: 'NOT_PERFORMED' } },
    { ...confirmed, outcomeState: 'UNKNOWN' },
    { ...confirmed, valid: false, status: 4 }
  ]) assert.equal(isConfirmedPublish(result), false)
  assert.equal(publishResultLabel({ outcomeState: 'UNKNOWN', valid: false }), '发布结果未知，请先查询')
  assert.equal(publishResultLabel({ valid: true, platform: { executionChannel: 'QA_MOCK' } }), '隔离任务完成')
})
