import axios from 'axios'
import * as auth from '../auth.js'
import { t, acceptLanguageHeader } from '../i18n/index.js'

/**
 * 普通 JSON 请求走 axios。
 *
 * baseURL 故意留空：Vite 的 dev server 会把 /api 代理到 http://localhost:8080，
 * 生产则是由 Spring Boot 托管构建产物、同源。所以这里一律用相对路径。
 * 写死 http://localhost:8080 会同时踩两个坑——浏览器直连产生跨域，
 * 以及读不到 X-Chat-Id 这种自定义响应头。
 */
export const http = axios.create({
  baseURL: '',
  // 默认 60 秒。放这么宽是因为只有上传建索引这一条链路会慢——它要调
  // DashScope 的 Embedding 接口，且服务端是按每批 10 条切片串行发请求的。
  // 单个接口如果还要更久，各自单独覆盖（见下面的批量导入）
  timeout: 60000,
})

/**
 * 每个请求都带上令牌。
 *
 * 放在拦截器里而不是各调用点，是因为它必须**一个不漏**：少带一个接口，
 * 那个接口就会 401，表现是「某个功能莫名其妙不能用」。
 *
 * 注意这条拦截器**管不到 SSE 那两条链路**——它们用原生 fetch，不走 axios
 * （原因见 sse.js 的注释）。令牌要在那边单独加一次，少加的表现同样是 401。
 */
http.interceptors.request.use((config) => {
  const current = auth.token()
  if (current) {
    config.headers.Authorization = `Bearer ${current}`
  }
  // 告诉后端用哪种语言写报错文案（后端 `messages*.properties` 靠它选）。
  // 和令牌一样必须「一个不漏」：漏掉的接口会在中文界面上返回英文报错，
  // 或者反过来——用户在设置里切了英文，某个接口却仍然报中文。
  config.headers['Accept-Language'] = acceptLanguageHeader()
  return config
})

/**
 * 并发请求一起 401 时，只跳转一次。
 *
 * 一个页面在挂载时常常并发发出好几个请求（会话列表、历史、知识库统计……），
 * 令牌一失效它们会同时 401。不挡一下的话就是连续好几次
 * `window.location.assign`，浏览器会把跳转重排，用户看到的是页面抖了一下。
 */
let redirectingToLogin = false

/**
 * 该去登录页了。
 *
 * <p>**用 `window.location.assign` 而不是 `router.push`。** 两个原因：
 * 一是在这里 `import router` 会形成一个真实的循环
 * （`router → views → http.js → router`），而循环导入的表现是某个模块
 * 在某些加载顺序下变成 `undefined`，只在特定入口才复现；
 * 二是「你的会话结束了」本来就该是一次完整的重载——把一个已经失效的登录态
 * 带进新页面，只会让新页面上的其他请求再撞一遍 401。
 */
function redirectToLogin() {
  if (redirectingToLogin) return
  redirectingToLogin = true
  auth.clear()
  const here = window.location.pathname + window.location.search
  // 已经在登录页上就别再跳了，否则会把自己的 ?redirect= 覆盖掉
  if (window.location.pathname === '/login') {
    redirectingToLogin = false
    return
  }
  window.location.assign(`/login?redirect=${encodeURIComponent(here)}`)
}

/**
 * 把 axios 的报错整理成一句人能直接看懂的话，并处理鉴权相关的状态码。
 *
 * 后端错误体是 `{ code, message }`，优先显示 message；
 * 连不上后端（开发时最常见）单独特判，否则用户只会看到 "Network Error" 这种没用的英文。
 */
http.interceptors.response.use(
  (res) => res,
  (err) => {
    const status = err?.response?.status
    const serverMessage = err?.response?.data?.message
    // 把后端的错误码也挂在 Error 上。视图偶尔需要按码分支
    // （比如 CODE_TOO_FREQUENT 要把「获取验证码」变成倒计时，
    // 而不是弹一个「请求太频繁」的报错框），而只靠 message 文案去判断太脆
    const serverCode = err?.response?.data?.code

    // 401：没登录 / 令牌过期或伪造。清掉本地令牌去登录页。
    // 这里**不做区分**：对用户来说两者的动作都是重新登录
    if (status === 401) {
      redirectToLogin()
      return Promise.reject(new Error(serverMessage || t('error.unauthorized')))
    }

    // 403：身份是好的，只是权限不够。**绝对不能跳登录页、更不能清令牌**——
    // 那样普通用户点到一个本不该看到的链接就被登出了，而实际上什么都没发生。
    // 保持登录，把后端那句话原样抛出去，让调用方弹个提示就行
    if (status === 403) {
      return Promise.reject(new Error(serverMessage || t('error.forbidden')))
    }

    // 后端说了话就用它那句——它已经按 Accept-Language 翻好了，而且比前端这句更具体
    if (serverMessage) return Promise.reject(failure(serverMessage, serverCode, status))

    if (err?.code === 'ECONNABORTED') {
      return Promise.reject(failure(t('error.timeout')))
    }
    if (!err?.response) {
      return Promise.reject(failure(t('error.offline')))
    }
    if (status === 404) {
      return Promise.reject(failure(t('error.notFound'), undefined, 404))
    }
    return Promise.reject(failure(t('error.http', { status }), undefined, status))
  },
)

/**
 * 造一个带着后端错误码和 HTTP 状态的 Error。
 *
 * 用 `Error` 而不是自定义类型：调用方已经习惯了 `try/catch` + `err.message`，
 * 换成自定义类会让现有那些 catch 拿不到 message（它们大多只读 message）。
 * 额外挂上去的 `code` / `status` 是加法，不破坏任何东西——
 * 不关心它们的地方照旧只看 message。
 *
 * `status` 是需要的：`ChatRoom` 要按 404 分支（「这个会话没了，开个新的」），
 * 而 404 在这里有两个来源（后端明确说的、和框架兜底的），
 * 用 message 文案去认太脆。
 */
function failure(message, code, status) {
  const error = new Error(message)
  if (code) error.code = code
  if (status) error.status = status
  return error
}

/* ------------------------------------------------------------------ 对话历史 */

/**
 * 读历史记录。link 传 'slim' 或 'manus'。
 *
 * **这个接口现在会 404**，而且要当正常结果处理：会话不存在、已被删除、
 * 或者不属于当前用户，后端一律返回 404 且是同一句话（分开的话它就成了一个
 * 存在性探测口子，见后端 `SessionAccess` 的说明）。
 *
 * 调用方要为此配合两件事：新生成的 chatId 不要去调它（必然 404），
 * 而从 sessionStorage 恢复出来的旧 id 真 404 时提示一句再开新会话。
 * 后端返回的 message 已经写好了（「会话不存在或已被删除」），直接显示即可。
 */
export async function fetchHistory(link, chatId) {
  const { data } = await http.get(`/api/${link}/history`, { params: { chatId } })
  return Array.isArray(data) ? data : []
}

/* -------------------------------------------------------------------- 会话 */

/** 列出某个入口下的全部会话，后端已按最近使用倒序。 */
export async function fetchSessions(link) {
  const { data } = await http.get('/api/sessions', { params: { entry: link } })
  return Array.isArray(data) ? data : []
}

export async function renameSession(conversationId, title) {
  await http.put(`/api/sessions/${encodeURIComponent(conversationId)}`, null, { params: { title } })
}

export async function deleteSession(conversationId) {
  await http.delete(`/api/sessions/${encodeURIComponent(conversationId)}`)
}

/* -------------------------------------------------------------------- 画像 */

/**
 * 读当前用户的画像。
 *
 * 响应里除了画像本身还带着活动水平的可选项（`activityLevelOptions`）——
 * 下拉框的选项由后端给，前端不写死一份：写死的话，以后枚举改了名或加了档，
 * 表现是「某个选项保存不了」，而那种问题很难联想到是前端少更新了一个数组。
 */
export async function fetchProfile() {
  const { data } = await http.get('/api/profile')
  return data
}

/**
 * 改画像。**只传这次改动的字段**，没传的后端保持原值。
 *
 * 返回改完之后完整的画像，直接拿它刷新表单即可，不用再 fetchProfile 一次。
 */
export async function updateProfile(payload) {
  const { data } = await http.put('/api/profile', payload)
  return data
}

/* ------------------------------------------------------------------ 资料库 */

/**
 * 当前用户在对话里产出的文件，最近的在前。
 *
 * 每条带着 `url`（能直接打开的地址，可能为 null）和 `kind`：
 * - `url` 有值：生成的 PDF、下载回来的资源。界面用普通链接即可，那个地址本身是公开的；
 * - `url` 为 null：writeFile 写出来的文件。它落在服务端一个**没有对外映射**的目录里，
 *   只能走 `downloadResourceFile` 那个带鉴权的接口。
 */
export async function fetchResources(limit = 50) {
  const { data } = await http.get('/api/resources', { params: { limit } })
  return Array.isArray(data) ? data : []
}

export async function deleteResource(id) {
  await http.delete(`/api/resources/${encodeURIComponent(id)}`)
}

/**
 * 下载一份「没有对外地址」的产出（就是 writeFile 写出来的那些）。
 *
 * **不能用 `<a href="/api/resources/{id}/download">`。** 那是一次浏览器导航，
 * 不带 Authorization 头，而接口是要求登录的——点下去只会得到一个 401。
 * 所以这里用 axios 把文件取成 blob（请求拦截器会带上令牌），
 * 再用一个临时链接触发保存。
 *
 * 代价是整个文件会先进内存。资料库里的东西都是文本或小文件，这点开销可以接受；
 * 真出现几十 MB 的产出时，要改成后端签发一次性下载令牌，而不是继续往内存里灌。
 */
export async function downloadResourceFile(id, title) {
  const { data } = await http.get(`/api/resources/${encodeURIComponent(id)}/download`, {
    responseType: 'blob',
  })
  const objectUrl = URL.createObjectURL(data)
  const anchor = document.createElement('a')
  anchor.href = objectUrl
  // 文件名由后端在 Content-Disposition 里给了，但 blob 这条路上浏览器读不到它
  // （那个头要 exposedHeaders 才允许 JS 读），所以这里再带一次
  anchor.download = title || ''
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  // 立刻回收：不释放的话这块内存在页面关掉之前一直占着
  URL.revokeObjectURL(objectUrl)
}

/* ------------------------------------------------------------------ 知识库 */

const KNOWLEDGE = '/api/knowledge'

export async function fetchKnowledgeStats() {
  const { data } = await http.get(`${KNOWLEDGE}/stats`)
  return data
}

export async function fetchKnowledgeDocuments(page = 1, size = 20) {
  const { data } = await http.get(`${KNOWLEDGE}/documents`, { params: { page, size } })
  return data
}

/** 上传一份文档并建索引。classification 必须命中后端配置里的分类表。 */
export async function uploadDocument(file, classification) {
  const form = new FormData()
  form.append('file', file)
  form.append('classification', classification)
  const { data } = await http.post(`${KNOWLEDGE}/documents`, form)
  return data
}

/**
 * 批量导入。files 是 File 数组，整批共用同一个分类
 * （后端也支持一一对应地传 classifications，但界面上没做那个交互）。
 */
export async function uploadDocuments(files, classification) {
  const form = new FormData()
  for (const file of files) form.append('files', file)
  form.append('classification', classification)
  // 单独放宽到 10 分钟：服务端是一份一份串行向量化的，几十份文档很容易超过默认的 60 秒。
  // 超时的表现特别容易误导 —— 前端报「请求超时」，服务端其实还在继续索引，
  // 用户于是重传一遍，同一批文档白花两次 Embedding 的钱
  const { data } = await http.post(`${KNOWLEDGE}/documents/batch`, form, { timeout: 600000 })
  return data
}

/** 索引前预览：返回会切成几片，不写库。 */
export async function previewDocument(file, classification) {
  const form = new FormData()
  form.append('file', file)
  form.append('classification', classification)
  const { data } = await http.post(`${KNOWLEDGE}/preview`, form)
  return data
}

export async function deleteDocument(source) {
  await http.delete(`${KNOWLEDGE}/documents`, { params: { source } })
}

/* -------------------------------------------------------------- 百炼同步 */

const BAILIAN = `${KNOWLEDGE}/bailian`

/**
 * 配置自检：配齐了没有、缺哪几项、可选分类有哪些。
 *
 * **不发远程请求**（只读本地配置），所以卡片挂载时可以直接调。
 * `categories` 也从这里拿，不在前端再硬编码一份——那些值必须和
 * `purify.rag.router.categories` 一字不差，多一处就多一处会漂移的地方。
 */
export async function fetchBailianStatus() {
  const { data } = await http.get(`${BAILIAN}/status`)
  return data
}

/** 百炼的文件清单，每行带本地同步状态。整页只调一次百炼，本地状态是批量查的。 */
export async function fetchBailianDocuments(page = 1, size = 20, status, name) {
  const { data } = await http.get(`${BAILIAN}/documents`, {
    params: { page, size, status, name },
  })
  return data
}

/**
 * 某一份文档在百炼侧的切片。**正文不截断**，响应可能很大。
 *
 * 所以是「展开某一行」按需触发的，不要在列表页预取；而且界面上一次只展开一份——
 * 百炼那个接口限流 10 QPS，同时展开十几行等于并发打十几个请求出去。
 */
export async function fetchBailianChunks(fileId, pageNum = 1, pageSize = 20) {
  const { data } = await http.get(
    `${BAILIAN}/documents/${encodeURIComponent(fileId)}/chunks`,
    { params: { pageNum, pageSize } },
  )
  return data
}

/**
 * 把选中的几份文档同步进本地向量库。
 *
 * `items` 的每一项是 `{ fileId, name, classification, gmtModified }`。
 * `gmtModified` 一定要带上：服务端会把它存进切片元数据，用来判断「百炼那边后来改过没有」。
 * 不带的话，这份文档以后会一直显示「百炼侧已更新」——本地没有基准可比。
 *
 * 超时单独放宽到 10 分钟，理由同 `uploadDocuments`：服务端是一份一份串行拉切片的，
 * 大文档很容易超过默认的 60 秒。不过这里比那个乐观一点——**重复同步是安全的**
 * （服务端先按来源删干净再写），所以超时后重新点一次不会写出重复切片。
 */
export async function syncBailianDocuments(items) {
  const { data } = await http.post(`${BAILIAN}/sync`, { items }, { timeout: 600000 })
  return data
}

/** 管控面联调自检。返回的是百炼的原始形状，排障用。 */
export async function probeBailian() {
  const { data } = await http.get(`${BAILIAN}/probe`)
  return data
}

/* ---------------------------------------------------------------- 检索自检 */

/** 拿一个问题跑一次真实检索，看看知识库到底能查出什么。只读，可以随便试。 */
export async function searchKnowledge(question) {
  const { data } = await http.get('/api/rag/search', { params: { q: question } })
  return data
}
