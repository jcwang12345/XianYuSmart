<script setup lang="ts">
import { useRouter } from 'vue-router'

interface ActionItem {
  label: string
  path: string
  primary?: boolean
  description?: string
}

defineProps<{
  eyebrow: string
  title: string
  description: string
  reason: string
  actions: ActionItem[]
}>()

const router = useRouter()
</script>

<template>
  <main class="legacy-page">
    <section class="legacy-card" aria-labelledby="legacy-title">
      <span class="legacy-eyebrow">{{ eyebrow }}</span>
      <h1 id="legacy-title">{{ title }}</h1>
      <p class="legacy-description">{{ description }}</p>
      <div class="legacy-reason" role="note">
        <strong>入口已合并</strong>
        <span>{{ reason }}</span>
      </div>
      <div class="legacy-actions" aria-label="继续前往">
        <button
          v-for="action in actions"
          :key="action.path"
          :class="{ primary: action.primary }"
          @click="router.push(action.path)"
        >
          <span>{{ action.label }}</span>
          <small v-if="action.description">{{ action.description }}</small>
        </button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.legacy-page{display:grid;min-height:100%;place-items:center;padding:var(--page-gutter)}
.legacy-card{width:min(760px,100%);padding:clamp(26px,5vw,52px);border:1px solid var(--glass-border);border-radius:20px;background:#fff;box-shadow:var(--surface-shadow)}
.legacy-eyebrow{color:#8a5900;font-size:11px;font-weight:800;letter-spacing:.11em}
h1{margin:7px 0 10px;color:var(--xy-ink);font-size:clamp(25px,3vw,36px);letter-spacing:-.035em}
.legacy-description{max-width:650px;margin:0;color:#67645d;line-height:1.75}
.legacy-reason{display:grid;gap:5px;margin-top:22px;padding:14px 16px;border:1px solid #eed47a;border-radius:12px;color:#725000;background:var(--xy-yellow-soft);font-size:13px}
.legacy-reason span{line-height:1.55}
.legacy-actions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:22px}
.legacy-actions button{display:grid;gap:5px;min-height:74px;align-content:center;padding:13px 16px;border:1px solid #d9d6cc;border-radius:12px;color:#3f3d38;background:#fff;text-align:left;font:inherit;cursor:pointer}
.legacy-actions button:hover,.legacy-actions button:focus-visible{border-color:#d6a500;background:#fffdf4}
.legacy-actions button:focus-visible{outline:3px solid rgba(247,193,33,.42);outline-offset:2px}
.legacy-actions button.primary{border-color:var(--xy-yellow-strong);color:#171717;background:var(--xy-yellow)}
.legacy-actions span{font-weight:750}
.legacy-actions small{color:#77736b;line-height:1.4}
@media(max-width:600px){.legacy-page{align-items:start;padding:12px}.legacy-card{padding:28px 20px}.legacy-actions{grid-template-columns:1fr}.legacy-actions button{min-height:70px}}
</style>
