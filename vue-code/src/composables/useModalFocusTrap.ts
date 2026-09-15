import { nextTick, onBeforeUnmount, onMounted, watch, type Ref } from 'vue'

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

type BackgroundState = {
  element: HTMLElement
  inert: boolean
  ariaHidden: string | null
}

/** 为自定义弹窗补齐聚焦、背景隔离、Tab 循环、Esc 关闭和焦点恢复。 */
export function useModalFocusTrap(
  open: Ref<boolean>,
  modal: Ref<HTMLElement | null> | (() => HTMLElement | null),
  onRequestClose?: () => void,
) {
  let opener: HTMLElement | null = null
  let background: BackgroundState[] = []

  const modalElement = () => typeof modal === 'function' ? modal() : modal.value
  const focusable = () => Array.from(modalElement()?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [])
    .filter(element => element.offsetParent !== null && element.getAttribute('aria-hidden') !== 'true')

  const restoreBackground = () => {
    for (const state of background) {
      if (state.inert) state.element.setAttribute('inert', '')
      else state.element.removeAttribute('inert')
      if (state.ariaHidden === null) state.element.removeAttribute('aria-hidden')
      else state.element.setAttribute('aria-hidden', state.ariaHidden)
    }
    background = []
  }

  const activate = async () => {
    opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    const element = modalElement()
    if (element) {
      element.setAttribute('role', element.getAttribute('role') || 'dialog')
      element.setAttribute('aria-modal', 'true')
      if (!element.hasAttribute('aria-label') && !element.hasAttribute('aria-labelledby')) {
        const title = element.querySelector('h1, h2, h3')?.textContent?.trim()
        if (title) element.setAttribute('aria-label', title)
      }
    }
    const overlay = element?.parentElement
    const root = overlay?.parentElement
    if (overlay && root) {
      background = Array.from(root.children)
        .filter((node): node is HTMLElement => node instanceof HTMLElement && node !== overlay)
        .map(element => ({
          element,
          inert: element.hasAttribute('inert'),
          ariaHidden: element.getAttribute('aria-hidden'),
        }))
      for (const state of background) {
        state.element.setAttribute('inert', '')
        state.element.setAttribute('aria-hidden', 'true')
      }
    }
    const targets = focusable()
    const preferred = element?.querySelector<HTMLElement>('[autofocus], [data-autofocus], input:not([type="hidden"]):not([disabled]), select:not([disabled]), textarea:not([disabled])')
    ;(preferred ?? targets[0] ?? element)?.focus()
  }

  const deactivate = async () => {
    restoreBackground()
    await nextTick()
    opener?.focus()
    opener = null
  }

  const handleKeydown = (event: KeyboardEvent) => {
    if (!open.value) return
    if (event.key === 'Escape') {
      event.preventDefault()
      if (onRequestClose) onRequestClose()
      else open.value = false
      return
    }
    if (event.key !== 'Tab') return
    const targets = focusable()
    if (!targets.length) {
      event.preventDefault()
      modalElement()?.focus()
      return
    }
    const first = targets[0]!
    const last = targets[targets.length - 1]!
    const active = document.activeElement
    if (!modalElement()?.contains(active)) {
      event.preventDefault()
      first.focus()
    } else if (event.shiftKey && active === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && active === last) {
      event.preventDefault()
      first.focus()
    }
  }

  watch(open, value => value ? void activate() : void deactivate(), { flush: 'post' })
  onMounted(() => document.addEventListener('keydown', handleKeydown, true))
  onBeforeUnmount(() => {
    document.removeEventListener('keydown', handleKeydown, true)
    restoreBackground()
  })
}
