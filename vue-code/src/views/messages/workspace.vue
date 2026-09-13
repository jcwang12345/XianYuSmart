<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useMessageManager } from './useMessageManager'
import {
  getContextMessages,
  getConversationProfiles,
  getWorkspaceConversations,
  markWorkspaceConversationRead,
  sendWorkspaceImage,
  sendWorkspaceText,
  takeoverWorkspaceConversation,
  updateWorkspaceConversation,
  syncContextMessages,
  type ChatMessage,
  type ConversationProfile
} from '@/api/message'
import { newRequestId } from '@/api/matrix'
import { getKeywordReplyRules } from '@/api/keywordReply'
import {
  acknowledgeOperationException,
  getNotificationLogs,
  getOperationExceptions,
  type NotificationLog,
  type OperationException
} from '@/api/operations-health'
import MultiImageUploader from '@/components/MultiImageUploader.vue'
import { showError, showSuccess, showWarning } from '@/utils'
import '@/styles/merchant-workbench.css'

const {
  loading,
  accounts,
  selectedAccountId,
  messageList,
  goodsList,
  getCurrentAccountUnb,
  loadAccounts,
  loadMessages,
  handleAccountChange,
  formatMessageTime
} = useMessageManager()

const selectedSid = ref('')
const searchText = ref('')
const profiles = ref<Record<string, ConversationProfile>>({})
const failedImages = ref(new Set<string>())
const contextMessages = ref<ChatMessage[]>([])
const contextLoading = ref(false)
const platformSyncing = ref(false)
const synchronizedSessions = ref(new Set<string>())
const messageText = ref('')
const imageUrls = ref('')
const showImageUploader = ref(false)
const sending = ref(false)
const refreshing = ref(false)
const quickReplies = ref<string[]>([])
const messagesRef = ref<HTMLElement>()
const inboxMode = ref<'conversations' | 'notifications'>('conversations')
const notificationSearch = ref('')
const notificationFilter = ref<'all' | 'pending' | 'delivery'>('all')
const notificationLogs = ref<NotificationLog[]>([])
const operationExceptions = ref<OperationException[]>([])
const notificationLoading = ref(false)
const selectedNotificationId = ref('')
const acknowledgingNotification = ref('')
const workspaceRecord = ref<any>(null)
const workspaceSaving = ref(false)
const workspaceForm = reactive({ pinned: false, keywordFlag: 'NONE', customerNote: '', blacklisted: false })

type SupportNotification = {
  id: string
  source: 'exception' | 'delivery'
  eventType: string
  title: string
  detail: string
  status: string
  time: string
  accountId?: number
  route: string
  originalException?: OperationException
}

const notificationEventLabels: Record<string, string> = {
  ORDER_CREATED: '新订单',
  DELIVERY_SUCCESS: '发货成功',
  DELIVERY_EXCEPTION: '发货异常',
  ACCOUNT_OFFLINE: '账号离线',
  CREDENTIAL_EXPIRED: '凭证失效',
  ACCOUNT_RECOVERED: '账号恢复',
  ACCOUNT_VERIFICATION_REQUIRED: '平台安全验证',
  KAMI_STOCK_LOW: '卡密库存预警',
  OPERATIONAL_ISSUE_CREATED: '运营异常',
  CONVERSATION_SLA_BREACHED: '客服响应超时',
  PRODUCT_PUBLISH_FAILED: '商品发布失败',
  ACCOUNT_CAPABILITY_CHANGED: '账号能力变化'
}

const notificationRoute = (eventType: string) => {
  if (eventType.includes('ORDER') || eventType.includes('DELIVERY')) return '/orders'
  if (eventType.includes('PRODUCT')) return '/goods'
  if (eventType.includes('ACCOUNT') || eventType.includes('CREDENTIAL')) return '/accounts'
  if (eventType.includes('CONVERSATION')) return '/messages'
  return '/operations-health'
}

const supportNotifications = computed<SupportNotification[]>(() => {
  const pending = operationExceptions.value.map(item => ({
    id: `exception-${item.exceptionType}-${item.exceptionId}-${item.exceptionVersion}`,
    source: 'exception' as const,
    eventType: item.exceptionType,
    title: item.title,
    detail: item.reason,
    status: '待处理',
    time: item.occurredAt,
    accountId: item.accountId,
    route: notificationRoute(item.exceptionType),
    originalException: item
  }))
  const deliveries = notificationLogs.value.map(item => ({
    id: `delivery-${item.id}`,
    source: 'delivery' as const,
    eventType: item.eventType,
    title: item.title,
    detail: item.sendStatus === 1
      ? '已通过已配置的通知渠道成功推送。'
      : (item.errorMessage || '外部通知发送失败，请检查通知渠道。'),
    status: item.sendStatus === 1 ? '已送达' : '发送失败',
    time: item.createTime,
    accountId: item.xianyuAccountId,
    route: notificationRoute(item.eventType)
  }))
  const keyword = notificationSearch.value.trim().toLowerCase()
  return [...pending, ...deliveries]
    .filter(item => !selectedAccountId.value || !item.accountId || item.accountId === selectedAccountId.value)
    .filter(item => notificationFilter.value === 'all'
      || (notificationFilter.value === 'pending' && item.source === 'exception')
      || (notificationFilter.value === 'delivery' && item.source === 'delivery'))
    .filter(item => !keyword || `${item.title} ${item.detail} ${item.eventType}`.toLowerCase().includes(keyword))
    .sort((a, b) => new Date(b.time).getTime() - new Date(a.time).getTime())
})

const selectedNotification = computed(() =>
  supportNotifications.value.find(item => item.id === selectedNotificationId.value) || supportNotifications.value[0]
)
const pendingNotificationCount = computed(() => operationExceptions.value.filter(item =>
  !selectedAccountId.value || !item.accountId || item.accountId === selectedAccountId.value
).length)

const normalizeImageUrl = (value?: string) => {
  if (!value) return ''
  if (value.startsWith('//')) return `https:${value}`
  return value.startsWith('http://') ? `https://${value.slice(7)}` : value
}

const markImageError = (url?: string) => {
  if (url) failedImages.value = new Set([...failedImages.value, url])
}

const imageAvailable = (url?: string) => Boolean(url && !failedImages.value.has(url))
const isImageMessage = (message: ChatMessage) => [2, 887, 997].includes(message.contentType)
const isSystemMessage = (message: ChatMessage) => ![1, 2, 887, 888, 997, 999].includes(message.contentType)

const conversations = computed(() => {
  const groups = new Map<string, ChatMessage[]>()
  for (const message of messageList.value) {
    const sid = message.sid || `message-${message.id}`
    groups.set(sid, [...(groups.get(sid) || []), message])
  }
  const currentUserId = getCurrentAccountUnb.value
  return Array.from(groups.entries()).map(([sid, messages]) => {
    const ordered = [...messages].sort((a, b) => Number(b.messageTime) - Number(a.messageTime))
    const latest = ordered[0]!
    const buyer = ordered.find(message => message.senderUserId !== currentUserId) || latest
    const goods = goodsList.value.find(item => item.item.xyGoodId === latest.xyGoodsId)
    const profile = profiles.value[sid]
    return {
      sid,
      messages: ordered,
      buyerName: profile?.nick || buyer.senderUserName || '买家',
      buyerId: buyer.senderUserId || '',
      buyerAvatar: normalizeImageUrl(profile?.avatar),
      latest,
      goods,
      goodsTitle: goods?.item.title || latest.xyGoodsId || '未关联商品',
      goodsCover: normalizeImageUrl(goods?.item.coverPic || '')
    }
  }).filter(item => {
    const keyword = searchText.value.trim().toLowerCase()
    return !keyword || `${item.buyerName} ${item.goodsTitle} ${item.latest.msgContent}`.toLowerCase().includes(keyword)
  }).sort((a, b) => Number(b.latest.messageTime) - Number(a.latest.messageTime))
})

const selected = computed(() => conversations.value.find(item => item.sid === selectedSid.value) || conversations.value[0])
const orderedContext = computed(() => [...contextMessages.value].reverse())
const incomingCount = computed(() => messageList.value.filter(message => message.senderUserId !== getCurrentAccountUnb.value).length)

const scrollToBottom = () => nextTick(() => {
  if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight
})

const loadWorkspaceRecord = async () => {
  workspaceRecord.value = null
  if (!selectedAccountId.value || !selected.value) return
  try {
    const response = await getWorkspaceConversations({ accountId: selectedAccountId.value, search: selected.value.sid, limit: 50 })
    const record = (response.data?.records || []).find(item => item.sessionId === selected.value?.sid)
    workspaceRecord.value = record || null
    workspaceForm.pinned = Boolean(record?.pinned)
    workspaceForm.keywordFlag = record?.keywordFlag || 'NONE'
    workspaceForm.customerNote = record?.customerNote || ''
    workspaceForm.blacklisted = Boolean(record?.customerBlacklisted)
    if (record?.unreadCount) await markWorkspaceConversationRead(selectedAccountId.value, selected.value.sid)
  } catch {
    workspaceRecord.value = null
  }
}

const takeoverCurrent = async () => {
  if (!selectedAccountId.value || !selected.value) return
  workspaceSaving.value = true
  try {
    await takeoverWorkspaceConversation(selectedAccountId.value, selected.value.sid, selected.value.latest.xyGoodsId, 15)
    showSuccess('已人工接管 15 分钟，期间自动回复暂停')
    await loadWorkspaceRecord()
  } finally { workspaceSaving.value = false }
}

const saveWorkspaceSettings = async () => {
  if (!selectedAccountId.value || !selected.value) return
  workspaceSaving.value = true
  try {
    await updateWorkspaceConversation({ accountId: selectedAccountId.value, sessionId: selected.value.sid, ...workspaceForm, requestId: newRequestId('conversation') })
    showSuccess(workspaceForm.blacklisted ? '会话已保存，自动回复和自动发货已对该买家停用' : '会话运营信息已保存')
    await loadWorkspaceRecord()
  } finally { workspaceSaving.value = false }
}

const loadConversationContext = async (syncPlatform = false, showLoading = true) => {
  if (!selectedAccountId.value || !selected.value) {
    contextMessages.value = []
    return
  }
  const accountId = selectedAccountId.value
  const sid = selected.value.sid
  const displayLoading = showLoading && contextMessages.value.length === 0
  if (displayLoading) contextLoading.value = true
  try {
    const response = await getContextMessages({ xianyuAccountId: accountId, sid, limit: 500, offset: 0 })
    if (selectedAccountId.value === accountId && selected.value?.sid === sid) {
      contextMessages.value = response.data || []
      await scrollToBottom()
    }
  } catch (error: any) {
    if (showLoading && !error?.messageShown) showWarning(error?.message || '本地会话读取失败')
    if (!contextMessages.value.length) contextMessages.value = selected.value?.messages || []
  } finally {
    if (displayLoading) contextLoading.value = false
  }
  if (!syncPlatform) return
  platformSyncing.value = true
  try {
    await syncContextMessages({ xianyuAccountId: accountId, sid, maxMessages: 500 }, true)
    synchronizedSessions.value = new Set([...synchronizedSessions.value, `${accountId}:${sid}`])
    const response = await getContextMessages({ xianyuAccountId: accountId, sid, limit: 500, offset: 0 })
    if (selectedAccountId.value === accountId && selected.value?.sid === sid) {
      contextMessages.value = response.data || contextMessages.value
      await scrollToBottom()
    }
  } catch (error: any) {
    // 平台同步失败时继续显示本地消息，避免一次超时让整个会话区域变空。
    if (!error?.messageShown) showWarning(error?.message || '平台历史同步失败，已保留本地会话记录')
  } finally {
    platformSyncing.value = false
  }
}

const loadQuickReplies = async () => {
  quickReplies.value = []
  if (!selectedAccountId.value || !selected.value?.latest.xyGoodsId) return
  try {
    const response = await getKeywordReplyRules({
      xianyuAccountId: selectedAccountId.value,
      xyGoodsId: selected.value.latest.xyGoodsId
    })
    quickReplies.value = [...new Set((response.data || [])
      .flatMap(rule => rule.contents || [])
      .map(content => content.replyText?.trim())
      .filter(Boolean))].slice(0, 8) as string[]
  } catch {
    quickReplies.value = []
  }
}

const sendCurrentMessage = async () => {
  if (!selectedAccountId.value || !selected.value) return
  const text = messageText.value.trim()
  const images = imageUrls.value.split(',').map(value => value.trim()).filter(Boolean)
  if (!text && !images.length) return showWarning('请输入消息或上传图片')
  sending.value = true
  try {
    const toId = selected.value.buyerId.replace('@goofish', '')
    for (const imageUrl of images) {
      const response = await sendWorkspaceImage({
        accountId: selectedAccountId.value,
        sessionId: selected.value.sid,
        recipientUserId: toId,
        content: imageUrl,
        width: 800,
        height: 800,
        goodsId: selected.value.latest.xyGoodsId,
        requestId: newRequestId('message-image')
      })
      if (response.data?.outcomeState === 'UNKNOWN') showWarning(response.data.recoveryHint || '图片发送结果未知，请勿重复发送，先刷新会话核对')
    }
    if (text) {
      const response = await sendWorkspaceText({
        accountId: selectedAccountId.value,
        sessionId: selected.value.sid,
        recipientUserId: toId,
        content: text,
        goodsId: selected.value.latest.xyGoodsId,
        requestId: newRequestId('message-text')
      })
      if (response.data?.outcomeState === 'UNKNOWN') showWarning(response.data.recoveryHint || '消息发送结果未知，请勿重复发送，先刷新会话核对')
    }
    messageText.value = ''
    imageUrls.value = ''
    showImageUploader.value = false
    await loadConversationContext(false, false)
    showSuccess('消息已取得平台确认或进入结果核对')
  } catch (error: any) {
    showError(error?.message || '消息发送失败')
  } finally {
    sending.value = false
  }
}

const refresh = async () => {
  if (refreshing.value || platformSyncing.value) return
  refreshing.value = true
  try {
    const previousMessageId = selected.value?.latest.id
    await loadMessages(true)
    if (selected.value && selected.value.latest.id !== previousMessageId) {
      // 仅在会话出现新消息时更新正文，避免轮询造成滚动位置跳动。
      await loadConversationContext(false, false)
    }
  } finally {
    refreshing.value = false
  }
}

const loadSupportNotifications = async (silent = false) => {
  if (!silent) notificationLoading.value = true
  try {
    const [exceptionsResult, logsResult] = await Promise.allSettled([
      getOperationExceptions(),
      getNotificationLogs()
    ])
    if (exceptionsResult.status === 'fulfilled') operationExceptions.value = exceptionsResult.value.data || []
    if (logsResult.status === 'fulfilled') notificationLogs.value = logsResult.value.data || []
    if (exceptionsResult.status === 'rejected' && logsResult.status === 'rejected' && !silent) {
      showWarning('通知消息暂时无法读取')
    }
  } finally {
    notificationLoading.value = false
  }
}

const switchInbox = async (mode: 'conversations' | 'notifications') => {
  inboxMode.value = mode
  if (mode === 'notifications') await loadSupportNotifications()
}

const acknowledgeSelectedNotification = async () => {
  const item = selectedNotification.value
  if (!item?.originalException) return
  acknowledgingNotification.value = item.id
  try {
    await acknowledgeOperationException(item.originalException)
    showSuccess('通知已标记为已处理')
    await loadSupportNotifications(true)
  } catch (error: any) {
    if (!error?.messageShown) showError(error?.message || '处理通知失败')
  } finally {
    acknowledgingNotification.value = ''
  }
}

watch(conversations, value => {
  if (!value.length) selectedSid.value = ''
  else if (!value.some(item => item.sid === selectedSid.value)) selectedSid.value = value[0]!.sid
}, { immediate: true })

watch(supportNotifications, value => {
  if (!value.length) selectedNotificationId.value = ''
  else if (!value.some(item => item.id === selectedNotificationId.value)) selectedNotificationId.value = value[0]!.id
}, { immediate: true })

watch(selectedAccountId, () => {
  profiles.value = {}
  failedImages.value = new Set()
  synchronizedSessions.value = new Set()
})

watch([selectedAccountId, messageList], async () => {
  if (!selectedAccountId.value) return
  const accountId = selectedAccountId.value
  const sessionIds = [...new Set(messageList.value.map(message => message.sid).filter(Boolean))]
    .filter(sid => !profiles.value[sid])
  for (let index = 0; index < sessionIds.length; index += 20) {
    const response = await getConversationProfiles({
      xianyuAccountId: accountId,
      sessionIds: sessionIds.slice(index, index + 20)
    })
    if (selectedAccountId.value !== accountId) return
    for (const profile of response.data || []) profiles.value[profile.sid] = profile
  }
}, { deep: false })

watch([selectedAccountId, () => selected.value?.sid], async ([accountId, sid]) => {
  contextMessages.value = []
  await loadQuickReplies()
  if (!accountId || !sid) return
  const key = `${accountId}:${sid}`
  await loadConversationContext(!synchronizedSessions.value.has(key))
  await loadWorkspaceRecord()
}, { immediate: true })

let timer: ReturnType<typeof setInterval> | undefined
onMounted(async () => {
  await loadAccounts()
  await loadSupportNotifications(true)
  timer = setInterval(() => {
    if (inboxMode.value === 'notifications') loadSupportNotifications(true)
    else Promise.all([refresh(), loadSupportNotifications(true)])
  }, 10000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <section class="workbench chat">
    <header class="workbench__header">
      <div><h1>集成客服</h1><p>统一处理买家会话、订单与账号通知，重要事件不再散落。</p></div>
      <div class="workbench__actions">
        <select v-model="selectedAccountId" class="workbench__select chat__account" @change="handleAccountChange">
          <option v-if="!accounts.length" :value="null" disabled>暂无可用账号</option>
          <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option>
        </select>
        <button class="workbench__btn" :disabled="loading || platformSyncing || notificationLoading" @click="inboxMode === 'notifications' ? loadSupportNotifications() : refresh()">
          {{ loading || platformSyncing || notificationLoading ? '同步中' : '刷新' }}
        </button>
      </div>
    </header>

    <nav class="chat__inbox-tabs" aria-label="客服消息类型">
      <button :class="{ 'chat__inbox-tab--active': inboxMode === 'conversations' }" @click="switchInbox('conversations')">
        买家会话 <span>{{ conversations.length }}</span>
      </button>
      <button :class="{ 'chat__inbox-tab--active': inboxMode === 'notifications' }" @click="switchInbox('notifications')">
        通知消息 <span :class="{ 'chat__tab-count--alert': pendingNotificationCount > 0 }">{{ pendingNotificationCount }}</span>
      </button>
    </nav>

    <div v-if="inboxMode === 'conversations'" class="chat__layout">
      <aside class="workbench__card chat__conversations">
        <div class="chat__summary">
          <strong>在线消息 <span>{{ conversations.length }}</span></strong>
          <div><span>全部 {{ conversations.length }}</span><span>买家消息 {{ incomingCount }}</span></div>
          <input v-model="searchText" class="workbench__input" placeholder="搜索联系人、商品或关键词">
        </div>
        <button
          v-for="conversation in conversations"
          :key="conversation.sid"
          class="chat__conversation"
          :class="{ 'chat__conversation--active': selected?.sid === conversation.sid }"
          @click="selectedSid = conversation.sid"
        >
          <img v-if="imageAvailable(conversation.buyerAvatar)" class="chat__avatar chat__avatar--image" :src="conversation.buyerAvatar" alt="" @error="markImageError(conversation.buyerAvatar)">
          <div v-else class="chat__avatar">{{ conversation.buyerName.slice(0, 1) }}</div>
          <div class="chat__conversation-copy">
            <strong>{{ conversation.buyerName }}</strong>
            <span>{{ conversation.goodsTitle }}</span>
            <p>{{ isImageMessage(conversation.latest) ? '[图片]' : conversation.latest.msgContent }}</p>
          </div>
          <time>{{ formatMessageTime(conversation.latest.messageTime) }}</time>
        </button>
        <div v-if="!conversations.length" class="workbench__empty">暂无会话</div>
      </aside>

      <main class="workbench__card chat__main">
        <template v-if="selected">
          <header class="chat__main-header">
            <img v-if="imageAvailable(selected.buyerAvatar)" class="chat__avatar chat__avatar--image" :src="selected.buyerAvatar" alt="" @error="markImageError(selected.buyerAvatar)">
            <div v-else class="chat__avatar">{{ selected.buyerName.slice(0, 1) }}</div>
            <div><strong>{{ selected.buyerName }}</strong><span>{{ selected.goodsTitle }}</span></div>
            <span v-if="workspaceRecord?.slaBreached" class="chat__sla">响应已超时</span>
            <button class="workbench__btn" :disabled="workspaceSaving" @click="takeoverCurrent">人工接管 15 分钟</button>
            <button class="workbench__btn" :disabled="platformSyncing" @click="loadConversationContext(true)">
              {{ platformSyncing ? '同步历史中' : '同步完整历史' }}
            </button>
          </header>

          <div ref="messagesRef" class="chat__messages">
            <div v-if="contextLoading" class="chat__loading">正在读取完整会话…</div>
            <template v-for="message in orderedContext" :key="message.id">
              <article v-if="isSystemMessage(message)" class="chat__system">{{ message.msgContent }}</article>
              <article v-else class="chat__message" :class="{ 'chat__message--mine': message.senderUserId === getCurrentAccountUnb }">
                <span>{{ message.senderUserId === getCurrentAccountUnb ? '商家' : (message.senderUserName || selected.buyerName) }}</span>
                <img v-if="isImageMessage(message) && imageAvailable(normalizeImageUrl(message.msgContent))" class="chat__message-image" :src="normalizeImageUrl(message.msgContent)" alt="会话图片" @error="markImageError(normalizeImageUrl(message.msgContent))">
                <p v-else>{{ message.msgContent }}</p>
                <time>{{ formatMessageTime(message.messageTime) }}</time>
              </article>
            </template>
            <div v-if="!contextLoading && !orderedContext.length" class="workbench__empty">暂无历史消息</div>
          </div>

          <footer class="chat__composer">
            <div v-if="quickReplies.length" class="chat__quick">
              <button v-for="reply in quickReplies" :key="reply" @click="messageText = reply">{{ reply }}</button>
            </div>
            <MultiImageUploader v-if="showImageUploader && selectedAccountId" v-model="imageUrls" :account-id="selectedAccountId" :max="5" />
            <textarea v-model="messageText" class="workbench__textarea" rows="3" placeholder="输入消息，Ctrl + Enter 发送" @keydown.ctrl.enter.prevent="sendCurrentMessage"></textarea>
            <div class="chat__composer-actions">
              <button class="workbench__btn" @click="showImageUploader = !showImageUploader">发送图片</button>
              <button class="workbench__btn workbench__btn--primary" :disabled="sending" @click="sendCurrentMessage">{{ sending ? '发送中' : '发送' }}</button>
            </div>
          </footer>
        </template>
        <div v-else class="workbench__empty">选择会话后查看内容</div>
      </main>

      <aside class="workbench__card chat__context">
        <template v-if="selected">
          <section>
            <h2>相关商品</h2>
            <img v-if="imageAvailable(selected.goodsCover)" class="chat__goods-cover" :src="selected.goodsCover" alt="" @error="markImageError(selected.goodsCover)">
            <div v-else class="chat__cover-empty">暂无商品图片</div>
            <strong>{{ selected.goodsTitle }}</strong>
            <p v-if="selected.goods">¥ {{ selected.goods.item.soldPrice || '--' }}</p>
          </section>
          <section>
            <h2>自动回复状态</h2>
            <div class="chat__status">
              <span :class="{ 'chat__status--on': selected.goods?.xianyuAutoReplyOn === 1 }">
                {{ selected.goods?.xianyuAutoReplyOn === 1 ? '已开启' : '未开启' }}
              </span>
              <small>关键词回复 {{ selected.goods?.xianyuKeywordReplyOn === 1 ? '已开启' : '未开启' }}</small>
            </div>
            <router-link class="workbench__btn" to="/auto-reply">配置自动回复</router-link>
          </section>
          <section>
            <h2>会话运营</h2>
            <label class="chat__check"><input v-model="workspaceForm.pinned" type="checkbox"><span>置顶会话</span></label>
            <label>关键词标记<select v-model="workspaceForm.keywordFlag" class="workbench__select"><option value="NONE">无标记</option><option value="INTENT">高意向</option><option value="AFTERSALE">售后</option><option value="RISK">风险</option></select></label>
            <label>客户备注<textarea v-model="workspaceForm.customerNote" class="workbench__textarea" maxlength="500"></textarea></label>
            <label class="chat__check chat__check--danger"><input v-model="workspaceForm.blacklisted" type="checkbox"><span>加入黑名单并停止自动化</span></label>
            <button class="workbench__btn" :disabled="workspaceSaving" @click="saveWorkspaceSettings">保存会话设置</button>
            <small v-if="workspaceRecord">未读 {{ workspaceRecord.unreadCount || 0 }} · 历史 {{ workspaceRecord.historyCoverageStatus || '未同步' }} · 接管 {{ workspaceRecord.manualTakeoverState || '自动' }}</small>
          </section>
          <section>
            <h2>关联信息</h2>
            <dl>
              <dt>买家</dt><dd>{{ selected.buyerName }}</dd>
              <dt>买家 ID</dt><dd>{{ selected.buyerId }}</dd>
              <dt>商品 ID</dt><dd>{{ selected.latest.xyGoodsId || '-' }}</dd>
              <dt>历史消息</dt><dd>{{ contextMessages.length }} 条</dd>
            </dl>
            <router-link class="workbench__btn" :to="{ path: '/buyers', query: { buyerId: selected.buyerId } }">查看买家档案</router-link>
            <router-link class="workbench__btn" :to="{ path: '/orders', query: { buyerId: selected.buyerId } }">查看关联订单</router-link>
          </section>
        </template>
      </aside>
    </div>

    <div v-else class="chat__layout chat__layout--notifications">
      <aside class="workbench__card chat__conversations">
        <div class="chat__summary">
          <strong>通知消息 <span>{{ supportNotifications.length }}</span></strong>
          <div class="chat__notification-filters">
            <button :class="{ active: notificationFilter === 'all' }" @click="notificationFilter = 'all'">全部</button>
            <button :class="{ active: notificationFilter === 'pending' }" @click="notificationFilter = 'pending'">待处理 {{ pendingNotificationCount }}</button>
            <button :class="{ active: notificationFilter === 'delivery' }" @click="notificationFilter = 'delivery'">发送记录</button>
          </div>
          <input v-model="notificationSearch" class="workbench__input" placeholder="搜索通知标题或内容">
        </div>
        <button
          v-for="item in supportNotifications"
          :key="item.id"
          class="chat__notification"
          :class="{ 'chat__notification--active': selectedNotification?.id === item.id }"
          @click="selectedNotificationId = item.id"
        >
          <span class="chat__notification-dot" :class="`chat__notification-dot--${item.source}`"></span>
          <div>
            <strong>{{ item.title }}</strong>
            <p>{{ item.detail }}</p>
            <small>{{ notificationEventLabels[item.eventType] || item.eventType }} · {{ item.accountId ? `账号 ${item.accountId}` : '全局' }}</small>
          </div>
          <time>{{ formatMessageTime(item.time) }}</time>
        </button>
        <div v-if="notificationLoading && !supportNotifications.length" class="workbench__empty">正在读取通知…</div>
        <div v-else-if="!supportNotifications.length" class="workbench__empty">暂无工作台通知</div>
      </aside>

      <main class="workbench__card chat__notification-detail">
        <template v-if="selectedNotification">
          <header>
            <div>
              <span class="chat__notification-kind" :class="`chat__notification-kind--${selectedNotification.source}`">
                {{ selectedNotification.source === 'exception' ? '待处理业务通知' : '外部推送记录' }}
              </span>
              <h2>{{ selectedNotification.title }}</h2>
            </div>
            <time>{{ formatMessageTime(selectedNotification.time) }}</time>
          </header>
          <section>
            <h3>通知内容</h3>
            <p>{{ selectedNotification.detail }}</p>
          </section>
          <dl>
            <dt>事件类型</dt><dd>{{ notificationEventLabels[selectedNotification.eventType] || selectedNotification.eventType }}</dd>
            <dt>所属账号</dt><dd>{{ selectedNotification.accountId || '全局工作台' }}</dd>
            <dt>当前状态</dt><dd>{{ selectedNotification.status }}</dd>
            <dt>发生时间</dt><dd>{{ new Date(selectedNotification.time).toLocaleString('zh-CN') }}</dd>
          </dl>
          <footer>
            <router-link class="workbench__btn" :to="selectedNotification.route">查看相关业务</router-link>
            <button
              v-if="selectedNotification.source === 'exception'"
              class="workbench__btn workbench__btn--primary"
              :disabled="acknowledgingNotification === selectedNotification.id"
              @click="acknowledgeSelectedNotification"
            >
              {{ acknowledgingNotification === selectedNotification.id ? '处理中' : '标记已处理' }}
            </button>
          </footer>
        </template>
        <div v-else class="workbench__empty">
          <strong>暂无通知消息</strong>
          <span>订单、退款、发货、账号安全和客服超时事件会集中显示在这里。</span>
        </div>
      </main>
    </div>
  </section>
</template>

<style scoped>
.chat { height: 100%; overflow: hidden; }
.chat__account { width: 180px; }
.chat__inbox-tabs { display: flex; height: 44px; gap: 24px; padding: 0 4px; border-bottom: 1px solid #eaecf0; }
.chat__inbox-tabs button { position: relative; display: flex; align-items: center; gap: 7px; padding: 0 6px; border: 0; color: #667085; background: transparent; font-weight: 650; cursor: pointer; }
.chat__inbox-tabs button::after { position: absolute; right: 0; bottom: -1px; left: 0; height: 2px; background: transparent; content: ''; }
.chat__inbox-tabs button.chat__inbox-tab--active { color: #9a6200; }
.chat__inbox-tabs button.chat__inbox-tab--active::after { background: #d9a600; }
.chat__inbox-tabs span { min-width: 20px; padding: 1px 6px; border-radius: 10px; background: #f2f4f7; color: #667085; font-size: 11px; text-align: center; }
.chat__inbox-tabs span.chat__tab-count--alert { background: #fee4e2; color: #b42318; }
.chat__layout { display: grid; height: calc(100% - 112px); min-height: 520px; grid-template-columns: 300px minmax(420px, 1fr) 280px; gap: 12px; padding-top: 12px; }
.chat__layout--notifications { grid-template-columns: minmax(320px, 38%) minmax(420px, 1fr); }
.chat__conversations, .chat__main, .chat__context { min-height: 0; overflow: hidden; padding: 0; }
.chat__conversations { overflow-y: auto; }
.chat__summary { position: sticky; top: 0; z-index: 2; padding: 14px; border-bottom: 1px solid #eaecf0; background: #fff; }
.chat__summary > strong { display: flex; justify-content: space-between; margin-bottom: 8px; }
.chat__summary > div { display: flex; gap: 12px; margin-bottom: 10px; color: #667085; font-size: 12px; }
.chat__conversation { display: grid; width: 100%; grid-template-columns: auto minmax(0, 1fr) auto; align-items: start; gap: 9px; padding: 12px; border: 0; border-bottom: 1px solid #f2f4f7; color: #344054; background: #fff; text-align: left; cursor: pointer; }
.chat__conversation--active { background: #f0f5ff; }
.chat__conversation-copy { min-width: 0; }
.chat__conversation-copy strong, .chat__conversation-copy span, .chat__conversation-copy p { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat__conversation-copy span, .chat__conversation-copy p, .chat__conversation time { color: #667085; font-size: 11px; }
.chat__conversation-copy p { margin: 5px 0 0; }
.chat__avatar { display: grid; width: 38px; height: 38px; flex: 0 0 38px; place-items: center; border-radius: 50%; color: #9a6200; background: #eaf0ff; font-weight: 700; }
.chat__avatar--image { display: block; object-fit: cover; }
.chat__main { display: flex; flex-direction: column; }
.chat__main-header { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-bottom: 1px solid #eaecf0; }
.chat__main-header > div:nth-child(2) { display: flex; min-width: 0; flex: 1; flex-direction: column; }
.chat__main-header span { overflow: hidden; color: #667085; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.chat__main-header .chat__sla { flex: 0 0 auto; padding: 4px 8px; border-radius: 999px; color: #b42318; background: #fee4e2; font-size: 11px; }
.chat__messages { display: flex; flex: 1; overflow-y: auto; flex-direction: column; gap: 10px; padding: 18px; }
.chat__loading, .chat__system { align-self: center; padding: 5px 10px; border-radius: 12px; color: #667085; background: #f2f4f7; font-size: 11px; }
.chat__message { max-width: 72%; align-self: flex-start; }
.chat__message span, .chat__message time { display: block; color: #98a2b3; font-size: 11px; }
.chat__message p { margin: 4px 0; padding: 9px 12px; border-radius: 4px 10px 10px; background: #f2f4f7; line-height: 1.6; white-space: pre-wrap; }
.chat__message-image { display: block; max-width: 280px; max-height: 320px; margin: 4px 0; border-radius: 10px; object-fit: contain; }
.chat__message--mine { align-self: flex-end; text-align: right; }
.chat__message--mine p { border-radius: 10px 4px 10px 10px; color: #fff; background: #9a6200; text-align: left; }
.chat__composer { padding: 10px 14px 12px; border-top: 1px solid #eaecf0; }
.chat__composer .workbench__textarea { min-height: 70px; resize: vertical; }
.chat__quick { display: flex; overflow-x: auto; gap: 6px; margin-bottom: 8px; }
.chat__quick button { max-width: 180px; flex: 0 0 auto; overflow: hidden; padding: 5px 9px; border: 1px solid #d0d5dd; border-radius: 14px; background: #fff; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
.chat__composer-actions { display: flex; justify-content: space-between; margin-top: 8px; }
.chat__context { overflow-y: auto; }
.chat__context section { display: flex; flex-direction: column; gap: 9px; padding: 14px; border-bottom: 1px solid #eaecf0; }
.chat__context h2 { margin: 0; font-size: 15px; }
.chat__context label { display: grid; gap: 5px; color: #667085; font-size: 12px; }
.chat__check { display: flex !important; align-items: center; gap: 8px !important; }
.chat__check--danger { color: #b42318 !important; }
.chat__goods-cover, .chat__cover-empty { width: 100%; aspect-ratio: 4 / 3; border-radius: 7px; object-fit: cover; background: #f2f4f7; }
.chat__cover-empty { display: grid; place-items: center; color: #98a2b3; font-size: 12px; }
.chat__status { display: flex; align-items: center; justify-content: space-between; }
.chat__status span { padding: 3px 8px; border-radius: 12px; color: #b42318; background: #fee4e2; font-size: 12px; }
.chat__status span.chat__status--on { color: #067647; background: #dcfae6; }
.chat__status small { color: #667085; }
.chat__context dl { display: grid; grid-template-columns: 70px minmax(0, 1fr); gap: 8px; margin: 0; font-size: 12px; }
.chat__context dt { color: #667085; }
.chat__context dd { margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat__notification-filters { display: flex !important; gap: 6px !important; }
.chat__notification-filters button { padding: 4px 8px; border: 1px solid #eaecf0; border-radius: 12px; color: #667085; background: #fff; font-size: 11px; cursor: pointer; }
.chat__notification-filters button.active { border-color: #f5d061; color: #7a5200; background: #fff8dd; }
.chat__notification { display: grid; width: 100%; grid-template-columns: auto minmax(0, 1fr) auto; gap: 10px; padding: 13px 12px; border: 0; border-bottom: 1px solid #f2f4f7; background: #fff; text-align: left; cursor: pointer; }
.chat__notification--active { background: #fffaf0; }
.chat__notification-dot { width: 9px; height: 9px; margin-top: 5px; border-radius: 50%; background: #98a2b3; }
.chat__notification-dot--exception { background: #f04438; box-shadow: 0 0 0 3px #fee4e2; }
.chat__notification-dot--delivery { background: #12b76a; box-shadow: 0 0 0 3px #dcfae6; }
.chat__notification > div { min-width: 0; }
.chat__notification strong, .chat__notification p, .chat__notification small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat__notification p { margin: 5px 0; color: #667085; font-size: 12px; }
.chat__notification small, .chat__notification time { color: #98a2b3; font-size: 11px; }
.chat__notification-detail { display: flex; min-height: 0; flex-direction: column; padding: 0; overflow: hidden; }
.chat__notification-detail > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 22px 24px; border-bottom: 1px solid #eaecf0; }
.chat__notification-detail h2 { margin: 10px 0 0; font-size: 20px; }
.chat__notification-detail time { color: #98a2b3; font-size: 12px; }
.chat__notification-kind { display: inline-flex; padding: 3px 8px; border-radius: 12px; color: #475467; background: #f2f4f7; font-size: 12px; }
.chat__notification-kind--exception { color: #b42318; background: #fee4e2; }
.chat__notification-kind--delivery { color: #067647; background: #dcfae6; }
.chat__notification-detail > section { margin: 24px; padding: 18px; border: 1px solid #eaecf0; border-radius: 10px; background: #fafafa; }
.chat__notification-detail h3 { margin: 0 0 10px; font-size: 14px; }
.chat__notification-detail p { margin: 0; color: #475467; line-height: 1.7; white-space: pre-wrap; }
.chat__notification-detail dl { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 13px; margin: 0 24px; font-size: 13px; }
.chat__notification-detail dt { color: #667085; }
.chat__notification-detail dd { margin: 0; color: #101828; }
.chat__notification-detail footer { display: flex; justify-content: flex-end; gap: 10px; margin-top: auto; padding: 16px 24px; border-top: 1px solid #eaecf0; }
.chat__notification-detail > .workbench__empty { display: flex; flex: 1; flex-direction: column; gap: 8px; align-items: center; justify-content: center; }
.chat__notification-detail > .workbench__empty span { color: #667085; }
@media (max-width: 1180px) { .chat__layout { grid-template-columns: 280px minmax(0, 1fr); } .chat__context { display: none; } }
@media (max-width: 767px) {
  .chat { height: auto; overflow: visible; }
  .chat__layout { display: block; height: auto; min-height: 0; }
  .chat__conversations { max-height: 42vh; }
  .chat__main { min-height: 58vh; margin-top: 10px; margin-bottom: max(12px, env(safe-area-inset-bottom)); }
  .chat__layout--notifications { display: block; }
  .chat__notification-detail { min-height: 48vh; margin-top: 10px; }
  .chat__message { max-width: 88%; }
  .chat__main-header .workbench__btn { padding: 6px 8px; font-size: 11px; }
}
</style>
