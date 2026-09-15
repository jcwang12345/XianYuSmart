import { computed, ref } from 'vue'

export type AsyncResourcePhase =
  | 'idle'
  | 'loading'
  | 'refreshing'
  | 'success'
  | 'empty'
  | 'error'
  | 'forbidden'

export interface AsyncResourceResult<T> {
  applied: boolean
  value?: T
  error?: unknown
}

interface AsyncResourceOptions<T> {
  isEmpty?: (value: T) => boolean
  errorMessage?: string
}

const statusOf = (error: any) => Number(error?.response?.status || error?.status || error?.response?.data?.code)

/**
 * 主入口统一异步状态：首载与刷新分离、刷新保留上次成功数据，并丢弃过时响应。
 * 页面只有收到最新服务端成功结果后，才可以进入 success/empty。
 */
export function useAsyncResourceState() {
  const phase = ref<AsyncResourcePhase>('idle')
  const hasSuccessfulData = ref(false)
  const errorMessage = ref('')
  let revision = 0

  const busy = computed(() => phase.value === 'loading' || phase.value === 'refreshing')
  const firstLoading = computed(() => phase.value === 'idle' || phase.value === 'loading')
  const refreshing = computed(() => phase.value === 'refreshing')
  const blockingFailure = computed(() =>
    !hasSuccessfulData.value && (phase.value === 'error' || phase.value === 'forbidden'))

  const execute = async <T>(task: () => Promise<T>, options: AsyncResourceOptions<T> = {}): Promise<AsyncResourceResult<T>> => {
    const requestRevision = ++revision
    phase.value = hasSuccessfulData.value ? 'refreshing' : 'loading'
    errorMessage.value = ''
    try {
      const value = await task()
      if (requestRevision !== revision) return { applied: false, value }
      hasSuccessfulData.value = true
      phase.value = options.isEmpty?.(value) ? 'empty' : 'success'
      return { applied: true, value }
    } catch (error: any) {
      if (requestRevision !== revision) return { applied: false, error }
      phase.value = statusOf(error) === 403 ? 'forbidden' : 'error'
      errorMessage.value = statusOf(error) === 403
        ? '当前角色没有读取该范围的权限。'
        : options.errorMessage || error?.message || '读取失败，请重试。'
      return { applied: true, error }
    }
  }

  const invalidate = () => { revision += 1 }

  return {
    phase,
    hasSuccessfulData,
    errorMessage,
    busy,
    firstLoading,
    refreshing,
    blockingFailure,
    execute,
    invalidate
  }
}
