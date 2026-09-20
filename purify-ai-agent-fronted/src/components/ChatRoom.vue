<script setup>
import { ref, reactive, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { fetchHistory, fetchSessions, renameSession, deleteSession } from '../api/http.js'
import { streamChat } from '../api/sse.js'
import { renderMarkdown } from '../markdown.js'

/**
 * 轻语和 PurifyManus 共用的聊天室。
 *
 * 两条链路的交互（气泡方向、流式打字、停止、会话 id、历史回放、侧边栏）完全一致，
 * 差别只有四处：接口路径、主题色、要不要展示工具调用过程、欢迎语。
 * 所以做成一个组件、用 props 区分，而不是复制两份 —— 复制出来的两份迟早会走样。
 */
const props = defineProps({
  /** 'slim' | 'manus'，同时决定接口路径 /api/{link}/chat 和会话列表的入口名 */
  link: { type: String, required: true },
  title: { type: String, required: true },
  /** 主题色，用户气泡和高亮都用它 */
  accent: { type: String, required: true },
  /** 智能体链路：额外展示工具调用过程、区分 WAITING_FOR_USER / ABORTED */
  agent: { type: Boolean, default: false },
  welcome: { type: String, required: true },
  intro: { type: String, required: true },
  examples: { type: Array, default: () => [] },
  placeholder: { type: String, default: '' },
})

/* ------------------------------------------------------------------ 会话 id */

// 用 sessionStorage 而不是 localStorage：刷新页面要接着刚才那段聊，
// 但新开一个标签页应当是一个新会话。用户标识则相反，那个要长期不变（见 user.js）
const STORAGE_KEY = `purify:chatId:${props.link}`

const chatId = ref('')
const shortId = computed(() => chatId.value.slice(0, 8))

function createChatId() {
  // crypto.randomUUID 需要安全上下文（https 或 localhost），本地开发满足条件
  return crypto.randomUUID()
}

/* ------------------------------------------------------------------- 状态 */

const messages = ref([])
const input = ref('')
const streaming = ref(false)
const historyError = ref('')

let controller = null
let uid = 0
const nextId = () => `m${++uid}`

/* ------------------------------------------------------------------ 侧边栏 */

const sessions = ref([])
const sessionsError = ref('')
// 窄屏下侧边栏默认收起，由顶栏那个按钮唤出
const sidebarOpen = ref(false)
// 正在改名的那一项的 id；null 表示没有在改
const editingId = ref('')
const editingTitle = ref('')

async function loadSessions() {
  try {
    sessions.value = await fetchSessions(props.link)
    sessionsError.value = ''
  } catch (err) {
    // 拉不到会话列表不该把页面卡住：给一条提示，用户照样能发消息。
    // 这和「历史拉不到」是同一个取向 —— 侧边栏是锦上添花，不是主流程
    sessionsError.value = friendlyError(err)
  }
}

function startNewSession() {
  if (streaming.value) stop()
  chatId.value = createChatId()
  sessionStorage.setItem(STORAGE_KEY, chatId.value)
  messages.value = []
  input.value = ''
  historyError.value = ''
  stick.value = true
  sidebarOpen.value = false
  nextTick(autoGrow)
}

async function selectSession(id) {
  if (streaming.value) stop()
  if (id === chatId.value) {
    sidebarOpen.value = false
    return
  }
  chatId.value = id
  sessionStorage.setItem(STORAGE_KEY, id)
  messages.value = []
  historyError.value = ''
  sidebarOpen.value = false
  await loadHistory()
}

function beginRename(session) {
  editingId.value = session.conversationId
  editingTitle.value = session.title
  // 让输入框在渲染出来后自动获得焦点并选中，省掉用户再点一下
  nextTick(() => {
    const el = document.querySelector('.rename-input')
    el?.focus()
    el?.select()
  })
}

/**
 * 按 Esc 放弃这次改名。
 *
 * 只清 editingId 就够了：输入框随之被卸载，浏览器会补发一个 blur，
 * 而那个 blur 会被 commitRename 开头的守卫挡掉。
 * （早先这里只写了 {@code editingId = ''} 而 commitRename 没有守卫，
 * 结果是「按 Esc 放弃」反而把改了一半的标题存了下去。）
 */
function cancelRename() {
  editingId.value = ''
}

async function commitRename(session) {
  // 已经不在编辑这一项了就不再处理。这一条同时挡掉两种重复调用：
  // Enter 提交之后输入框被卸载会补发 blur；Esc 取消时也是先清 editingId、再收到 blur
  if (editingId.value !== session.conversationId) return
  editingId.value = ''

  const title = editingTitle.value.trim()
  // 没改、或者改成空，就当没这回事。空标题会让侧边栏出现一行看不见的东西
  if (!title || title === session.title) return

  const previous = session.title
  // 先改本地再发请求：改名的反馈必须是立刻的，否则用户会以为没点中
  session.title = title
  try {
    await renameSession(session.conversationId, title)
  } catch (err) {
    session.title = previous
    sessionsError.value = friendlyError(err)
  }
}

async function removeSession(session) {
  if (!window.confirm(`删除会话「${session.title}」？这段对话的记录会一起删掉。`)) return
  try {
    await deleteSession(session.conversationId)
    sessions.value = sessions.value.filter((s) => s.conversationId !== session.conversationId)
    // 删的正好是当前打开的这个，就顺手开一个新的，免得停在一个已经不存在的会话上
    if (session.conversationId === chatId.value) startNewSession()
  } catch (err) {
    sessionsError.value = friendlyError(err)
  }
}

/* --------------------------------------------------------------- 滚动控制 */

const listEl = ref(null)
// 是否「吸附」在底部。用户手动往上翻看历史时置 false，之后就不再抢他的滚动位置
const stick = ref(true)

function onScroll() {
  const el = listEl.value
  if (!el) return
  // 距底部 80px 以内都算「在底部」，避免像素级抖动导致吸附状态反复横跳
  stick.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function scrollToBottom(force = false) {
  if (!force && !stick.value) return
  // 这里刻意不去改 stick：滚动事件随后会自己触发 onScroll 重新判断。
  // 如果在 nextTick 里把 stick 拨回 true，用户恰好在流式输出中往上翻，
  // 一个排队中的回调就会把他刚解除的吸附状态又打开，表现为「滚上去又被拽下来」。
  nextTick(() => {
    const el = listEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

/* ----------------------------------------------------------- 输入框自适应 */

const taEl = ref(null)
const MAX_TEXTAREA = 160

function autoGrow() {
  const el = taEl.value
  if (!el) return
  // 先塌成 auto 再量 scrollHeight，否则高度只会单调增长、永远缩不回去
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, MAX_TEXTAREA)}px`
}

const canSend = computed(() => !streaming.value && input.value.trim().length > 0)

/* ----------------------------------------------------------------- 发消息 */

async function send(preset) {
  const content = (preset ?? input.value).trim()
  // 生成中不允许再发：后端对同一个会话有并发闸门，同时发第二条会直接报错
  if (!content || streaming.value) return

  input.value = ''
  await nextTick()
  autoGrow()

  // 这一段会话可能还不存在于侧边栏（第一次发言）——先把本地那一项补上，
  // 让用户立刻看到它，而不是等流跑完再刷新列表
  ensureLocalSession(content)

  messages.value.push({ id: nextId(), role: 'user', text: content, steps: [] })
  const reply = newReply()
  messages.value.push(reply)

  streaming.value = true
  scrollToBottom(true)

  controller = new AbortController()

  try {
    await streamChat({
      url: `/api/${props.link}/chat`,
      chatId: chatId.value,
      message: content,
      signal: controller.signal,
      onChatId: (id) => {
        // 以后端返回的为准。正常情况它就是我们传过去的那个，
        // 万一它纠正了 id，后续请求必须跟着它走，否则历史就对不上了。
        if (id !== chatId.value) {
          chatId.value = id
          sessionStorage.setItem(STORAGE_KEY, id)
        }
      },
      onEvent: ({ event, payload }) => {
        handleEvent(event, payload, reply)
        scrollToBottom()
      },
    })
  } catch (err) {
    if (err?.name === 'AbortError') {
      // 用户主动点了「停止」：保留已经显示的部分，只加一个标记，不要整条消息消失。
      // 已经收到收尾事件（FINAL/QUESTION/ERROR）的话就别再打「已停止」了 ——
      // 那只是收完流之后的清理阶段被中断，答案其实是完整的。
      if (!reply.state) reply.stopped = true
    } else {
      // 出错要把原因显示出来，不要静默失败
      reply.failed = true
      reply.state = 'ERROR'
      reply.hint = friendlyError(err)
      if (!reply.text) reply.text = reply.hint
    }
  } finally {
    streaming.value = false
    controller = null
    scrollToBottom()
    // 后端这时才写下会话行（标题取自首轮提问），拉一次让标题和排序对齐
    loadSessions()
  }
}

/**
 * 本地先补一条会话项。
 *
 * 标题和后端不完全一样（后端会截断到 60 字），这是有意的取舍：
 * 为了一个几百毫秒后就会被 loadSessions 纠正的标题，把发送动作卡在一次网络往返上不值得。
 */
function ensureLocalSession(firstQuestion) {
  if (sessions.value.some((s) => s.conversationId === chatId.value)) return
  const now = new Date().toISOString()
  sessions.value.unshift({
    conversationId: chatId.value,
    title: firstQuestion.length > 40 ? `${firstQuestion.slice(0, 40)}…` : firstQuestion,
    createdAt: now,
    updatedAt: now,
  })
}

/**
 * 造一条等待填充的助手消息。
 *
 * <b>必须用 reactive() 包一层，不能直接返回裸对象。</b>messages 是 ref([])，
 * 而 Vue 对 ref 里的对象是「读的时候才转成响应式代理」，push 进去的那个裸对象
 * 和后面从 messages.value[i] 读出来的代理不是同一个东西。send() 里持有的是裸对象，
 * 往它上面写 text 绕过了代理的 set 拦截，一次渲染都不会触发——
 * 表现是整个生成过程中气泡里只有一个光标，直到 finally 里 streaming 变了才整段蹦出来。
 * 这个坑不报任何错，只是「流式看起来不像流式」。
 */
function newReply() {
  return reactive({
    id: nextId(),
    role: 'assistant',
    text: '',
    steps: [],
    state: null,
    stopped: false,
    failed: false,
    hint: '',
    traceOpen: false,
  })
}

/**
 * 处理单个 SSE 事件。
 * 事件语义见接口文档，这里的关键是分清「过程」和「答案」：
 * STEP / TOOL_CALL / TOOL_RESULT / LOOP_SIGNAL / RETRIEVAL 是过程，收进折叠区；
 * TEXT 是过程性的正文，可以边收边显示；FINAL / QUESTION / ERROR 才是收尾。
 */
function handleEvent(event, payload, reply) {
  const text = payload?.text ?? ''

  switch (event) {
    case 'STEP':
    case 'TOOL_CALL':
    case 'TOOL_RESULT':
    case 'LOOP_SIGNAL':
      reply.steps.push({ type: event, text })
      // 有工具调用时自动展开一次，让用户看得见它在干活；
      // 之后是否收起由用户自己决定，不要每次来新步骤都强行弹开
      if (event === 'TOOL_CALL' && reply.steps.length === 1) reply.traceOpen = true
      break

    case 'RETRIEVAL':
      // 开场预检索的结果。放在最前面（它是这一步之前发生的），并且**跳过也要显示** ——
      // 「没查知识库」和「查了没命中」是完全不同的两件事，混在一起就分不出来了
      reply.steps.unshift({ type: 'RETRIEVAL', text })
      break

    case 'TEXT':
      reply.text += text
      break

    case 'FINAL':
      // 关键：FINAL 的 text 是「完整答复」，必须覆盖而不是追加。
      // 智能体跑多步时，中途那些 TEXT 只是过程，不是最终答案；
      // 写成追加的话，最后一段话会被拼两遍。
      reply.text = text
      reply.state = payload?.state || 'FINISHED'
      break

    case 'QUESTION':
      // 它停下来反问用户：把这个问题当成一条正常的 AI 回复展示出来
      reply.text = text
      reply.state = 'WAITING_FOR_USER'
      break

    case 'ERROR':
      reply.text = text || '生成失败。'
      reply.state = 'ERROR'
      reply.failed = true
      break

    default:
      // 后端将来加了新事件类型，不认识的就当正文片段，至少不会丢内容
      if (text) reply.text += text
  }
}

function stop() {
  controller?.abort()
}

/* ----------------------------------------------------------------- 历史回放 */

async function loadHistory() {
  // 记下这次请求是为哪个会话发的。请求回来时它可能已经变了
  const requested = chatId.value

  try {
    const rows = await fetchHistory(props.link, requested)
    // 期间用户切到了别的会话：这份结果属于上一个会话，直接丢掉。
    // 不判的话表现是「头部和会话列表显示的是 B，正文却是 A 的历史」，
    // 而且此时再发一句话，消息会追加到 A 的正文里、却记录在 B 名下 —— 不报任何错
    if (requested !== chatId.value) return

    if (rows.length) {
      // 历史是「按时间正序」的问答对，展开成扁平的消息列表
      messages.value = rows.flatMap(toPair)
    }
    historyError.value = ''
    stick.value = true
    scrollToBottom(true)
  } catch (err) {
    if (requested !== chatId.value) return
    // 后端没起来时拉不到历史，但不该把页面卡住：给一条提示，用户照样能发消息
    historyError.value = friendlyError(err)
  }
}

function toPair(row) {
  const list = [{ id: nextId(), role: 'user', text: row?.question ?? '', steps: [] }]
  if (row?.answer) {
    list.push({
      id: nextId(),
      role: 'assistant',
      text: row.answer,
      steps: historySteps(row.steps),
      // state / steps 只有智能体链路有值，轻语是 null。
      // 用 ?? 而不是 ||，并且不要拿空字符串来判空。
      state: row.state ?? null,
      stopped: false,
      failed: row.state === 'ERROR',
      hint: '',
      traceOpen: false,
    })
  }
  return list
}

/**
 * 历史里的「过程」。
 *
 * 这里曾经有一个 bug：后端 {@code ChatHistoryItem.steps} 是一个**整数**（这一轮走了几步），
 * 而这里按「数组 / 字符串 / 对象」三种形态去归一化，数字落进 `!Array.isArray` 那一支，
 * 结果返回空数组 —— 于是刷新页面之后，智能体的过程永远显示不出来，而且不报任何错。
 *
 * 修法不是「把数字也硬塞成一条步骤」：chat_record 这张表**根本没有存过程的明细**，
 * 它只有步数。所以这里把真实情况说清楚，而不是编一条看起来很真、实际是假的记录。
 */
function historySteps(raw) {
  if (typeof raw === 'number' && raw > 0) {
    return [{ type: 'STEP', text: `这一轮共走了 ${raw} 步（过程明细未入库，看日志或当次对话可见）` }]
  }
  if (typeof raw === 'string' && raw) return [{ type: 'STEP', text: raw }]
  return []
}

/* ------------------------------------------------------------------- 展示 */

const STEP_LABEL = {
  STEP: '步骤',
  TOOL_CALL: '调用',
  TOOL_RESULT: '返回',
  LOOP_SIGNAL: '自检',
  RETRIEVAL: '检索',
}

const stepLabel = (type) => STEP_LABEL[type] || type

const isLastAssistant = (msg) =>
  streaming.value && msg.role === 'assistant' && msg === messages.value[messages.value.length - 1]

/**
 * 助手气泡的正文 HTML。
 *
 * 渲染链路（关 HTML → 消毒 → v-html）的安全性说明在 markdown.js 里，这里只说一句：
 * 光标那个 span 是拼接在**消毒之后**的，是我们自己写死的字符串，不经过模型。
 */
function assistantHtml(msg) {
  const html = renderMarkdown(msg.text)
  return isLastAssistant(msg) ? `${html}<span class="caret"></span>` : html
}

/** 气泡的视觉基调：失败 / 中止 / 等待 / 正常 */
function toneOf(msg) {
  if (msg.role !== 'assistant') return ''
  if (msg.failed || msg.state === 'ERROR') return 'tone-error'
  if (msg.stopped) return 'tone-muted'
  if (msg.state === 'ABORTED') return 'tone-warn'
  if (msg.state === 'WAITING_FOR_USER') return 'tone-ask'
  // 内容安全拦截：和正常回答一样是白底 —— 话术本身就是要读的内容，
  // 给它加底色等于暗示「出问题了」，而它恰恰是产品在按策略正常工作
  return ''
}

/** 气泡下方的小标记，没有就返回 null */
function badgeOf(msg) {
  if (msg.role !== 'assistant') return null
  if (msg.failed || msg.state === 'ERROR') return { label: '生成失败', tone: 'error' }
  if (msg.stopped) return { label: '已停止', tone: 'muted' }
  if (msg.state === 'ABORTED') return { label: '已中止', tone: 'warn' }
  // 不写「已拦截」这种像报错的说法：用户看到的应该是顾问在关心他
  if (msg.state === 'BLOCKED') return { label: '已转为安全提示', tone: 'ask' }
  if (msg.state === 'WAITING_FOR_USER') return { label: '等待你的回答', tone: 'ask' }
  return null
}

/** 等待回答时补一句人话，否则用户不知道该怎么接 */
function hintOf(msg) {
  // 这两条只对智能体有意义：轻语是一问一答，没有「接着做」这回事；
  // 而它在轻语上的 ABORTED 只可能是内容安全拦截，那段话术自己已经说清楚了
  if (props.agent && msg.state === 'WAITING_FOR_USER') {
    return '它在等你回答，直接接着说就行。'
  }
  if (props.agent && msg.state === 'ABORTED' && !msg.stopped) {
    return '任务被中止了（触发循环保护）。可以把要求拆小一点再试。'
  }
  return ''
}

/** fetch 在网络层失败时抛的是 TypeError，给一句明确的话比原样抛英文有用得多 */
function friendlyError(err) {
  const msg = err?.message || ''
  if (err?.name === 'TypeError' || /failed to fetch|networkerror|load failed/i.test(msg)) {
    return '连不上后端服务（http://localhost:8080），请确认 Spring Boot 已经启动。'
  }
  return msg || '请求失败，请稍后重试。'
}

/* --------------------------------------------------------------- 键盘交互 */

function onKeydown(e) {
  if (e.key !== 'Enter') return
  if (e.shiftKey) return // Shift + Enter 换行，交给浏览器默认行为
  if (e.isComposing) return // 中文输入法选词时的回车不能当发送，否则会吃掉候选词
  e.preventDefault()
  send()
}

/* ----------------------------------------------------------------- 生命周期 */

onMounted(async () => {
  const saved = sessionStorage.getItem(STORAGE_KEY)
  chatId.value = saved || createChatId()
  if (!saved) sessionStorage.setItem(STORAGE_KEY, chatId.value)

  autoGrow()
  // 两件事互不依赖，一起发出去，别串行等
  await Promise.all([loadHistory(), loadSessions()])
})

onBeforeUnmount(() => {
  // 离开页面时把在飞的请求掐掉，避免流还挂着、回调打到已经卸载的组件上
  controller?.abort()
})
</script>

<template>
  <div class="shell" :style="{ '--accent': accent, '--accent-soft': accent + '1f' }">
    <!-- ============================ 侧边栏 ============================ -->
    <!-- 遮罩只在窄屏出现，点它收起侧边栏；宽屏下它 display:none，不拦截点击 -->
    <div v-if="sidebarOpen" class="scrim" @click="sidebarOpen = false"></div>

    <aside class="side" :class="{ open: sidebarOpen }">
      <div class="side-head">
        <RouterLink to="/" class="brand" title="返回主页">
          <span class="dot" aria-hidden="true"></span>
          <span class="brand-name">{{ title }}</span>
        </RouterLink>
      </div>

      <button class="new-chat" type="button" @click="startNewSession">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
        新会话
      </button>

      <p v-if="sessionsError" class="side-error">{{ sessionsError }}</p>

      <nav class="sessions">
        <p v-if="!sessions.length && !sessionsError" class="side-empty">还没有历史会话</p>

        <div
          v-for="s in sessions"
          :key="s.conversationId"
          class="session"
          :class="{ active: s.conversationId === chatId }"
        >
          <input
            v-if="editingId === s.conversationId"
            v-model="editingTitle"
            class="rename-input"
            type="text"
            maxlength="60"
            @keydown.enter.prevent="commitRename(s)"
            @keydown.esc="cancelRename"
            @blur="commitRename(s)"
          />
          <button v-else type="button" class="session-title" :title="s.title" @click="selectSession(s.conversationId)">
            {{ s.title }}
          </button>

          <span v-if="editingId !== s.conversationId" class="session-tools">
            <button type="button" class="mini" title="重命名" @click.stop="beginRename(s)">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M12 20h9M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z" />
              </svg>
            </button>
            <button type="button" class="mini danger" title="删除" @click.stop="removeSession(s)">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14" />
              </svg>
            </button>
          </span>
        </div>
      </nav>

      <div class="side-foot">
        <RouterLink to="/knowledge" class="side-link">知识库</RouterLink>
      </div>
    </aside>

    <!-- ============================= 主区 ============================= -->
    <div class="room">
      <header class="bar">
        <button class="icon-btn menu" type="button" title="会话列表" @click="sidebarOpen = !sidebarOpen">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
            <path d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>

        <span class="dot" aria-hidden="true"></span>
        <h1 class="name">{{ title }}</h1>

        <button class="ghost" type="button" @click="startNewSession">新会话</button>
        <!-- 悬停显示完整 id，方便和后端日志对上 -->
        <span class="chatid" :title="chatId">{{ shortId }}</span>
      </header>

      <div ref="listEl" class="list" @scroll.passive="onScroll">
        <div class="column">
          <p v-if="historyError" class="notice">{{ historyError }}</p>

          <div v-if="!messages.length" class="empty">
            <h2>{{ welcome }}</h2>
            <p class="intro">{{ intro }}</p>
            <div class="chips">
              <button v-for="q in examples" :key="q" type="button" class="chip" @click="send(q)">
                {{ q }}
              </button>
            </div>
          </div>

          <div v-for="msg in messages" :key="msg.id" class="row" :class="msg.role">
            <div class="bubble" :class="toneOf(msg)">
              <!-- 过程区：工具调用 / 检索结果，默认折叠，点开看它到底干了什么 -->
              <div v-if="agent && msg.role === 'assistant' && msg.steps.length" class="trace">
                <button type="button" class="trace-toggle" @click="msg.traceOpen = !msg.traceOpen">
                  <svg class="caret-icon" :class="{ open: msg.traceOpen }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M9 18l6-6-6-6" />
                  </svg>
                  过程 {{ msg.steps.length }} 步
                </button>
                <ul v-show="msg.traceOpen" class="trace-list">
                  <li v-for="(s, i) in msg.steps" :key="i" :class="`trace-${s.type.toLowerCase()}`">
                    <span class="trace-type">{{ stepLabel(s.type) }}</span>
                    <span class="trace-text">{{ s.text }}</span>
                  </li>
                </ul>
              </div>

              <!-- 用户消息原样显示：那是用户自己打的字，渲染成 markdown 只会让
                   一句带星号的话突然变成斜体，反而失真 -->
              <div v-if="msg.role === 'user'" class="text user-text">{{ msg.text }}</div>

              <!-- 助手消息按 markdown 渲染。安全性见 markdown.js：
                   先关掉内联 HTML，再用 DOMPurify 消毒，两道都做才允许 v-html -->
              <div
                v-else-if="msg.text || isLastAssistant(msg)"
                class="text md"
                v-html="assistantHtml(msg)"
              ></div>

              <span v-if="badgeOf(msg)" class="badge" :class="`badge-${badgeOf(msg).tone}`">
                {{ badgeOf(msg).label }}
              </span>
            </div>

            <p v-if="hintOf(msg)" class="hint">{{ hintOf(msg) }}</p>
          </div>
        </div>
      </div>

      <div class="composer">
        <div class="composer-inner">
          <div class="box">
            <textarea
              ref="taEl"
              v-model="input"
              rows="1"
              :placeholder="placeholder"
              @input="autoGrow"
              @keydown="onKeydown"
            ></textarea>

            <!-- 生成中时按钮变「停止」：既给了中断手段，也顺手挡住同一会话的并发发送 -->
            <button v-if="streaming" type="button" class="send stop" @click="stop">停止</button>
            <button v-else type="button" class="send" :disabled="!canSend" @click="send()">发送</button>
          </div>
          <p class="tip">Enter 发送，Shift + Enter 换行</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100dvh;
  background: var(--page);
}

/* ---------------------------------------------------------------- 侧边栏 */

.side {
  display: flex;
  flex-direction: column;
  flex: none;
  width: 264px;
  border-right: 1px solid var(--line);
  background: #fbfbfc;
}

.side-head {
  padding: 14px 16px 6px;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--ink);
  text-decoration: none;
}
.brand-name {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.01em;
}

.new-chat {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin: 8px 12px 10px;
  padding: 9px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: #3d4653;
  font-size: 13.5px;
  transition: border-color 0.18s ease, color 0.18s ease, background 0.18s ease;
}
.new-chat:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}
.new-chat svg {
  width: 15px;
  height: 15px;
}

.side-error {
  margin: 0 14px 8px;
  color: #b0322f;
  font-size: 12px;
  line-height: 1.5;
}

.sessions {
  flex: 1;
  overflow-y: auto;
  padding: 2px 8px 8px;
}

.side-empty {
  padding: 18px 8px;
  color: #a0a7b3;
  font-size: 12.5px;
  text-align: center;
}

.session {
  display: flex;
  align-items: center;
  gap: 4px;
  border-radius: 9px;
  transition: background 0.15s ease;
}
.session:hover {
  background: #eef0f4;
}
.session.active {
  background: var(--accent-soft);
}

.session-title {
  flex: 1;
  min-width: 0;
  padding: 8px 10px;
  border: none;
  background: none;
  color: #3d4653;
  font-size: 13px;
  text-align: left;
  /* 标题一行放不下就省略，不要把侧边栏撑宽或者换行 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session.active .session-title {
  color: var(--accent);
  font-weight: 500;
}

.rename-input {
  flex: 1;
  min-width: 0;
  margin: 3px 4px;
  padding: 5px 8px;
  border: 1px solid var(--accent);
  border-radius: 7px;
  background: #fff;
  font: inherit;
  font-size: 13px;
  outline: none;
}

.session-tools {
  display: none;
  flex: none;
  gap: 1px;
  padding-right: 5px;
}
.session:hover .session-tools,
.session.active .session-tools {
  display: flex;
}

.mini {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 6px;
  background: none;
  color: #8b94a3;
}
.mini:hover {
  background: #fff;
  color: var(--ink);
}
.mini.danger:hover {
  color: #b0322f;
}
.mini svg {
  width: 13px;
  height: 13px;
}

.side-foot {
  flex: none;
  padding: 10px 14px 14px;
  border-top: 1px solid var(--line);
}

.side-link {
  color: #6b7381;
  font-size: 12.5px;
  text-decoration: none;
}
.side-link:hover {
  color: var(--accent);
}

/* 窄屏时侧边栏浮在内容之上，靠遮罩点击收起 */
.scrim {
  display: none;
}

/* ------------------------------------------------------------------ 主区 */

.room {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 20px;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--line);
}

.icon-btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 9px;
  background: none;
  color: #5b6472;
}
.icon-btn:hover {
  background: #eef0f4;
}
.icon-btn svg {
  width: 17px;
  height: 17px;
}

/* 宽屏下侧边栏常驻，菜单按钮没有意义 */
.menu {
  display: none;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.name {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.01em;
}

.chatid {
  margin-left: auto;
  padding: 3px 9px;
  border-radius: 999px;
  background: #eef0f4;
  color: #77808f;
  font-size: 11px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  cursor: default;
}

.ghost {
  padding: 5px 11px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  font-size: 12.5px;
  color: #46505f;
  transition: border-color 0.18s ease, color 0.18s ease, background 0.18s ease;
}
.ghost:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}

/* -------------------------------------------------------------- 消息列表 */

.list {
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 26px 20px 10px;
}

.column {
  max-width: 820px;
  margin: 0 auto;
}

.notice {
  margin-bottom: 16px;
  padding: 10px 14px;
  border: 1px solid #f0d8b0;
  border-radius: 10px;
  background: #fffaf1;
  color: #8a6220;
  font-size: 13px;
}

.row {
  display: flex;
  flex-direction: column;
  margin-bottom: 18px;
}
.row.user {
  align-items: flex-end;
}
.row.assistant {
  align-items: flex-start;
}

.bubble {
  max-width: min(80%, 660px);
  padding: 11px 15px;
  border-radius: 16px;
  font-size: 15px;
  line-height: 1.7;
}

.row.user .bubble {
  background: var(--accent);
  color: #fff;
  border-bottom-right-radius: 5px;
}

.row.assistant .bubble {
  background: #fff;
  color: var(--ink);
  border: 1px solid var(--line);
  border-bottom-left-radius: 5px;
  box-shadow: 0 1px 2px rgba(16, 20, 30, 0.04);
}

.row.assistant .bubble.tone-warn {
  border-color: #eccb99;
  background: #fffbf4;
}
.row.assistant .bubble.tone-error {
  border-color: #eeb6b6;
  background: #fff8f8;
}
.row.assistant .bubble.tone-ask {
  border-color: var(--accent);
  background: #fff;
}
.row.assistant .bubble.tone-muted {
  background: #fbfbfc;
}

.text {
  overflow-wrap: anywhere;
}
.user-text {
  white-space: pre-wrap;
}

/* 生成中的闪烁光标，让用户知道它还在输出 */
.caret {
  display: inline-block;
  width: 2px;
  height: 1.05em;
  margin-left: 2px;
  background: var(--accent);
  vertical-align: text-bottom;
  animation: blink 1.1s steps(2, start) infinite;
}
@keyframes blink {
  to {
    visibility: hidden;
  }
}

.badge {
  display: inline-block;
  margin-top: 8px;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 1.7;
  background: #eef0f4;
  color: #5b6472;
}
.badge-error {
  background: #fdeaea;
  color: #b0322f;
}
.badge-warn {
  background: #fdf1dd;
  color: #93631a;
}
.badge-ask {
  background: var(--accent-soft);
  color: var(--accent);
}

.hint {
  max-width: min(80%, 660px);
  margin-top: 6px;
  padding-left: 4px;
  color: #838c9b;
  font-size: 12.5px;
  line-height: 1.6;
}

/* ------------------------------------------------------- markdown 正文样式 */

/*
 * v-html 插进来的内容不在本组件的样式作用域里，必须用 :deep() 才管得到。
 * 不写这些的话，模型输出的标题会是浏览器默认的巨号字、代码块没有背景，
 * 看起来像页面坏了。
 */
.md :deep(> *:first-child) {
  margin-top: 0;
}
.md :deep(> *:last-child) {
  margin-bottom: 0;
}
.md :deep(p) {
  margin: 0 0 10px;
}
.md :deep(h1),
.md :deep(h2),
.md :deep(h3),
.md :deep(h4) {
  margin: 16px 0 8px;
  font-weight: 600;
  line-height: 1.4;
}
.md :deep(h1) {
  font-size: 18px;
}
.md :deep(h2) {
  font-size: 17px;
}
.md :deep(h3),
.md :deep(h4) {
  font-size: 15.5px;
}
.md :deep(ul),
.md :deep(ol) {
  margin: 0 0 10px;
  padding-left: 22px;
}
.md :deep(li) {
  margin: 3px 0;
}
.md :deep(blockquote) {
  margin: 10px 0;
  padding: 2px 0 2px 12px;
  border-left: 3px solid var(--line);
  color: #6b7381;
}
.md :deep(a) {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 2px;
}
.md :deep(code) {
  padding: 1.5px 5px;
  border-radius: 5px;
  background: #f2f3f6;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.88em;
}
.md :deep(pre) {
  margin: 10px 0;
  padding: 11px 13px;
  border-radius: 10px;
  background: #f7f8fa;
  border: 1px solid var(--line);
  overflow-x: auto;
}
/* 代码块里的 code 不要再套一层底色和内边距，否则会出现双层框 */
.md :deep(pre code) {
  padding: 0;
  background: none;
  font-size: 0.86em;
  line-height: 1.6;
}
.md :deep(table) {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  font-size: 14px;
}
.md :deep(th),
.md :deep(td) {
  padding: 6px 10px;
  border: 1px solid var(--line);
  text-align: left;
}
.md :deep(th) {
  background: #f7f8fa;
  font-weight: 600;
}
.md :deep(hr) {
  margin: 14px 0;
  border: none;
  border-top: 1px solid var(--line);
}

/* highlight.js 的配色（自己写，不引它的主题包：只需要几个 token） */
.md :deep(.hljs-comment),
.md :deep(.hljs-quote) {
  color: #8b94a3;
  font-style: italic;
}
.md :deep(.hljs-keyword),
.md :deep(.hljs-selector-tag),
.md :deep(.hljs-literal) {
  color: #a626a4;
}
.md :deep(.hljs-string),
.md :deep(.hljs-attr),
.md :deep(.hljs-addition) {
  color: #0a7d55;
}
.md :deep(.hljs-number),
.md :deep(.hljs-built_in) {
  color: #b06400;
}
.md :deep(.hljs-title),
.md :deep(.hljs-function),
.md :deep(.hljs-section) {
  color: #1a6fd4;
}

/* ------------------------------------------------------------ 工具调用过程 */

.trace {
  margin-bottom: 10px;
  padding-bottom: 10px;
  border-bottom: 1px dashed var(--line);
}

.trace-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0;
  border: none;
  background: none;
  color: #77808f;
  font-size: 12px;
}
.trace-toggle:hover {
  color: var(--accent);
}

.caret-icon {
  width: 12px;
  height: 12px;
  transition: transform 0.18s ease;
}
.caret-icon.open {
  transform: rotate(90deg);
}

.trace-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 9px 0 0;
  padding: 0;
  list-style: none;
}

.trace-list li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: #77808f;
  font-size: 12px;
  line-height: 1.55;
}

.trace-type {
  flex: none;
  min-width: 30px;
  padding: 0 6px;
  border-radius: 4px;
  background: #eef0f4;
  color: #5b6472;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}
.trace-tool_call .trace-type {
  background: var(--accent-soft);
  color: var(--accent);
}
/* 检索那条用青绿标出来：它和工具调用不是一回事，
   用户要能一眼看到「知识库到底查了没有」 */
.trace-retrieval .trace-type {
  background: #e2f3ee;
  color: #0a7d55;
}
.trace-loop_signal .trace-type {
  background: #fdf1dd;
  color: #93631a;
}

.trace-text {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

/* ---------------------------------------------------------------- 空状态 */

.empty {
  padding: 44px 0 12px;
  text-align: center;
}

.empty h2 {
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 0.01em;
}

.intro {
  max-width: 34em;
  margin: 10px auto 0;
  color: #6b7381;
  font-size: 14px;
  line-height: 1.75;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 9px;
  margin-top: 26px;
}

.chip {
  padding: 8px 14px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: #fff;
  color: #46505f;
  font-size: 13px;
  text-align: left;
  transition: border-color 0.18s ease, color 0.18s ease, background 0.18s ease, transform 0.18s ease;
}
.chip:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
  transform: translateY(-1px);
}

/* ---------------------------------------------------------------- 输入区 */

.composer {
  padding: 10px 20px 16px;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(10px);
  border-top: 1px solid var(--line);
}

.composer-inner {
  max-width: 820px;
  margin: 0 auto;
}

.box {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 7px 7px 7px 14px;
  border: 1px solid #dfe2e8;
  border-radius: 14px;
  background: #fff;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}
.box:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

textarea {
  flex: 1;
  min-height: 26px;
  max-height: 160px;
  padding: 5px 0;
  border: none;
  outline: none;
  background: transparent;
  color: inherit;
  font: inherit;
  line-height: 1.6;
  resize: none;
  overflow-y: auto;
}
textarea::placeholder {
  color: #a3aab6;
}

.send {
  flex: none;
  padding: 8px 18px;
  border: none;
  border-radius: 10px;
  background: var(--accent);
  color: #fff;
  font-size: 14px;
  transition: opacity 0.18s ease, filter 0.18s ease;
}
.send:hover:not(:disabled) {
  filter: brightness(1.07);
}
.send:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.stop {
  background: #fff;
  color: #46505f;
  border: 1px solid #dfe2e8;
}
.stop:hover {
  border-color: #b0322f;
  color: #b0322f;
  filter: none;
}

.tip {
  margin-top: 7px;
  padding-left: 3px;
  color: #a0a7b3;
  font-size: 11.5px;
}

/* ------------------------------------------------------------------ 窄屏 */

@media (max-width: 900px) {
  /* 侧边栏改成抽屉：默认挪到屏幕外，靠菜单按钮唤出 */
  .side {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: 20;
    transform: translateX(-100%);
    transition: transform 0.22s ease;
    box-shadow: 0 0 40px rgba(16, 20, 30, 0.14);
  }
  .side.open {
    transform: translateX(0);
  }
  .scrim {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 10;
    background: rgba(16, 20, 30, 0.32);
  }
  .menu {
    display: grid;
  }
}

@media (max-width: 640px) {
  .list,
  .composer {
    padding-left: 12px;
    padding-right: 12px;
  }
  .bubble {
    max-width: 88%;
  }
  .chatid {
    display: none;
  }
}
</style>
