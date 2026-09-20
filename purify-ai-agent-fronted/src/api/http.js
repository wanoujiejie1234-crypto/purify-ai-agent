import axios from 'axios'
import { currentUserId } from '../user.js'

/**
 * 普通 JSON 请求走 axios。
 *
 * baseURL 故意留空：Vite 的 dev server 会把 /api 代理到 http://localhost:8080，
 * 生产则是由 Spring Boot 托管构建产物、同源。所以这里一律用相对路径。
 * 写死 http://localhost:8080 会同时踩两个坑——浏览器直连产生跨域，
 * 以及读不到 X-Chat-Id 这种自定义响应头。
 */
const http = axios.create({
  baseURL: '',
  // 默认 60 秒。放这么宽是因为只有上传建索引这一条链路会慢——它要调
  // DashScope 的 Embedding 接口，且服务端是按每批 10 条切片串行发请求的。
  // 单个接口如果还要更久，各自单独覆盖（见下面的批量导入）
  timeout: 60000,
})

/**
 * 每个请求都带上用户标识。
 *
 * 放在拦截器里而不是各调用点，是因为它必须**一个不漏**：少带一个接口，
 * 那个接口就会把数据记到 anonymous 名下，而这个错误不会报任何错——
 * 表现只是「换台机器登录，会话列表少了几条」。
 *
 * 后端还没有登录体系，这个值由浏览器生成，取值理由见 user.js。
 */
http.interceptors.request.use((config) => {
  config.headers['X-User-Id'] = currentUserId()
  return config
})

/**
 * 把 axios 的报错整理成一句人能直接看懂的话。
 * 后端错误体是 { code, message }，优先显示 message；
 * 连不上后端（开发时最常见）单独特判，否则用户只会看到 "Network Error" 这种没用的英文。
 */
http.interceptors.response.use(
  (res) => res,
  (err) => {
    const serverMessage = err?.response?.data?.message
    if (serverMessage) return Promise.reject(new Error(serverMessage))

    if (err?.code === 'ECONNABORTED') {
      return Promise.reject(new Error('请求超时，请稍后重试。'))
    }
    if (!err?.response) {
      return Promise.reject(new Error('连不上后端服务（http://localhost:8080），请确认 Spring Boot 已经启动。'))
    }
    if (err.response.status === 404) {
      return Promise.reject(new Error('请求的资源不存在（404）。'))
    }
    return Promise.reject(new Error(`请求失败（HTTP ${err.response.status}）`))
  },
)

/* ------------------------------------------------------------------ 对话历史 */

/**
 * 读历史记录。link 传 'slim' 或 'manus'。
 * 会话不存在或还没聊过时后端返回 []，这是正常结果，不要当错误处理。
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

/* ---------------------------------------------------------------- 检索自检 */

/** 拿一个问题跑一次真实检索，看看知识库到底能查出什么。只读，可以随便试。 */
export async function searchKnowledge(question) {
  const { data } = await http.get('/api/rag/search', { params: { q: question } })
  return data
}
