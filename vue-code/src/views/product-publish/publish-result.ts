export type PublishResult = Record<string, any> | null

export const isConfirmedPublish = (result: PublishResult): boolean => {
  const completed = result?.valid === true || Number(result?.status) === 2
  return Boolean(completed && result?.platform?.platformWrite === 'CONFIRMED'
    && /^\d+$/.test(String(result?.platform?.itemId || ''))
    && ['PLATFORM_CONFIRMED', 'PLATFORM_CONFIRMED_LOCAL_PENDING', 'PARTIAL'].includes(String(result?.outcomeState || '')))
}

export const publishResultLabel = (result: PublishResult): string => {
  if (isConfirmedPublish(result)) {
    if (result?.platform?.localSynced === false) return '闲鱼已发布，本地同步待修复'
    if (result?.verificationStatus === 'PENDING') return '闲鱼已发布，字段待核对'
    return '闲鱼已确认发布'
  }
  if (result?.platform?.executionChannel === 'QA_MOCK' && result?.valid === true) return '隔离任务完成'
  if (String(result?.outcomeState || '').includes('UNKNOWN')) return '发布结果未知，请先查询'
  return '发布未成功'
}
