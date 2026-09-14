<script setup lang="ts">
import { computed, ref } from 'vue'
import { uploadMedia } from '@/api/image'
import { toast } from '@/utils/toast'

interface Props {
  modelValue?: string[]
  accountId?: number
  max?: number
  accept?: 'image' | 'video' | 'both'
  label?: string
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: () => [],
  max: 9,
  accept: 'image',
  label: '添加文件'
})
const emit = defineEmits<{ (event: 'update:modelValue', value: string[]): void }>()
const uploading = ref(false)
const input = ref<HTMLInputElement>()
interface FailedUpload { id: string; file: File; reason: string }
const failedUploads = ref<FailedUpload[]>([])
const acceptValue = computed(() => props.accept === 'both' ? 'image/*,video/*' : `${props.accept}/*`)
const isVideo = (url: string) => /\.(mp4|webm|mov|m4v|ogg)(?:\?.*)?$/i.test(url)
const uploadHint = computed(() => props.accept === 'video'
  ? '支持 MP4、MOV、WebM、M4V、OGG，单个文件最大 500MB'
  : props.accept === 'both' ? '图片最大 20MB；视频支持 MP4、MOV、WebM、M4V、OGG，最大 500MB' : '支持常见图片格式，单张最大 20MB')

const selectFiles = () => input.value?.click()
const remove = (index: number) => emit('update:modelValue', props.modelValue.filter((_, itemIndex) => itemIndex !== index))

const validateFile = (file: File) => {
  if (props.accept === 'image' && !file.type.startsWith('image/')) throw new Error('这里只能上传图片')
  const videoExtension = /\.(mp4|mov|webm|m4v|ogg)$/i.test(file.name)
  if (props.accept === 'video' && !file.type.startsWith('video/') && !videoExtension) throw new Error('仅支持 MP4、MOV、WebM、M4V、OGG 视频')
  const maxSize = (file.type.startsWith('video/') || videoExtension) ? 500 : 20
  if (file.size > maxSize * 1024 * 1024) throw new Error(`${file.name} 超过 ${maxSize}MB 限制`)
}

const uploadOne = async (file: File) => {
  validateFile(file)
  const response = await uploadMedia(file, props.accountId)
  if (response.code !== 200 || !response.data?.url) throw new Error(response.msg || '上传失败')
  if (response.data.storage === 'LOCAL') toast.info(file.type.startsWith('image/') ? '图片已本地暂存，发布时会自动同步闲鱼' : '视频已保存到本地素材库')
  return response.data.url
}

const rememberFailure = (file: File, error: any, id?: string) => {
  const failure: FailedUpload = { id: id || `${Date.now()}-${Math.random().toString(36).slice(2)}`, file, reason: error?.message || '上传失败' }
  const index = failedUploads.value.findIndex(item => item.id === failure.id)
  if (index >= 0) failedUploads.value[index] = failure
  else failedUploads.value.push(failure)
}

const retryFailed = async (failure: FailedUpload) => {
  if (uploading.value || props.modelValue.length >= props.max) return
  uploading.value = true
  try {
    const url = await uploadOne(failure.file)
    emit('update:modelValue', [...props.modelValue, url])
    failedUploads.value = failedUploads.value.filter(item => item.id !== failure.id)
    toast.success(`${failure.file.name} 上传成功`)
  } catch (error: any) {
    rememberFailure(failure.file, error, failure.id)
    toast.error(error?.message || '重试失败')
  } finally { uploading.value = false }
}

const removeFailed = (id: string) => { failedUploads.value = failedUploads.value.filter(item => item.id !== id) }

const upload = async (event: Event) => {
  const files = Array.from((event.target as HTMLInputElement).files || [])
  if (!files.length) return
  const available = props.max - props.modelValue.length
  if (available <= 0) return toast.warning(`最多上传 ${props.max} 个文件`)
  uploading.value = true
  try {
    const uploaded: string[] = []
    for (const file of files.slice(0, available)) {
      try { uploaded.push(await uploadOne(file)) }
      catch (error: any) { rememberFailure(file, error) }
    }
    if (uploaded.length) {
      emit('update:modelValue', [...props.modelValue, ...uploaded])
      toast.success(`已上传 ${uploaded.length} 个文件`)
    }
    if (failedUploads.value.length) toast.warning(`${failedUploads.value.length} 个文件上传失败，可在下方逐项重试`)
  } finally {
    uploading.value = false
    ;(event.target as HTMLInputElement).value = ''
  }
}
</script>

<template>
  <div class="media-uploader">
    <div class="media-uploader__list">
      <article v-for="(url, index) in modelValue" :key="url" class="media-uploader__card">
        <video v-if="isVideo(url)" :src="url" controls preload="metadata"></video>
        <img v-else :src="url" alt="已上传素材" />
        <button type="button" aria-label="删除素材" @click="remove(index)">×</button>
        <small>{{ isVideo(url) ? '视频（本地）' : url.startsWith('/media/') ? '图片（本地暂存）' : '图片（闲鱼）' }}</small>
      </article>
      <button v-if="modelValue.length < max" type="button" class="media-uploader__add" :disabled="uploading" @click="selectFiles">
        {{ uploading ? '上传中…' : label }}<small>最多 {{ max }} 个</small>
      </button>
    </div>
    <input ref="input" class="media-uploader__input" type="file" :accept="acceptValue" multiple @change="upload" />
    <small class="media-uploader__hint">{{ uploadHint }}</small>
    <section v-if="failedUploads.length" class="media-uploader__failures" aria-live="polite">
      <strong>上传失败（{{ failedUploads.length }}）</strong>
      <article v-for="failure in failedUploads" :key="failure.id">
        <div><b>{{ failure.file.name }}</b><small>{{ failure.reason }}</small></div>
        <button type="button" :disabled="uploading || modelValue.length >= max" @click="retryFailed(failure)">重试</button>
        <button type="button" class="media-uploader__discard" :disabled="uploading" @click="removeFailed(failure.id)">移除</button>
      </article>
    </section>
  </div>
</template>

<style scoped>
.media-uploader__list { display:flex; flex-wrap:wrap; gap:10px; }
.media-uploader__card, .media-uploader__add { position:relative; width:124px; height:124px; border:1px solid #dfe4ed; border-radius:10px; overflow:hidden; background:#f7f9fc; }
.media-uploader__card img, .media-uploader__card video { width:100%; height:100%; object-fit:cover; display:block; }
.media-uploader__card button { position:absolute; top:5px; right:5px; width:24px; height:24px; border:0; border-radius:50%; background:rgba(0,0,0,.65); color:#fff; font-size:18px; line-height:20px; cursor:pointer; }
.media-uploader__card small { position:absolute; bottom:0; left:0; right:0; padding:3px 5px; color:#fff; font-size:11px; background:rgba(0,0,0,.58); }
.media-uploader__add { display:flex; flex-direction:column; justify-content:center; align-items:center; gap:7px; color:#3977e8; cursor:pointer; font:inherit; }
.media-uploader__add:disabled { cursor:wait; opacity:.6; }.media-uploader__add small { color:#8993a4; }.media-uploader__input { display:none; }
.media-uploader__failures { display:grid; gap:8px; margin-top:10px; padding:10px; border:1px solid #f2b8b5; border-radius:8px; color:#9b1c1c; background:#fff7f6; }.media-uploader__failures>strong { font-size:12px; }.media-uploader__failures article { display:grid; grid-template-columns:minmax(0,1fr) auto auto; gap:8px; align-items:center; }.media-uploader__failures article div { min-width:0; }.media-uploader__failures b,.media-uploader__failures small { display:block; overflow-wrap:anywhere; }.media-uploader__failures b { font-size:12px; }.media-uploader__failures small { margin-top:2px; color:#b42318; }.media-uploader__failures button { padding:5px 9px; border:1px solid #d92d20; border-radius:6px; color:#b42318; background:#fff; cursor:pointer; }.media-uploader__failures button:disabled { opacity:.5; cursor:not-allowed; }.media-uploader__failures .media-uploader__discard { border-color:#d0d5dd; color:#475467; }
@media(max-width:520px){.media-uploader__failures article{grid-template-columns:1fr 1fr}.media-uploader__failures article div{grid-column:1/-1}}
</style>
