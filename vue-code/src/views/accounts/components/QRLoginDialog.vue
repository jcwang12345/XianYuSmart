<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { generateQRCode, getQRCodeStatus } from '@/api/qrlogin'
import { showSuccess, showError } from '@/utils'
import type { QRLoginSession } from '@/types'

interface Props {
  modelValue: boolean
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const isMobile = ref(false)
const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768
}

const qrCodeUrl = ref('')
const sessionId = ref('')
const status = ref<QRLoginSession['status']>('pending')
const statusText = ref('正在生成二维码...')
const generatedAt = ref<number | null>(null)
const expiresAt = ref<number | null>(null)
const now = ref(Date.now())
const generating = ref(false)
let pollTimer: number | null = null
let pollRequestPending = false
let countdownTimer: number | null = null

const normalizeEpoch = (value: unknown) => {
  const epoch = Number(value)
  return Number.isFinite(epoch) && epoch > 0 ? epoch : null
}
const remainingSeconds = computed(() => Math.max(0, Math.ceil(((expiresAt.value || 0) - now.value) / 1000)))
const remainingText = computed(() => {
  if (!expiresAt.value) return '有效时间读取中'
  const minutes = Math.floor(remainingSeconds.value / 60)
  const seconds = remainingSeconds.value % 60
  return remainingSeconds.value > 0
    ? `剩余 ${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
    : '本轮二维码已过期'
})
const formatMoment = (value: number | null) => value
  ? new Date(value).toLocaleString('zh-CN', { hour12: false })
  : '读取中'
const canRegenerate = computed(() => !generating.value && status.value !== 'confirmed' && status.value !== 'scanned')

watch(() => props.modelValue, (newVal) => {
  if (newVal) {
    checkScreenSize()
    window.addEventListener('resize', checkScreenSize)
    startCountdown()
    generateQR()
  } else {
    window.removeEventListener('resize', checkScreenSize)
    stopPolling()
    stopCountdown()
  }
})

const generateQR = async () => {
  stopPolling()
  qrCodeUrl.value = ''
  sessionId.value = ''
  generatedAt.value = null
  expiresAt.value = null
  status.value = 'pending'
  statusText.value = '正在生成二维码...'
  generating.value = true
  try {
    const response = await generateQRCode()
    if (response.code === 0 || response.code === 200) {
      qrCodeUrl.value = response.data?.qrCodeUrl || ''
      sessionId.value = response.data?.sessionId || ''
      generatedAt.value = normalizeEpoch(response.data?.generatedAt) || Date.now()
      expiresAt.value = normalizeEpoch(response.data?.expiresAt)
      now.value = Date.now()
      startPolling()
    } else {
      throw new Error(response.msg || '生成二维码失败')
    }
  } catch (error: any) {
    console.error('生成二维码失败:', error)
    status.value = 'error'
    statusText.value = error?.message || '生成二维码失败，请重试'
    showError(statusText.value)
  } finally {
    generating.value = false
  }
}

const startCountdown = () => {
  stopCountdown()
  now.value = Date.now()
  countdownTimer = window.setInterval(() => {
    now.value = Date.now()
    if (expiresAt.value && now.value >= expiresAt.value && status.value !== 'confirmed') {
      status.value = 'expired'
      statusText.value = '二维码已过期，请重新生成'
      stopPolling()
    }
  }, 1000)
}

const stopCountdown = () => {
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
}

const startPolling = () => {
  if (!sessionId.value) return
  pollTimer = window.setInterval(async () => {
    if (!sessionId.value || pollRequestPending) return
    pollRequestPending = true
    try {
      const response = await getQRCodeStatus(sessionId.value)
      if (response.code === 0 || response.code === 200) {
        const data = response.data
        status.value = data?.status || 'pending'
        
        switch (data?.status) {
          case 'pending':
            statusText.value = '等待扫码...'
            break
          case 'scanned':
            statusText.value = '已扫码，等待确认...'
            break
          case 'confirmed':
            statusText.value = '登录成功！正在获取信息...'
            stopPolling()
            await handleLoginSuccess()
            break
          case 'expired':
            statusText.value = '二维码已过期'
            stopPolling()
            break
          case 'error':
          case 'cancelled':
          case 'verification_required':
            statusText.value = data?.message || '登录失败，请重试'
            showError(statusText.value)
            stopPolling()
            break
        }
      }
    } catch (error) {
      console.error('检查登录状态失败:', error)
    } finally {
      pollRequestPending = false
    }
  }, 2000)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const handleLoginSuccess = async () => {
  // 后端已在确认扫码时原子化保存账号；Cookie 不再经过浏览器。
  showSuccess('账号添加成功')
  emit('success')
  handleClose()
}

const handleClose = () => {
  stopPolling()
  stopCountdown()
  emit('update:modelValue', false)
}
</script>

<template>
  <!-- iOS 风格弹窗背景 -->
  <transition name="ios-sheet-fade">
    <div v-if="modelValue" class="ios-sheet-overlay" @click="handleClose"></div>
  </transition>

  <!-- iOS 风格 Sheet 弹窗 -->
  <transition name="ios-sheet-slide">
    <div v-if="modelValue" class="ios-sheet">
      <!-- 弹窗头部 -->
      <div class="ios-sheet-header">
        <div class="ios-sheet-handle"></div>
        <div class="ios-sheet-title">扫码添加闲鱼账号</div>
        <button class="ios-sheet-close" @click="handleClose">✕</button>
      </div>

      <!-- 弹窗内容 -->
      <div class="ios-sheet-content">
        <div class="account-mode-card">
          <strong>新增闲鱼账号</strong>
          <span>扫码后自动读取闲鱼账号 ID；已有账号续期请从“连接管理 → 扫码更新”进入。</span>
        </div>
        <div class="qr-code-container">
          <img v-if="qrCodeUrl" :src="qrCodeUrl" alt="二维码" class="qr-code" />
          <div v-else class="qr-skeleton">
            <div class="skeleton-line"></div>
          </div>
        </div>
        
        <p class="qr-tip">请使用闲鱼APP扫描二维码登录</p>
        
        <div class="qr-status">
          <div class="status-badge" :class="`status-${status}`">
            {{ statusText }}
          </div>
        </div>
        <p class="qr-expiry" :class="{ 'qr-expiry--expired': remainingSeconds === 0 && !!expiresAt }">{{ remainingText }}</p>
        <dl class="qr-time-facts">
          <div><dt>生成时间</dt><dd>{{ formatMoment(generatedAt) }}</dd></div>
          <div><dt>本地失效时间</dt><dd>{{ formatMoment(expiresAt) }}</dd></div>
        </dl>
        <p class="qr-validity-note">本地最长保留 15 分钟；闲鱼平台可能提前使二维码失效，请以最新一张为准。</p>
        <p v-if="sessionId" class="session-id">会话ID: {{ sessionId }}</p>
      </div>

      <!-- 弹窗底部按钮 -->
      <div class="ios-sheet-footer">
        <button class="ios-sheet-btn ios-sheet-btn--cancel" @click="handleClose">
          取消
        </button>
        <button v-if="canRegenerate" class="ios-sheet-btn ios-sheet-btn--primary" @click="generateQR">
          {{ qrCodeUrl ? '刷新二维码' : '重新生成二维码' }}
        </button>
      </div>
    </div>
  </transition>
</template>

<style scoped>
/* iOS 弹窗动画 */
.ios-sheet-fade-enter-active,
.ios-sheet-fade-leave-active {
  transition: opacity 0.3s ease;
}

.ios-sheet-fade-enter-from,
.ios-sheet-fade-leave-to {
  opacity: 0;
}

.ios-sheet-slide-enter-active,
.ios-sheet-slide-leave-active {
  transition: all 0.3s cubic-bezier(0.25, 0.1, 0.25, 1);
}

.ios-sheet-slide-enter-from,
.ios-sheet-slide-leave-to {
  transform: scale(0.95);
  opacity: 0;
}

/* iOS Sheet 背景遮罩 */
.ios-sheet-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0,0,0,0.20);
  z-index: 999;
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

/* iOS Sheet 容器 */
.ios-sheet {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  z-index: 1000;
  background: rgba(255,255,255,0.72);
  backdrop-filter: blur(40px) saturate(2);
  -webkit-backdrop-filter: blur(40px) saturate(2);
  border: 1px solid rgba(255,255,255,0.75);
  border-radius: 20px;
  box-shadow: 0 16px 48px rgba(0,0,0,0.16), 0 2px 8px rgba(0,0,0,0.08);
  display: flex;
  flex-direction: column;
  max-height: 85vh;
  width: 340px;
  max-width: 90vw;
  overflow: hidden;
}

/* iOS Sheet 头部 */
.ios-sheet-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px 16px 12px;
  border-bottom: 0.5px solid rgba(60,60,67,.12);
  position: relative;
}

.ios-sheet-handle {
  display: none;
}

.ios-sheet-title {
  font-size: 17px;
  font-weight: 600;
  color: #1c1c1e;
  text-align: center;
}

.ios-sheet-close {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 32px;
  height: 32px;
  border: none;
  background: rgba(0, 0, 0, 0.06);
  border-radius: 50%;
  font-size: 18px;
  color: #1c1c1e;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
  -webkit-tap-highlight-color: transparent;
}

.ios-sheet-close:active {
  background: rgba(0, 0, 0, 0.12);
}

/* iOS Sheet 内容 */
.ios-sheet-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px 16px;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.ios-sheet-content::-webkit-scrollbar {
  display: none;
}

.account-mode-card { display: grid; gap: 4px; padding: 10px 12px; border: 1px solid rgba(203,152,0,.24); border-radius: 10px; background: rgba(255,214,10,.09); }
.account-mode-card strong { color: #1c1c1e; font-size: 13px; }
.account-mode-card span { color: rgba(28,28,30,.6); font-size: 11px; line-height: 1.5; }

.qr-code-container {
  display: flex;
  justify-content: center;
  margin: 16px 0;
}

.qr-code {
  width: 200px;
  height: 200px;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.06);
}

.qr-skeleton {
  width: 200px;
  height: 200px;
  background: rgba(0, 0, 0, 0.06);
  border-radius: 12px;
  animation: skeleton-loading 1.5s infinite;
}

@keyframes skeleton-loading {
  0%, 100% {
    background: rgba(0, 0, 0, 0.06);
  }
  50% {
    background: rgba(0, 0, 0, 0.1);
  }
}

.skeleton-line {
  width: 100%;
  height: 100%;
  background: inherit;
}

.qr-tip {
  margin: 16px 0 12px;
  color: rgba(28,28,30,.55);
  font-size: 14px;
  text-align: center;
  line-height: 1.5;
}

.qr-status {
  display: flex;
  justify-content: center;
  margin: 12px 0;
}

.status-badge {
  padding: 8px 16px;
  border-radius: 20px;
  font-size: 13px;
  font-weight: 500;
  text-align: center;
  min-width: 120px;
}

.status-pending,
.status-scanned {
  background: rgba(10,132,255,.15);
  color: #0A84FF;
}

.status-confirmed {
  background: rgba(48,209,88,.2);
  color: #30D158;
}

.status-expired {
  background: rgba(255,69,58,.15);
  color: #FF453A;
}

.qr-expiry { margin: 4px 0 0; color: #8a6400; font-size: 13px; font-weight: 700; text-align: center; }
.qr-expiry--expired { color: #c9342f; }
.qr-time-facts { display: grid; gap: 5px; margin: 10px 0 0; padding: 9px 11px; border-radius: 9px; background: rgba(255,255,255,.5); }
.qr-time-facts > div { display: flex; justify-content: space-between; gap: 12px; }
.qr-time-facts dt { color: rgba(28,28,30,.55); font-size: 11px; }
.qr-time-facts dd { margin: 0; color: #1c1c1e; font-size: 11px; text-align: right; }
.qr-validity-note { margin: 6px 0 0; color: rgba(28,28,30,.55); font-size: 11px; line-height: 1.5; text-align: center; }

.session-id {
  margin: 12px 0;
  font-size: 12px;
  color: rgba(28,28,30,.55);
  text-align: center;
}

/* iOS Sheet 底部 */
.ios-sheet-footer {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  padding: 12px 16px;
  border-top: 0.5px solid rgba(60,60,67,.12);
  background: transparent;
}
.ios-sheet-footer > :only-child { grid-column: 1 / -1; }

.ios-sheet-btn {
  width: 100%;
  height: 48px;
  border: none;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  -webkit-tap-highlight-color: transparent;
}

.ios-sheet-btn--cancel {
  background: rgba(255,255,255,0.38);
  border: 1px solid rgba(255,255,255,0.35);
  color: #1c1c1e;
}

.ios-sheet-btn--cancel:active {
  background: rgba(255,255,255,0.55);
}

.ios-sheet-btn--primary { color: #1c1c1e; background: #ffd60a; }
.ios-sheet-btn--primary:active { background: #f1c400; }

/* 手机端适配 */
@media screen and (max-width: 768px) {
  .ios-sheet {
    width: 320px;
    max-height: 85vh;
    max-width: 95vw;
  }

  .ios-sheet-header {
    padding: 14px 16px 12px;
  }

  .ios-sheet-title {
    font-size: 16px;
  }

  .ios-sheet-close {
    width: 30px;
    height: 30px;
    font-size: 16px;
  }

  .ios-sheet-content {
    padding: 16px 12px;
  }

  .qr-code {
    width: 160px;
    height: 160px;
  }

  .qr-skeleton {
    width: 160px;
    height: 160px;
  }

  .qr-tip {
    font-size: 13px;
    margin: 12px 0 10px;
  }

  .status-badge {
    font-size: 12px;
    padding: 6px 14px;
  }

  .session-id {
    font-size: 11px;
  }

  .ios-sheet-footer {
    padding: 10px 12px;
  }

  .ios-sheet-btn {
    height: 44px;
    font-size: 15px;
  }
}

@media screen and (max-width: 480px) {
  .ios-sheet {
    width: 300px;
    max-height: 80vh;
    max-width: 92vw;
  }

  .qr-code {
    width: 140px;
    height: 140px;
  }

  .qr-skeleton {
    width: 140px;
    height: 140px;
  }

  .qr-tip {
    font-size: 12px;
  }

  .ios-sheet-btn {
    height: 42px;
    font-size: 14px;
  }
}
</style>
