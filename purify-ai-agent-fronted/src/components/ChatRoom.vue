<script setup>
import { ref, reactive, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { fetchHistory, fetchSessions, renameSession, deleteSession } from '../api/http.js'
import { streamChat } from '../api/sse.js'
import { renderMarkdown } from '../markdown.js'
import { SLIM, MANUS } from '../chatConfig.js'
import { theme, enterChatPage, leaveChatPage } from '../theme.js'
import { isEnglish, message, rawMessage, resolveMessage, t } from '../i18n/index.js'
import UserMenu from './UserMenu.vue'

/**
 * 轻语和 PurifyManus 共用的聊天室。
 *
 * 两条链路的交互（气泡方向、流式打字、停止、会话 id、历史回放、侧边栏）完全一致，
 * 差别只有四处：接口路径、主题色、要不要展示工具调用过程、文案。
 * 所以做成一个组件、用 props 区分，而不是复制两份 —— 复制出来的两份迟早会走样。
 */
const props = defineProps({
  /** 'slim' | 'manus'，同时决定接口路径 /api/{link}/chat 和会话列表的入口名 */
  link: { type: String, required: true },
  /** 主题色，用户气泡和高亮都用它 */
  accent: { type: String, required: true },
  /** 智能体链路：额外展示工具调用过程、区分 WAITING_FOR_USER / ABORTED */
  agent: { type: Boolean, default: false },
})

const { t: $t, tm, rt } = useI18n()

/**
 * 这一条链路的文案。
 *
 * **必须是 computed，而且必须逐个声明成顶层常量。**
 *
 * 一、不能写成 `const copy = { title: t(...) }`：`t()` 在**求值那一刻**取当前语言，
 * 这样写出来的对象会冻在初始语言里——表现是整页都英文了，只有欢迎语和示例问题
 * 还是中文（而这几处恰恰是空会话时唯一可见的东西）。
 *
 * 二、不能写成 `const copy = { title: computed(...) }` 然后在模板里用 `copy.title`：
 * 模板只对**顶层**的 ref 做自动解包，`copy` 是个普通对象，`copy.title` 拿到的是
 * ref 本身而不是它的值，渲染出来会是 `[object Object]`。
 *
 * 键名就是 `link`，所以加一条链路不用动这个文件，加一组 `chat.<link>.*` 就行。
 */
const title = computed(() => t(`chat.${props.link}.title`))
const welcome = computed(() => t(`chat.${props.link}.welcome`))
const intro = computed(() => t(`chat.${props.link}.intro`))
const placeholder = computed(() => t(`chat.${props.link}.placeholder`))
// 数组要用 tm + rt 取。rt 在「消息已被编译成函数」的那套构建下才是必需的，
// 在另一套构建下是恒等函数——两边都写一遍，就不用关心用的是哪种构建。
// 不要写成 t('...examples[0]')：那靠的是路径解析器的下标语法，能work但不保证
const examples = computed(() => tm(`chat.${props.link}.examples`).map((item) => rt(item)))

/* -------------------------------------------------------------------- 主题 */

/**
 * 深色开关。
 *
 * 这里**在 setup 里同步调** `enterChatPage()`，而不是放到 onMounted：
 * mounted 的时候首帧已经画完了，深色用户每次刷新都会先闪一下浅色。
 * setup 跑在组件第一次渲染之前，正好。
 *
 * 离开时必须摘掉（onBeforeUnmount），否则用户从对话页回到首页，
 * 首页会顶着一个 `theme-dark` 的类——而首页并没有深色样式。
 */
enterChatPage()

const dark = computed(() => theme.value === 'dark')

/* ------------------------------------------------------------------ 会话 id */

// 用 sessionStorage 而不是 localStorage：刷新页面要接着刚才那段聊，
// 但新开一个标签页应当是一个新会话。（登录态则相反，那个要长期不变，见 auth.js。）
//
// 这个键的前缀必须和 auth.js 里的 CHAT_ID_PREFIX 一致——退出登录时它按前缀清理，
// 对不上的话换个人登录会继承上一个人的 chatId，打开就是「会话不存在」
const STORAGE_KEY = `purify:chatId:${props.link}`

const chatId = ref('')
const shortId = computed(() => chatId.value.slice(0, 8))

function createChatId() {
  // crypto.randomUUID 需要安全上下文（https 或 localhost），本地开发满足条件
  return crypto.randomUUID()
}

/* --------------------------------------------------------------- 侧栏折叠 */

/**
 * 侧边栏是否折叠成细窄条。
 *
 * 存在 localStorage 而不是 sessionStorage：这是**用户对这个界面的偏好**，
 * 和会话 id 不一样——它不该因为开个新标签页就变回去。
 */
const COLLAPSE_KEY = 'purify:sidebarCollapsed'

const collapsed = ref(false)

function toggleCollapse() {
  collapsed.value = !collapsed.value
  try {
    localStorage.setItem(COLLAPSE_KEY, collapsed.value ? '1' : '0')
  } catch {
    // 存不下就退化成「本次会话内有效」，不影响使用
  }
}

/**
 * 是不是窄屏（侧边栏变成抽屉、默认收在画外）。
 *
 * 用途只有一个：决定顶栏要不要放那个用户菜单。侧边栏可见时它底部已经有了，
 * 顶栏再放一个就是同一个东西出现两次；而侧边栏不可见时（折叠了，或者窄屏收起来了）
 * 没有它用户就没法退出登录。
 *
 * 断点必须和样式里那个 `@media (max-width: 900px)` 一致——两处对不上的话，
 * 会出现「侧边栏收在画外，顶栏也没有菜单」的窗口，用户找不到退出按钮。
 */
const NARROW_QUERY = '(max-width: 900px)'

const narrow = ref(false)
let mediaQuery = null

function onMediaChange(event) {
  narrow.value = event.matches
}

/**
 * 另一条链路的信息。侧边栏顶部那个切换按钮用它。
 *
 * 从 `chatConfig.js` 取而不是写死：主题色和标题在那边定义过一次，
 * 再抄一份到这里迟早会漂移（改了主页卡片的颜色，切换按钮还是旧的）
 */
const otherLink = computed(() => (props.link === SLIM.link ? MANUS : SLIM))

/** 另一条链路的显示名。它跟着语言走，所以标题也得在这儿翻一次（理由同上） */
const otherLinkTitle = computed(() => t(`chat.${otherLink.value.link}.title`))

/* ------------------------------------------------------------------- 状态 */

const messages = ref([])
const input = ref('')
const streaming = ref(false)
/**
 * 历史拉取失败的提示。**存描述符不存句子**（见 i18n/index.js 的 message()）：
 * 存句子的话，中文时拉一次失败、再切到英文，那句提示会一直是中文，
 * 而它恰恰是用户此刻唯一看得见的东西。
 */
const historyError = ref(null)


let controller = null
let uid = 0
const nextId = () => `m${++uid}`

/**
 * 把两个「存描述符的 ref」翻成给模板看的句子。
 *
 * 写成 computed 而不是在模板里现调 `resolveMessage`：模板里那两处都有 `v-if`，
 * 现调会让「有没有提示」和「提示是什么」是两次求值，中间万一变了会闪。
 * 顺带也把 `v-if` 的判据统一成「翻完是不是空串」，空串的键和空串的原文都算没有。
 */
const historyErrorText = computed(() => resolveMessage(historyError.value))
const sessionsErrorText = computed(() => resolveMessage(sessionsError.value))

/* ------------------------------------------------------------------ 侧边栏 */

const sessions = ref([])
const sessionsError = ref(null)
// 窄屏下侧边栏默认收起，由顶栏那个按钮唤出
const sidebarOpen = ref(false)
// 正在改名的那一项的 id；null 表示没有在改
const editingId = ref('')
const editingTitle = ref('')

async function loadSessions() {
  try {
    sessions.value = await fetchSessions(props.link)
    sessionsError.value = null
  } catch (err) {
    // 拉不到会话列表不该把页面卡住：给一条提示，用户照样能发消息。
    // 这和「历史拉不到」是同一个取向 —— 侧边栏是锦上添花，不是主流程
    sessionsError.value = friendlyError(err)
  }
}

/**
 * 开一个新会话。
 *
 * @param {object} [opts]
 * @param {boolean} [opts.keepNotice] 保留当前那条提示。给 loadHistory 的 404 分支用——
 *        它刚写下「这个会话已经不存在了」，而清空提示就在下一行，用户什么都看不到
 */
function startNewSession(opts = {}) {
  if (streaming.value) stop()
  chatId.value = createChatId()
  // 这里**不写 sessionStorage**，见 persistChatId 的说明
  messages.value = []
  input.value = ''
  if (!opts.keepNotice) historyError.value = null
  stick.value = true
  sidebarOpen.value = false
  nextTick(autoGrow)
}

/**
 * 把当前 chatId 记进 sessionStorage。
 *
 * **只在真的要发消息时才调**，不在新会话创建时调。差别很具体：
 * 会话行是发第一条消息时由后端建出来的（`touch`），所以「建了 chatId 但没说话」
 * 的会话在库里根本不存在。如果创建时就落盘，那么用户打开页面、什么都没发、
 * 刷新一下——这一次 `restored` 为真，会去拉历史，然后拿到 404，
 * 界面上弹出一句「这个会话已经不存在了」，而用户其实什么都没做错。
 *
 * 只在发送时落盘，「本地有 id」就等价于「这个会话真的存在过」，那个误报就没有了。
 */
function persistChatId() {
  try {
    sessionStorage.setItem(STORAGE_KEY, chatId.value)
  } catch {
    // 存不下就退化成「刷新后开新会话」。不影响当前这次对话
  }
}

async function selectSession(id) {
  if (streaming.value) stop()
  if (id === chatId.value) {
    sidebarOpen.value = false
    return
  }
  chatId.value = id
  // 从侧边栏点进来的会话一定存在于库里（列表是后端给的），所以这里可以直接记
  sessionStorage.setItem(STORAGE_KEY, id)
  messages.value = []
  historyError.value = null
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
  if (!window.confirm(t('chat.side.deleteConfirm', { title: session.title }))) return
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
        }
        // 走到这里说明这一轮真的发出去了，会话行已经由后端建出来 ——
        // 这才是该把 id 记下来的时机，见 persistChatId
        persistChatId()
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
      // 存描述符。这里**不把它抄进 reply.text**：text 是正文，
      // 抄进去就等于把错误文案冻在那一刻的语言里了。气泡里没有正文时
      // 由 assistantHtml 兜底去读 hint，这样切语言它也跟着变
      reply.hint = friendlyError(err)
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
    /** 失败原因，存的是描述符不是句子（见 i18n/index.js 的 message()） */
    hint: null,
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
      // 后端给的 text 是散文，原样显示；它没给的话才是本地那句兜底
      reply.text = text
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
    historyError.value = null
    stick.value = true
    scrollToBottom(true)
  } catch (err) {
    if (requested !== chatId.value) return
    // 404 单独处理：这个会话不存在、已被删除，**或者不属于当前账号**。
    // 本地却还记着它的 id（比如换了个账号登录，或者会话在另一个标签页被删了），
    // 这时留着这个 id 只会一直报错 —— 直接开一个新会话，
    // 并把刚才那句话留在提示条上告诉用户发生了什么
    if (err?.status === 404) {
      historyError.value = message('chat.history.gone')
      startNewSession({ keepNotice: true })
      return
    }
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
      hint: null,
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
    return [{ type: 'STEP', text: t('chat.trace.summary', { n: raw }) }]
  }
  if (typeof raw === 'string' && raw) return [{ type: 'STEP', text: raw }]
  return []
}

/* ------------------------------------------------------------------- 展示 */

/**
 * 过程条目左边那个类型标签。
 *
 * 存的是**键**不是文案：这个映射原先是一张写死中文的表，切语言之后
 * 过程区里每一行都还是中文。存键、取值时再翻，两件事就分开了。
 * 认不出的类型直接回退成类型名本身（后端将来加了新事件类型时不至于空着）。
 */
const STEP_LABEL_KEYS = {
  STEP: 'chat.trace.type.step',
  TOOL_CALL: 'chat.trace.type.toolCall',
  TOOL_RESULT: 'chat.trace.type.toolResult',
  LOOP_SIGNAL: 'chat.trace.type.loopSignal',
  RETRIEVAL: 'chat.trace.type.retrieval',
}

const stepLabel = (type) => (STEP_LABEL_KEYS[type] ? $t(STEP_LABEL_KEYS[type]) : type)

const isLastAssistant = (msg) =>
  streaming.value && msg.role === 'assistant' && msg === messages.value[messages.value.length - 1]

/**
 * 助手气泡的正文 HTML。
 *
 * 渲染链路（关 HTML → 消毒 → v-html）的安全性说明在 markdown.js 里，这里只说一句：
 * 光标那个 span 是拼接在**消毒之后**的，是我们自己写死的字符串，不经过模型。
 */
function assistantHtml(msg) {
  // 正文为空时按优先级兜底：失败原因 → 一句「生成失败」→ 空。
  // **最后那支必须存在**：流式刚开始、一个字都还没到的时候正文就是空的，
  // 那时候渲染出来的应当只有光标，不是一句「生成失败」。
  //
  // 这三处都是**渲染期**取值，所以切语言它们跟着变
  // （send() 里刻意没有把 hint 抄进 text，抄进去就冻住了）
  const source =
    msg.text ||
    (msg.hint ? resolveMessage(msg.hint) : msg.failed ? t('chat.fallbackAnswer') : '')
  const html = renderMarkdown(source)
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

/**
 * 气泡下方的小标记，没有就返回 null。
 *
 * 返回的 `label` 是**渲染期翻好的**（模板里每次渲染都调一次这个函数），
 * 所以切语言它跟着变，不需要额外做什么。
 */
function badgeOf(msg) {
  if (msg.role !== 'assistant') return null
  if (msg.failed || msg.state === 'ERROR') return { label: $t('chat.badge.failed'), tone: 'error' }
  if (msg.stopped) return { label: $t('chat.badge.stopped'), tone: 'muted' }
  if (msg.state === 'ABORTED') return { label: $t('chat.badge.aborted'), tone: 'warn' }
  // 不写「已拦截」这种像报错的说法：用户看到的应该是顾问在关心他
  if (msg.state === 'BLOCKED') return { label: $t('chat.badge.blocked'), tone: 'ask' }
  if (msg.state === 'WAITING_FOR_USER') return { label: $t('chat.badge.waiting'), tone: 'ask' }
  return null
}

/** 等待回答时补一句人话，否则用户不知道该怎么接 */
function hintOf(msg) {
  // 这两条只对智能体有意义：轻语是一问一答，没有「接着做」这回事；
  // 而它在轻语上的 ABORTED 只可能是内容安全拦截，那段话术自己已经说清楚了
  if (props.agent && msg.state === 'WAITING_FOR_USER') {
    return $t('chat.hint.waiting')
  }
  if (props.agent && msg.state === 'ABORTED' && !msg.stopped) {
    return $t('chat.hint.aborted')
  }
  return ''
}

/**
 * 把一次失败整理成一条提示。
 *
 * 返回的是**描述符**（见 i18n/index.js 的 message()），不是句子：
 * 它会存进 ref 或者挂在消息上，而那些地方都不会因为切语言而重算。
 *
 * fetch 在网络层失败时抛的是 TypeError，这时 `err.message` 是一句
 * "Failed to fetch"，对用户毫无意义，换成一句能照着做的话（去看后端起没起）。
 */
function friendlyError(err) {
  const text = err?.message || ''
  if (err?.name === 'TypeError' || /failed to fetch|networkerror|load failed/i.test(text)) {
    return message('error.offline')
  }
  return text ? rawMessage(text) : message('error.generic')
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
  let saved = null
  try {
    saved = sessionStorage.getItem(STORAGE_KEY)
  } catch {
    // 读不出来就当作新会话
  }
  // 有本地记录才叫「恢复」。它等价于「这个会话发过消息」——
  // 因为 chatId 只在发送成功后才落盘（见 persistChatId）
  const restored = Boolean(saved)
  chatId.value = saved || createChatId()

  try {
    collapsed.value = localStorage.getItem(COLLAPSE_KEY) === '1'
  } catch {
    // 读不出来就用默认的展开态
  }

  // matchMedia 而不是监听 window.resize：只在跨越断点时才触发，
  // 而 resize 在拖动窗口时会每秒触发几十次
  mediaQuery = window.matchMedia(NARROW_QUERY)
  narrow.value = mediaQuery.matches
  mediaQuery.addEventListener('change', onMediaChange)

  autoGrow()

  // **新会话不要去拉历史。** 后端现在会校验归属，一个还没建出来的 chatId
  // 必然返回 404，于是每次打开聊天页都会先闪一条「会话不存在」的错误。
  // 只有从 sessionStorage 恢复出来的（或从侧边栏点开的）才值得去问一次
  if (restored) {
    await Promise.all([loadHistory(), loadSessions()])
  } else {
    await loadSessions()
  }
})

onBeforeUnmount(() => {
  // 离开页面时把在飞的请求掐掉，避免流还挂着、回调打到已经卸载的组件上
  controller?.abort()
  mediaQuery?.removeEventListener('change', onMediaChange)
  // 主题只作用于对话页，走了就摘掉，别让首页也顶着深色的类
  leaveChatPage()
})
</script>

<template>
  <div
    class="shell"
    :class="{ dark }"
    :style="{ '--accent': accent, '--accent-soft': accent + '1f' }"
  >
    <!-- ============================ 侧边栏 ============================ -->
    <!-- 遮罩只在窄屏出现，点它收起侧边栏；宽屏下它 display:none，不拦截点击 -->
    <div v-if="sidebarOpen" class="scrim" @click="sidebarOpen = false"></div>

    <aside class="side" :class="{ open: sidebarOpen, collapsed }">
      <div class="side-head">
        <RouterLink v-if="!collapsed" to="/" class="brand" :title="$t('chat.side.backHome')">
          <span class="dot" aria-hidden="true"></span>
          <span class="brand-name">{{ title }}</span>
        </RouterLink>
        <span v-else class="dot solo" aria-hidden="true"></span>

        <!-- 折叠开关。宽屏才显示：窄屏用的是顶栏那个抽屉按钮，
             两个都留会让人分不清按哪个 -->
        <button
          class="collapse-btn"
          type="button"
          :title="collapsed ? $t('chat.side.expand') : $t('chat.side.collapse')"
          :aria-expanded="!collapsed"
          @click="toggleCollapse"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <rect x="3" y="4" width="18" height="16" rx="2" />
            <path d="M9 4v16" />
          </svg>
        </button>
      </div>

      <!-- 链路切换。两条链路以前各占首页一张卡片，现在只差一次点击——
           这也是它存在的理由：用轻语聊到一半想交给 PurifyManus，不用先回首页 -->
      <RouterLink
        v-if="!collapsed"
        class="switch"
        :to="`/${otherLink.link}`"
        :style="{ '--sw': otherLink.accent }"
        :title="$t('chat.side.switchTo', { name: otherLinkTitle })"
      >
        <span class="switch-dot" aria-hidden="true"></span>
        <span class="switch-text">
          <span class="switch-label">{{ $t('chat.side.switchLabel') }}</span>
          <span class="switch-name">{{ otherLinkTitle }}</span>
        </span>
        <svg class="switch-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M5 12h14M13 6l6 6-6 6" />
        </svg>
      </RouterLink>

      <button class="new-chat" type="button" :title="collapsed ? $t('chat.side.newChat') : null" @click="startNewSession">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
        <span v-if="!collapsed">{{ $t('chat.side.newChat') }}</span>
      </button>

      <p v-if="sessionsErrorText" class="side-error">{{ sessionsErrorText }}</p>

      <nav class="sessions">
        <p v-if="!sessions.length && !sessionsErrorText" class="side-empty">{{ $t('chat.side.noSessions') }}</p>

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
            <button type="button" class="mini" :title="$t('chat.side.rename')" @click.stop="beginRename(s)">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M12 20h9M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z" />
              </svg>
            </button>
            <button type="button" class="mini danger" :title="$t('chat.side.delete')" @click.stop="removeSession(s)">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14" />
              </svg>
            </button>
          </span>
        </div>
      </nav>

      <!-- 侧边栏底部：用户信息 + 退出登录。
           以前这里是一个光秃秃的「知识库」链接，现在它收进了用户菜单里
           （而且只对超级用户显示，见 UserMenu） -->
      <div class="side-foot">
        <UserMenu direction="up" />
      </div>
    </aside>

    <!-- ============================= 主区 ============================= -->
    <div class="room">
      <header class="bar">
        <button class="icon-btn menu" type="button" :title="$t('chat.side.sessions')" @click="sidebarOpen = !sidebarOpen">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
            <path d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>

        <span class="dot" aria-hidden="true"></span>
        <h1 class="name">{{ title }}</h1>

        <button class="ghost" type="button" @click="startNewSession">{{ $t('chat.side.newChat') }}</button>
        <!-- 悬停显示完整 id，方便和后端日志对上。折叠侧栏时它也跟着收起来 ——
             那时候底部那个用户菜单看不见，顶栏这个得顶上 -->
        <span class="chatid" :title="chatId">{{ shortId }}</span>
        <!-- 侧边栏看得见时它底部已经有一个了，这里不重复放 -->
        <UserMenu v-if="collapsed || narrow" direction="down" />
      </header>

      <div ref="listEl" class="list" @scroll.passive="onScroll">
        <div class="column">
          <p v-if="historyErrorText" class="notice">{{ historyErrorText }}</p>

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
                  <!-- 用复数形式（t 的第三个参数是 count）：中文两档一样，
                       英文才会正确地在 1 步时说 step 而不是 steps -->
                  {{ $t('chat.trace.steps', { n: msg.steps.length }, msg.steps.length) }}
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
              <!-- 后两个条件不能省：正文为空但有失败原因（或整条就是「生成失败」）时，
                   气泡里得有东西可显示。只看 msg.text 的话，一次「还没出字就断了」
                   的错误会渲染成一个空气泡，看着像界面坏了 -->
              <div
                v-else-if="msg.text || msg.hint || msg.failed || isLastAssistant(msg)"
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
            <button v-if="streaming" type="button" class="send stop" @click="stop">{{ $t('chat.composer.stop') }}</button>
            <button v-else type="button" class="send" :disabled="!canSend" @click="send()">{{ $t('chat.composer.send') }}</button>
          </div>
          <p class="tip">{{ $t('chat.composer.tip') }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
 * 颜色 token。
 *
 * 深色主题的做法是**两层**：
 *
 *   1. 复用全局变量的直接在这里重新赋值（见 `.shell.dark`）。`--page`/`--ink`/
 *      `--line` 这些来自 base.css 的 `:root`，而自定义属性是**可以被子元素覆盖**的——
 *      在 `.shell` 上说一句 `--line: #2e323a`，这个子树里的 `var(--line)` 全部跟着变，
 *      一条都不用改。这是让改动量可控的关键。
 *      它们只作用在 `.shell` 子树上，所以首页/登录/知识库完全不受影响。
 *   2. 组件里**自己写死**的那些颜色（浅色下 41 个不同取值）没有变量可复用，
 *      就地归拢成下面这批 `--c-*` 语义 token。浅色值写在这里，
 *      深色值写在同一文件下面的 `.shell.dark` 里。
 *
 * 命名按「用途」不按「颜色」：`--c-text-2` 而不是 `--c-gray-2`，
 * 这样以后调色不用改名字。文本从深到浅分 6 级（1 最深），
 * 浅色下原本有 13 个不同的灰，那是历史堆积，不是设计——归成 6 级足够。
 */
.shell {
  /* 面 */
  --c-side: #fbfbfc;
  --c-surface: #fff;
  --c-surface-hover: #fdfdff;
  --c-subtle: #eef0f4;
  --c-bubble-muted: #fbfbfc;
  --c-code-inline: #f2f3f6;
  --c-code-block: #f7f8fa;
  --c-chrome: rgba(255, 255, 255, 0.82);
  --c-chrome-strong: rgba(255, 255, 255, 0.86);
  --c-tint-hover: rgba(10, 15, 30, 0.06);

  /* 线 */
  --c-border-input: #dfe2e8;
  --c-border-warn: #eccb99;
  --c-border-error: #eeb6b6;

  /* 字。1 是正文，6 是最淡的辅助说明 */
  --c-text-1: #14161a;
  --c-text-2: #3d4653;
  --c-text-3: #5b6472;
  --c-text-4: #77808f;
  --c-text-5: #8b94a3;
  --c-text-6: #a0a7b3;

  /*
   * 强调色上的字。**和 --c-surface 是两回事，别合并。**
   * 用户气泡和发送按钮是「强调色底 + 白字」，深色下底色不变，
   * 这两个 `#fff` 也就不该跟着面一起翻成深色——翻了就是深底深字，
   * 用户自己发的消息直接看不见。
   */
  --c-on-accent: #fff;

  /* 语义色 */
  --c-danger: #b0322f;
  --c-warn-text: #8a6220;
  --c-danger-bg: #fdeaea;
  --c-warn-bg: #fdf1dd;
  --c-notice-bg: #fffbf4;
  --c-error-bg: #fff8f8;
  --c-success-bg: #e2f3ee;
  --c-success: #0a7d55;

  /* 阴影和遮罩。浅色下用黑色低透明度；深色下底色本身就深，
     同样的黑几乎看不见，所以深色那边要加大不透明度 */
  --c-shadow-bubble: rgba(16, 20, 30, 0.04);
  --c-shadow-drawer: rgba(16, 20, 30, 0.14);
  --c-scrim: rgba(16, 20, 30, 0.32);

  /* highlight.js。自己写的一套，深色下是另一套调色板 */
  --c-hljs-comment: #8b94a3;
  --c-hljs-keyword: #a626a4;
  --c-hljs-string: #0a7d55;
  --c-hljs-number: #b06400;
  --c-hljs-title: #1a6fd4;

  display: flex;
  height: 100dvh;
  background: var(--page);
  /*
   * 这里必须显式写一次字色，**不能靠继承**。
   *
   * 不写的话，色值来自 `base.css` 的 `body { color: var(--ink) }`——
   * 那个 `var(--ink)` 是在 body 元素上解析的，取的是 `:root` 里的 #14161a。
   * 下面 `.shell.dark` 虽然也重新赋值了 `--ink`，但那只影响**在 .shell 子树里
   * 引用 var(--ink) 的地方**，管不到 body 已经解析好的那个值——继承下来的是深色。
   *
   * 表现就是：输入框（`textarea { color: inherit }`）和会话重命名框在深色下
   * 是「深底上的深字」，几乎看不见。所有没显式设过颜色、靠继承的元素都吃这个亏。
   */
  color: var(--c-text-1);
}

/*
 * 深色。类由 `theme.js` 按「用户选了深色 且 对话页开着」两个条件挂上来。
 *
 * 上面一半是**覆盖 base.css 的全局变量**：这是最省事的一层，
 * 组件里那 20 多处 `var(--line)`、`var(--ink)` 一条都不用动。
 * 下面一半是覆盖本组件自己的语义 token。
 */
.shell.dark {
  --page: #0f1115;
  --ink: #e6e8ee;
  --line: #2e323a;
  --line-strong: #3a3f49;

  --c-side: #14161b;
  --c-surface: #1e2026;
  --c-surface-hover: #23262d;
  --c-subtle: #262a31;
  --c-bubble-muted: #1a1c22;
  --c-code-inline: #262a31;
  --c-code-block: #17191e;
  --c-chrome: rgba(20, 22, 27, 0.82);
  --c-chrome-strong: rgba(20, 22, 27, 0.86);
  --c-tint-hover: rgba(255, 255, 255, 0.07);

  --c-border-input: #33373f;
  --c-border-warn: #4d3f28;
  --c-border-error: #5a3232;

  --c-text-1: #e6e8ee;
  --c-text-2: #c8ccd6;
  --c-text-3: #a8aebc;
  --c-text-4: #8b93a3;
  --c-text-5: #737b8b;
  --c-text-6: #666e7d;

  --c-danger: #f08a86;
  --c-warn-text: #e0b878;
  --c-danger-bg: #3a2020;
  --c-warn-bg: #3a2f1c;
  --c-notice-bg: #2a2418;
  --c-error-bg: #2e1e1e;
  --c-success-bg: #16302a;
  --c-success: #6cc9a8;

  --c-shadow-bubble: rgba(0, 0, 0, 0.4);
  --c-shadow-drawer: rgba(0, 0, 0, 0.55);
  --c-scrim: rgba(0, 0, 0, 0.5);

  --c-hljs-comment: #6b7383;
  --c-hljs-keyword: #d9a0d6;
  --c-hljs-string: #7ec99f;
  --c-hljs-number: #e0b070;
  --c-hljs-title: #7fb4ef;
}

/* ---------------------------------------------------------------- 侧边栏 */

.side {
  display: flex;
  flex-direction: column;
  flex: none;
  width: 264px;
  border-right: 1px solid var(--line);
  background: var(--c-side);
  /* 折叠时宽度变窄，用过渡而不是瞬间跳变：整个页面会跟着重排，
     突变看起来像卡了一下 */
  transition: width 0.2s cubic-bezier(0.16, 0.84, 0.44, 1);
}

/* 折叠态：只留图标。
   56px 是「28px 图标 + 两边各 14px」，能放下那颗新会话按钮 */
.side.collapsed {
  width: 56px;
}
.side.collapsed .side-head {
  padding: 14px 0 6px;
  display: flex;
  justify-content: center;
}
.side.collapsed .new-chat {
  /* 去掉文字之后按钮变成方的，左右外边距要收窄，不然会被挤扁 */
  margin-inline: 8px;
  padding-inline: 0;
  justify-content: center;
}
.side.collapsed .side-foot {
  padding-inline: 6px;
}
.side.collapsed .sessions,
.side.collapsed .side-error,
.side.collapsed .side-empty {
  /* 窄轨里放不下会话标题。藏起来而不是截断成几个字——
     截断出来的「我 175…」比没有更难看，而且点不着 */
  display: none;
}

.side-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px 6px;
}

/* 折叠时只剩品牌点，居中显示 */
.dot.solo {
  margin: 0;
}

.collapse-btn {
  flex: none;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  margin-left: auto;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--c-text-6);
  transition: background 0.16s ease, color 0.16s ease;
}
.collapse-btn:hover {
  background: var(--c-tint-hover);
  color: var(--c-text-3);
}
.collapse-btn svg {
  width: 16px;
  height: 16px;
}
.side.collapsed .collapse-btn {
  margin-left: 0;
}

/* ------------------------------------------------ 链路切换（轻语 ⇄ Manus） */

/*
 * 它长得像一张卡片而不是一行链接，是因为它的作用是「换一条链路」——
 * 那是这个界面里最重的一次切换（会换掉整个会话列表和主题色），
 * 做成普通链接容易被当成「返回上一页」那类导航。
 *
 * 用目标链路的颜色（--sw），不是当前那条：这是「你要去的地方」的预览。
 */
.switch {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 6px 14px 2px;
  padding: 9px 11px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--c-surface);
  color: inherit;
  text-decoration: none;
  transition: border-color 0.18s ease, background 0.18s ease;
}
.switch:hover {
  border-color: var(--sw);
  background: var(--c-surface-hover);
}

.switch-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--sw);
}

.switch-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.switch-label {
  color: var(--c-text-6);
  font-size: 10.5px;
  letter-spacing: 0.02em;
}

.switch-name {
  overflow: hidden;
  color: var(--c-text-2);
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.switch-arrow {
  flex: none;
  width: 14px;
  height: 14px;
  color: var(--sw);
  transition: transform 0.24s cubic-bezier(0.16, 0.84, 0.44, 1);
}
.switch:hover .switch-arrow {
  transform: translateX(3px);
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
  background: var(--c-surface);
  color: var(--c-text-2);
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
  color: var(--c-danger);
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
  color: var(--c-text-6);
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
  background: var(--c-subtle);
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
  color: var(--c-text-2);
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
  background: var(--c-surface);
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
  color: var(--c-text-5);
}
.mini:hover {
  background: var(--c-surface);
  color: var(--ink);
}
.mini.danger:hover {
  color: var(--c-danger);
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
  background: var(--c-chrome);
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
  color: var(--c-text-3);
}
.icon-btn:hover {
  background: var(--c-subtle);
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
  background: var(--c-subtle);
  color: var(--c-text-4);
  font-size: 11px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  cursor: default;
}

.ghost {
  padding: 5px 11px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--c-surface);
  font-size: 12.5px;
  color: var(--c-text-3);
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
  border: 1px solid var(--c-border-warn);
  border-radius: 10px;
  background: var(--c-notice-bg);
  color: var(--c-warn-text);
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
  /* 注意不是 --c-surface：这是**强调色上的字**，深色下底色和字色都不变 */
  color: var(--c-on-accent);
  border-bottom-right-radius: 5px;
}

.row.assistant .bubble {
  background: var(--c-surface);
  color: var(--ink);
  border: 1px solid var(--line);
  border-bottom-left-radius: 5px;
  box-shadow: 0 1px 2px var(--c-shadow-bubble);
}

.row.assistant .bubble.tone-warn {
  border-color: var(--c-border-warn);
  background: var(--c-notice-bg);
}
.row.assistant .bubble.tone-error {
  border-color: var(--c-border-error);
  background: var(--c-error-bg);
}
.row.assistant .bubble.tone-ask {
  border-color: var(--accent);
  background: var(--c-surface);
}
.row.assistant .bubble.tone-muted {
  background: var(--c-bubble-muted);
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
  background: var(--c-subtle);
  color: var(--c-text-3);
}
.badge-error {
  background: var(--c-danger-bg);
  color: var(--c-danger);
}
.badge-warn {
  background: var(--c-warn-bg);
  color: var(--c-warn-text);
}
.badge-ask {
  background: var(--accent-soft);
  color: var(--accent);
}

.hint {
  max-width: min(80%, 660px);
  margin-top: 6px;
  padding-left: 4px;
  color: var(--c-text-5);
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
  color: var(--c-text-4);
}
.md :deep(a) {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 2px;
}
.md :deep(code) {
  padding: 1.5px 5px;
  border-radius: 5px;
  background: var(--c-code-inline);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.88em;
}
.md :deep(pre) {
  margin: 10px 0;
  padding: 11px 13px;
  border-radius: 10px;
  background: var(--c-code-block);
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
  background: var(--c-code-block);
  font-weight: 600;
}
.md :deep(hr) {
  margin: 14px 0;
  border: none;
  border-top: 1px solid var(--line);
}

/* highlight.js 的配色（自己写，不引它的主题包：只需要几个 token）。
   浅色那套在深色底上基本读不出来（比如 #0a7d55 的绿），所以深色是另一套调色板，
   见上面的 .shell.dark */
.md :deep(.hljs-comment),
.md :deep(.hljs-quote) {
  color: var(--c-hljs-comment);
  font-style: italic;
}
.md :deep(.hljs-keyword),
.md :deep(.hljs-selector-tag),
.md :deep(.hljs-literal) {
  color: var(--c-hljs-keyword);
}
.md :deep(.hljs-string),
.md :deep(.hljs-attr),
.md :deep(.hljs-addition) {
  color: var(--c-hljs-string);
}
.md :deep(.hljs-number),
.md :deep(.hljs-built_in) {
  color: var(--c-hljs-number);
}
.md :deep(.hljs-title),
.md :deep(.hljs-function),
.md :deep(.hljs-section) {
  color: var(--c-hljs-title);
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
  color: var(--c-text-4);
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
  color: var(--c-text-4);
  font-size: 12px;
  line-height: 1.55;
}

.trace-type {
  flex: none;
  min-width: 30px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--c-subtle);
  color: var(--c-text-3);
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
  background: var(--c-success-bg);
  color: var(--c-success);
}
.trace-loop_signal .trace-type {
  background: var(--c-warn-bg);
  color: var(--c-warn-text);
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
  color: var(--c-text-4);
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
  background: var(--c-surface);
  color: var(--c-text-3);
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
  background: var(--c-chrome-strong);
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
  border: 1px solid var(--c-border-input);
  border-radius: 14px;
  background: var(--c-surface);
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
  color: var(--c-text-6);
}

.send {
  flex: none;
  padding: 8px 18px;
  border: none;
  border-radius: 10px;
  background: var(--accent);
  color: var(--c-on-accent);
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
  background: var(--c-surface);
  color: var(--c-text-3);
  border: 1px solid var(--c-border-input);
}
.stop:hover {
  border-color: var(--c-danger);
  color: var(--c-danger);
  filter: none;
}

.tip {
  margin-top: 7px;
  padding-left: 3px;
  color: var(--c-text-6);
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
    box-shadow: 0 0 40px var(--c-shadow-drawer);
  }
  .side.open {
    transform: translateX(0);
  }
  /*
   * 窄屏下「折叠」这个状态没有意义：侧边栏本来就收在画外，展开时它要占满
   * 抽屉的宽度。不禁掉的话，一个在宽屏折起侧栏、再把窗口缩窄的用户会得到一个
   * 56px 宽的抽屉——里面的会话标题全被 `.collapsed` 那条规则藏了，看起来像空的。
   */
  .side.collapsed {
    width: 264px;
  }
  .side.collapsed .side-head {
    display: flex;
    padding: 14px 16px 6px;
  }
  .side.collapsed .new-chat {
    margin-inline: 0;
    padding-inline: 0;
    justify-content: flex-start;
  }
  .side.collapsed .side-foot {
    padding-inline: 14px;
  }
  /* 这三个都是块级元素（nav / p），恢复成它们本来的 display。
     写成 flex 的话会话项会横着排 */
  .side.collapsed .sessions,
  .side.collapsed .side-error,
  .side.collapsed .side-empty {
    display: block;
  }
  /* 抽屉里不需要折叠按钮：关掉它是靠点遮罩，不是靠这个开关 */
  .collapse-btn {
    display: none;
  }
  .scrim {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 10;
    background: var(--c-scrim);
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
