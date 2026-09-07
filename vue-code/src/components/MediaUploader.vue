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
const acceptValue = computed(() => props.accept === 'both' ? 'image/*,video/*' : `${props.accept}/*`)
const isVideo = (url: string) => /\.(mp4|webm|mov|m4v|ogg)(?:\?.*)?$/i.test(url)
const uploadHint = computed(() => props.accept === 'video'
  ? '支持 MP4、MOV、WebM、M4V、OGG，单个文件最大 500MB'
  : props.accept === 'both' ? '图片最大 20MB；视频支持 MP4、MOV、WebM、M4V、OGG，最大 500MB' : '支持常见图片格式，单张最大 20MB')

const selectFiles = () => input.value?.click()
const remove = (index: number) => emit('update:modelValue', props.modelValue.filter((_, itemIndex) => itemIndex !== index))

const upload = async (event: Event) => {
  const files = Array.from((event.target as HTMLInputElement).files || [])
  if (!files.length) return
  const available = props.max - props.modelValue.length
  if (available <= 0) return toast.warning(`最多上传 ${props.max} 个文件`)
  uploading.value = true
  try {
    const uploaded: string[] = []
    for (const file of files.slice(0, available)) {
      if (props.accept === 'image' && !file.type.startsWith('image/')) throw new Error('这里只能上传图片')
      const videoExtension = /\.(mp4|mov|webm|m4v|ogg)$/i.test(file.name)
      if (props.accept === 'video' && !file.type.startsWith('video/') && !videoExtension) throw new Error('仅支持 MP4、MOV、WebM、M4V、OGG 视频')
      const maxSize = (file.type.startsWith('video/') || videoExtension) ? 500 : 20
      if (file.size > maxSize * 1024 * 1024) throw new Error(`${file.name} 超过 ${maxSize}MB 限制`)
      const response = await uploadMedia(file, props.accountId)
      if (response.code !== 200 || !response.data?.url) throw new Error(response.msg || '上传失败')
      uploaded.push(response.data.url)
      if (response.data.storage === 'LOCAL') toast.info(file.type.startsWith('image/') ? '图片已本地暂存，发布时会自动同步闲鱼' : '视频已保存到本地素材库')
    }
    emit('update:modelValue', [...props.modelValue, ...uploaded])
    toast.success(`已上传 ${uploaded.length} 个文件`)
  } catch (error: any) {
    toast.error(error?.message || '上传失败')
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
</style>
