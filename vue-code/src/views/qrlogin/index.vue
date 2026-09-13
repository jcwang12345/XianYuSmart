<script setup lang="ts">
import { useRouter } from 'vue-router'
import IconQrCode from '@/components/icons/IconQrCode.vue'

const router = useRouter()
</script>

<template>
  <section class="qrlogin-page">
    <div class="qrlogin-card">
      <div class="qrlogin-icon"><IconQrCode /></div>
      <span>ACCOUNT ACCESS</span>
      <h1>扫码登录闲鱼账号</h1>
      <p>扫码入口已经整合到账号管理与连接管理中。账号名称、账号 ID 和有效期会随二维码一起展示，便于多账号识别。</p>
      <div class="qrlogin-actions">
        <button class="primary" @click="router.push('/accounts')">前往账号管理</button>
        <button @click="router.push('/connection')">检查连接状态</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.qrlogin-page{display:grid;min-height:100%;place-items:center;padding:var(--page-gutter);background:transparent}.qrlogin-card{width:min(560px,100%);padding:clamp(28px,5vw,52px);border:1px solid var(--glass-border);border-radius:20px;background:#fff;box-shadow:var(--surface-shadow);text-align:center}.qrlogin-icon{display:grid;width:64px;height:64px;margin:0 auto 18px;place-items:center;border-radius:18px;color:#171717;background:var(--xy-yellow)}.qrlogin-icon :deep(svg){width:32px;height:32px}.qrlogin-card>span{color:#8a5900;font-size:11px;font-weight:750;letter-spacing:.1em}.qrlogin-card h1{margin:6px 0 10px;font-size:clamp(24px,3vw,34px);font-weight:750;letter-spacing:-.03em}.qrlogin-card p{margin:0;color:#6f6c65;line-height:1.8}.qrlogin-actions{display:flex;justify-content:center;gap:10px;margin-top:26px}.qrlogin-actions button{min-height:44px;padding:0 18px;border:1px solid #d9d6cc;border-radius:10px;background:#fff;color:#3f3d38;font:inherit;font-weight:650;cursor:pointer}.qrlogin-actions .primary{border-color:var(--xy-yellow-strong);color:#171717;background:var(--xy-yellow)}@media(max-width:560px){.qrlogin-page{align-items:start;padding:12px}.qrlogin-card{padding:30px 20px}.qrlogin-actions{flex-direction:column}.qrlogin-actions button{width:100%;min-height:48px}}
</style>
