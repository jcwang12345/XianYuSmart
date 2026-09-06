import { toast } from './toast'
import { showConfirm } from './confirm'

export const showSuccess = toast.success
export const showError = toast.error
export const showWarning = toast.warning
export const showInfo = toast.info
export { showConfirm }

export function formatTime(timestamp: number | string | Date): string {
  if (!timestamp) return '-'
  if (typeof timestamp === 'string') {
    if (/^\d{4}-\d{2}-\d{2}/.test(timestamp)) {
      return timestamp.replace('T', ' ').substring(0, 19)
    }
    const num = Number(timestamp)
    if (!isNaN(num)) {
      const date = new Date(num)
      if (!isNaN(date.getTime())) {
        return date.toLocaleString('zh-CN', {
          year: 'numeric', month: '2-digit', day: '2-digit',
          hour: '2-digit', minute: '2-digit', second: '2-digit'
        })
      }
    }
    return '-'
  }
  const date = new Date(timestamp)
  if (isNaN(date.getTime())) return '-'
  return date.toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  })
}

export function formatPrice(price: string | number): string {
  if (!price) return '¥0.00'
  const num = typeof price === 'string' ? parseFloat(price) : price
  return `¥${num.toFixed(2)}`
}

export const GOODS_STATUS = {
  PLATFORM_OFF_SHELF: -98,
  REVIEWING: -9,
  DELETED: -1,
  ON_SALE: 0,
  OFF_SHELF: 1,
  SOLD: 2
} as const

type GoodsStatusType = 'success' | 'warning' | 'info' | 'danger'

export interface GoodsStatusInfo {
  text: string
  type: GoodsStatusType
  className: string
}

const goodsStatusMap: Record<number, GoodsStatusInfo> = {
  [GOODS_STATUS.PLATFORM_OFF_SHELF]: { text: '平台下架', type: 'danger', className: 'platform-off-shelf' },
  [GOODS_STATUS.REVIEWING]: { text: '审核中', type: 'warning', className: 'reviewing' },
  [GOODS_STATUS.DELETED]: { text: '已删除', type: 'danger', className: 'deleted' },
  [GOODS_STATUS.ON_SALE]: { text: '在售', type: 'success', className: 'on-sale' },
  [GOODS_STATUS.OFF_SHELF]: { text: '已下架', type: 'info', className: 'off-shelf' },
  [GOODS_STATUS.SOLD]: { text: '已售出', type: 'warning', className: 'sold' }
}

export const GOODS_STATUS_OPTIONS = [
  { value: GOODS_STATUS.ON_SALE, label: '在售' },
  { value: GOODS_STATUS.REVIEWING, label: '审核中' },
  { value: GOODS_STATUS.OFF_SHELF, label: '已下架' },
  { value: GOODS_STATUS.PLATFORM_OFF_SHELF, label: '平台下架' },
  { value: GOODS_STATUS.SOLD, label: '已售出' },
  { value: GOODS_STATUS.DELETED, label: '已删除' }
] as const

export function getGoodsStatusText(status: number | null | undefined): GoodsStatusInfo {
  if (status !== null && status !== undefined && goodsStatusMap[status]) return goodsStatusMap[status]
  return {
    text: `未知状态（${status ?? '-'}）`,
    type: 'info',
    className: 'unknown'
  }
}

export function getGoodsStatusClass(status: number | null | undefined): string {
  return getGoodsStatusText(status).className
}

export function isGoodsOnSale(status: number | null | undefined): boolean {
  return status === GOODS_STATUS.ON_SALE
}

export function canToggleGoodsListingStatus(status: number | null | undefined): boolean {
  return status === GOODS_STATUS.ON_SALE || status === GOODS_STATUS.OFF_SHELF
}

export function getAccountStatusText(status: number): { text: string; type: string } {
  const statusMap: Record<number, { text: string; type: string }> = {
    1: { text: '正常', type: 'success' },
    '-1': { text: '需要验证', type: 'warning' }
  }
  return statusMap[status] || { text: '未知', type: 'info' }
}

export function debounce<T extends (...args: any[]) => any>(
  func: T,
  wait: number
): (...args: Parameters<T>) => void {
  let timeout: ReturnType<typeof setTimeout> | null = null
  return function (this: any, ...args: Parameters<T>) {
    if (timeout) clearTimeout(timeout)
    timeout = setTimeout(() => {
      func.apply(this, args)
    }, wait)
  }
}

export function throttle<T extends (...args: any[]) => any>(
  func: T,
  wait: number
): (...args: Parameters<T>) => void {
  let timeout: ReturnType<typeof setTimeout> | null = null
  return function (this: any, ...args: Parameters<T>) {
    if (!timeout) {
      timeout = setTimeout(() => {
        timeout = null
        func.apply(this, args)
      }, wait)
    }
  }
}
