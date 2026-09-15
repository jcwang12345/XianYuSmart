<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { generateQRCode, getQRCodeStatus } from '@/api/qrlogin'
import { showSuccess, showError } from '@/utils'
import type { QRLoginSession } from '@/types'

interface Props {
  modelValue: boolean
  accountId: number
  accountDisplayId?: string
  accountRemark?: string
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

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

const remainingSeconds = computed(() => Math.max(0, Math.ceil(((expiresAt.value || 0) - now.value) / 1000)))
const remainingText = computed(() => {
  if (!expiresAt.value) return '有效时间读取中'
  const minutes = Math.floor(remainingSeconds.value / 60)
  const seconds = remainingSeconds.value % 60
  return remainingSeconds.value > 0
    ? `剩余 ${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
    : '本轮二维码已过期'
})
const canRegenerate = computed(() => !generating.value && status.value !== 'confirmed' && status.value !== 'scanned')
const formatMoment = (value: number | null) => value
  ? new Date(value).toLocaleString('zh-CN', { hour12: false })
  : '读取中'
const normalizeEpoch = (value: unknown) => {
  const epoch = Number(value)
  return Number.isFinite(epoch) && epoch > 0 ? epoch : null
}

watch(() => props.modelValue, (newVal) => {
  if (newVal) {
    startCountdown()
    generateQR()
  } else {
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
    const response = await generateQRCode(props.accountId)
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
  if (!sessionId.value) {
    showError('会话ID为空，无法查询状态')
    return
  }
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
            statusText.value = '登录成功！正在更新Cookie和Token...'
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
            statusText.value = data?.message || '更新失败，请重试'
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
  showSuccess('Cookie更新成功')
  emit('success')
  handleClose()
}

const handleClose = () => {
  stopPolling()
  stopCountdown()
  emit('update:modelValue', false)
}

onBeforeUnmount(() => {
  stopPolling()
  stopCountdown()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="modal-overlay" @click.self="handleClose">
        <div class="modal-container">
          <div class="modal-header">
            <h2 class="modal-title">扫码更新 Cookie 和 Token</h2>
            <button class="modal-close" @click="handleClose">×</button>
          </div>
          <div class="modal-body">
            <div class="account-card">
              <div><span>闲鱼账号 ID</span><strong>{{ accountDisplayId || '未同步' }}</strong></div>
              <div><span>系统店铺 ID</span><strong>{{ accountId || '—' }}</strong></div>
              <div><span>账号备注</span><strong>{{ accountRemark || '未填写备注' }}</strong></div>
            </div>
            <div class="qr-code-wrap">
              <img v-if="qrCodeUrl" :src="qrCodeUrl" alt="二维码" class="qr-code" />
              <div v-else class="qr-loading"><div class="loading-spinner"></div></div>
            </div>
            <p class="qr-tip">请使用闲鱼APP扫描二维码完成更新</p>
            <div class="qr-status">
              <span class="status-tag" :class="status === 'confirmed' ? 'is-success' : ''">{{ statusText }}</span>
            </div>
            <p class="expiry" :class="{ 'is-expired': remainingSeconds === 0 && !!expiresAt }">{{ remainingText }}</p>
            <dl class="qr-time-facts">
              <div><dt>生成时间</dt><dd>{{ formatMoment(generatedAt) }}</dd></div>
              <div><dt>本地失效时间</dt><dd>{{ formatMoment(expiresAt) }}</dd></div>
            </dl>
            <p class="expiry-note">本地最长保留 15 分钟；闲鱼平台可能提前使二维码失效。</p>
            <details v-if="sessionId" class="session-detail"><summary>会话详情</summary><code>{{ sessionId }}</code></details>
          </div>
          <div class="modal-footer">
            <button class="btn btn-secondary" @click="handleClose">取消</button>
            <button v-if="canRegenerate" class="btn btn-primary" @click="generateQR">{{ qrCodeUrl ? '刷新二维码' : '重新生成二维码' }}</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.20);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 24px;
}

.modal-container {
  background: rgba(255,255,255,0.72);
  border-radius: 20px;
  width: 100%;
  max-width: 360px;
  box-shadow: 0 32px 100px rgba(0, 0, 0, 0.14), 0 12px 32px rgba(0, 0, 0, 0.1);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - 32px);
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  flex-shrink: 0;
}

.modal-title {
  font-size: 15px;
  font-weight: 600;
  color: #1c1c1e;
  margin: 0;
}

.modal-close {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  border: none;
  background: transparent;
  color: rgba(28,28,30,.55);
  font-size: 18px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.modal-close:hover {
  background: rgba(60,60,67,.12);
  color: #1c1c1e;
}

.modal-body {
  padding: 0 20px 20px;
  text-align: center;
  overflow-y: auto;
}

.account-card {
  display: grid;
  gap: 8px;
  margin: 8px 0 4px;
  padding: 12px;
  border: 1px solid rgba(203, 152, 0, 0.22);
  border-radius: 12px;
  background: rgba(255, 214, 10, 0.09);
  text-align: left;
}

.account-card > div { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.account-card span { color: rgba(28,28,30,.55); font-size: 12px; }
.account-card strong { color: #1c1c1e; font-size: 13px; text-align: right; overflow-wrap: anywhere; }

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  flex-shrink: 0;
}

.btn {
  padding: 8px 18px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  border: none;
}

.btn-secondary {
  background: rgba(60,60,67,.12);
  color: #1c1c1e;
}

.btn-secondary:hover {
  background: rgba(0, 0, 0, 0.1);
}

.btn-primary { background: #ffd60a; color: #1c1c1e; }
.btn-primary:hover { background: #f1c400; }

.qr-code-wrap {
  margin: 16px 0;
  display: flex;
  justify-content: center;
}

.qr-code {
  max-width: 180px;
  border-radius: 12px;
}

.qr-loading {
  width: 180px;
  height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f5f7;
  border-radius: 12px;
}

.loading-spinner {
  width: 28px;
  height: 28px;
  border: 2px solid #e5e5e5;
  border-top-color: #0071e3;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.qr-tip {
  margin: 8px 0;
  color: #1c1c1e;
  font-size: 14px;
}

.qr-status {
  margin: 10px 0;
  min-height: 28px;
  display: flex;
  justify-content: center;
  align-items: center;
}

.status-tag {
  padding: 5px 12px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  background: rgba(255,255,255,0.38);
  color: rgba(28,28,30,.55);
}

.status-tag.is-success {
  background: rgba(52, 199, 89, 0.1);
  color: #30D158;
}

.expiry { margin: 2px 0 0; color: #8a6400; font-size: 13px; font-weight: 700; }
.expiry.is-expired { color: #c9342f; }
.qr-time-facts { display: grid; gap: 5px; margin: 10px 0 0; padding: 9px 11px; border-radius: 9px; background: rgba(255,255,255,.5); text-align: left; }
.qr-time-facts > div { display: flex; justify-content: space-between; gap: 12px; }
.qr-time-facts dt { color: rgba(28,28,30,.55); font-size: 11px; }
.qr-time-facts dd { margin: 0; color: #1c1c1e; font-size: 11px; text-align: right; }
.expiry-note { margin: 6px auto 0; max-width: 280px; color: rgba(28,28,30,.55); font-size: 11px; line-height: 1.5; }
.session-detail { margin-top: 10px; color: rgba(28,28,30,.55); font-size: 11px; }
.session-detail code { display: block; margin-top: 5px; overflow-wrap: anywhere; }

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.3s cubic-bezier(0.32, 0.94, 0.6, 1), opacity 0.2s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.92) translateY(8px);
  opacity: 0;
}
</style>
