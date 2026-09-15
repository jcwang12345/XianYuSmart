<script setup lang="ts">
import { computed, ref } from 'vue'

type Dimension = { name: string; values: string[] }
type Sku = { key: string; values: Record<string, string>; price: number; originalPrice?: number; stock: number; merchantCode?: string; image?: string }

const props = defineProps<{ dimensions: Dimension[]; skus: Sku[]; basePrice: number; baseStock: number }>()
const emit = defineEmits<{
  'update:dimensions': [value: Dimension[]]
  'update:skus': [value: Sku[]]
}>()

const combinationCount = computed(() => props.dimensions.reduce((total, item) => total * Math.max(1, item.values.length), 1))
const bulkPrice = ref<number | undefined>()
const bulkStock = ref<number | undefined>()

const updateName = (index: number, name: string) => {
  const next = props.dimensions.map((item, i) => i === index ? { ...item, name } : item)
  emit('update:dimensions', next)
}

const updateValues = (index: number, raw: string) => {
  const values = raw.split(/[，,\n]/).map(item => item.trim()).filter((item, i, list) => item && list.indexOf(item) === i).slice(0, 20)
  const next = props.dimensions.map((item, i) => i === index ? { ...item, values } : item)
  emit('update:dimensions', next)
}

const addDimension = () => {
  if (props.dimensions.length >= 2) return
  emit('update:dimensions', [...props.dimensions, { name: props.dimensions.length ? '版本' : '规格', values: [] }])
}

const removeDimension = (index: number) => {
  emit('update:dimensions', props.dimensions.filter((_, i) => i !== index))
  emit('update:skus', [])
}

const generate = () => {
  if (!props.dimensions.length || props.dimensions.some(item => !item.name.trim() || !item.values.length) || combinationCount.value > 50) return
  const walk = (index: number, values: Record<string, string>, result: Array<Record<string, string>>) => {
    const dimension = props.dimensions[index]
    if (!dimension) return result.push(values)
    dimension.values.forEach(value => walk(index + 1, { ...values, [dimension.name]: value }, result))
  }
  const combinations: Array<Record<string, string>> = []
  walk(0, {}, combinations)
  const existing = new Map(props.skus.map(item => [item.key, item]))
  emit('update:skus', combinations.map(values => {
    const key = Object.values(values).join(' / ')
    return existing.get(key) || { key, values, price: props.basePrice || 0, originalPrice: undefined, stock: props.baseStock || 1, merchantCode: '', image: '' }
  }))
}

const patchSku = (index: number, patch: Partial<Sku>) => {
  emit('update:skus', props.skus.map((item, i) => i === index ? { ...item, ...patch } : item))
}
const applyBulk = () => {
  const patch: Partial<Sku> = {}
  if (bulkPrice.value !== undefined && bulkPrice.value > 0) patch.price = bulkPrice.value
  if (bulkStock.value !== undefined && bulkStock.value >= 0) patch.stock = bulkStock.value
  if (Object.keys(patch).length) emit('update:skus', props.skus.map(item => ({ ...item, ...patch })))
}
</script>

<template>
  <section class="sku-editor">
    <header>
      <div><strong>规格型号</strong><p>最多 2 个规格维度、50 个组合；可为每个组合设置独立价格、库存和商家编码。</p></div>
      <button class="workbench__btn" type="button" :disabled="dimensions.length >= 2" @click="addDimension">＋ 添加规格</button>
    </header>
    <div v-if="!dimensions.length" class="sku-editor__empty">
      <strong>当前按单规格商品发布</strong>
      <span>如果商品有版本、颜色或套餐差异，可添加规格；真实提交前会再次核对通道能力。</span>
    </div>
    <div v-for="(dimension, index) in dimensions" :key="index" class="sku-editor__dimension">
      <label>规格名称<input class="workbench__input" :value="dimension.name" maxlength="12" placeholder="例如：版本" @input="updateName(index, ($event.target as HTMLInputElement).value)"></label>
      <label>规格值<input class="workbench__input" :value="dimension.values.join('，')" placeholder="例如：标准版，专业版" @input="updateValues(index, ($event.target as HTMLInputElement).value)"></label>
      <button type="button" class="sku-editor__remove" @click="removeDimension(index)">移除</button>
    </div>
    <div v-if="dimensions.length" class="sku-editor__generate">
      <span>预计生成 <strong>{{ combinationCount }}</strong> 个组合</span>
      <button class="workbench__btn workbench__btn--primary" type="button" :disabled="combinationCount > 50 || dimensions.some(item => !item.values.length)" @click="generate">生成规格表</button>
    </div>
    <div v-if="skus.length" class="sku-editor__table-wrap">
      <div class="sku-editor__bulk"><strong>批量填充</strong><label>售价<input v-model.number="bulkPrice" class="workbench__input" type="number" min="0.01" step="0.01" placeholder="不修改"></label><label>库存<input v-model.number="bulkStock" class="workbench__input" type="number" min="0" step="1" placeholder="不修改"></label><button class="workbench__btn" type="button" @click="applyBulk">应用到 {{ skus.length }} 个 SKU</button></div>
      <table>
        <thead><tr><th>规格组合</th><th>售价</th><th>划线价</th><th>库存</th><th>商家编码</th><th>规格图片</th></tr></thead>
        <tbody><tr v-for="(sku, index) in skus" :key="sku.key">
          <td><strong>{{ sku.key }}</strong></td>
          <td><input class="workbench__input" type="number" min="0.01" step="0.01" :value="sku.price" @input="patchSku(index, { price: Number(($event.target as HTMLInputElement).value) })"></td>
          <td><input class="workbench__input" type="number" min="0.01" step="0.01" :value="sku.originalPrice || ''" placeholder="可选" @input="patchSku(index, { originalPrice: Number(($event.target as HTMLInputElement).value) || undefined })"></td>
          <td><input class="workbench__input" type="number" min="1" step="1" :value="sku.stock" @input="patchSku(index, { stock: Number(($event.target as HTMLInputElement).value) })"></td>
          <td><input class="workbench__input" :value="sku.merchantCode || ''" maxlength="64" placeholder="可选" @input="patchSku(index, { merchantCode: ($event.target as HTMLInputElement).value })"></td>
          <td><input class="workbench__input" :value="sku.image || ''" maxlength="1000" placeholder="HTTPS 或 /media/" @input="patchSku(index, { image: ($event.target as HTMLInputElement).value })"></td>
        </tr></tbody>
      </table>
    </div>
    <p v-if="skus.length" class="sku-editor__warning">多规格内容已进入草稿与本地预览；仅当所选发布通道返回“多规格已验证”时，真实发布才会放行。</p>
  </section>
</template>

<style scoped>
.sku-editor{display:grid;gap:14px}.sku-editor>header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.sku-editor header p{margin:4px 0 0;color:#77736b;font-size:12px}.sku-editor__empty{display:grid;gap:4px;padding:18px;border:1px dashed #d8d5cd;border-radius:12px;color:#5d5a54;background:#faf9f6;text-align:center}.sku-editor__empty span{font-size:12px}.sku-editor__dimension{display:grid;grid-template-columns:140px minmax(220px,1fr) auto;align-items:end;gap:10px;padding:12px;border:1px solid #e2dfd7;border-radius:12px;background:#fff}.sku-editor__dimension label{display:grid;gap:6px;color:#57534e;font-size:12px;font-weight:650}.sku-editor__remove{height:40px;padding:0 8px;border:0;color:#a13b31;background:transparent;cursor:pointer}.sku-editor__generate{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 12px;border-radius:10px;background:#fff7dc;font-size:12px}.sku-editor__table-wrap{overflow:auto;border:1px solid #e2dfd7;border-radius:12px}.sku-editor table{width:100%;min-width:760px;border-collapse:collapse}.sku-editor th,.sku-editor td{padding:10px;border-bottom:1px solid #eeeae2;text-align:left;font-size:12px}.sku-editor th{color:#77736b;background:#faf9f6}.sku-editor td:first-child{min-width:150px}.sku-editor td .workbench__input{min-width:110px}.sku-editor__warning{margin:0;color:#8a5a00;font-size:12px}@media(max-width:767px){.sku-editor>header{flex-direction:column}.sku-editor__dimension{grid-template-columns:1fr}.sku-editor__remove{justify-self:start}.sku-editor__generate{align-items:flex-start;flex-direction:column}}
.sku-editor__bulk{position:sticky;left:0;display:flex;align-items:end;gap:8px;padding:10px;background:#fff8df}.sku-editor__bulk label{display:grid;gap:4px;font-size:10px}.sku-editor__bulk .workbench__input{width:110px}.sku-editor table{min-width:960px}@media(max-width:767px){.sku-editor__bulk{align-items:stretch;flex-direction:column}.sku-editor__bulk .workbench__input{width:100%}}
</style>
