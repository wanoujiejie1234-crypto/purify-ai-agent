/**
 * SSE 流式对话的读取实现。
 *
 * 三条硬性约束，缺一条都会出问题：
 *
 * 1. 必须用 fetch，不能用 axios。
 *    axios 在浏览器里是 XHR 的封装，只能等响应体全部到齐后一次性交付，
 *    读不到中间过程；而 SSE 的价值恰恰是「模型生成一个字，界面就出一个字」。
 *    （axios 的 responseType: 'stream' 只在 Node 端有效，浏览器端会被忽略。）
 *
 * 2. 绝对不能用 EventSource。
 *    它只支持 GET，而这两个接口是 POST；更严重的是它在连接断开时会自动重连，
 *    重连等于把同一句话再发一遍 —— 对智能体就是一次完整的任务重跑，
 *    白烧 token 还可能重复执行工具。
 *
 * 3. 必须用 TextDecoder 并带 { stream: true }。见下方注释。
 */

import * as auth from '../auth.js'
import { t, acceptLanguageHeader } from '../i18n/index.js'

/**
 * 发起一次流式请求。
 *
 * @param {object}   opts
 * @param {string}   opts.url      相对路径，如 '/api/slim/chat'
 * @param {string}   opts.chatId   会话 id
 * @param {string}   opts.message  用户说的话
 * @param {AbortSignal} opts.signal 用于「停止生成」
 * @param {(e: {event: string, payload: object}) => void} opts.onEvent 每收到一个事件回调一次
 * @param {(id: string) => void} [opts.onChatId] 读到 X-Chat-Id 时回调
 */
export async function streamChat({ url, chatId, message, signal, onEvent, onChatId }) {
  const currentToken = auth.token()
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
  }
  // 这条链路绕过了 axios，所以 http.js 里那个带令牌的拦截器管不到它，
  // 令牌必须在这里单独加一次。漏了的表现是发消息直接 401。
  //
  // 判空不能省：没有令牌时拼出来的是字符串 "Bearer null"，它**不是**一个空头——
  // 后端的 extractToken 会把它当成一个真的令牌往下传，最后以「签名不对」告终。
  // 结果虽然也是 401（正好是想要的），但日志里留下的是一个像模像样的伪造令牌，
  // 排查时会误导方向。http.js 那边就是这么判的，两边保持一致
  if (currentToken) {
    headers.Authorization = `Bearer ${currentToken}`
  }
  // 和 http.js 那边一样要带语言。这条链路绕过了 axios 的拦截器，
  // 所以两处都要各写一次——漏掉这一处，对话页上的报错会永远是中文
  headers['Accept-Language'] = acceptLanguageHeader()

  const resp = await fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify({ chatId, message }),
    signal,
  })

  // X-Chat-Id 是「响应头」不是响应体。前端虽然自己生成了 chatId，
  // 但仍要以后端返回的为准兜一下，否则万一后端纠正了 id，后续请求就对不上了。
  const headerChatId = resp.headers.get('X-Chat-Id')
  if (headerChatId && onChatId) onChatId(headerChatId)

  // 401 单独处理：令牌过期了，和 http.js 那条链路的动作要一致（清本地、去登录页）。
  // 它发生在任何字节流出之前，所以不会有半截回答丢掉的问题
  if (resp.status === 401) {
    auth.clear()
    const here = window.location.pathname + window.location.search
    window.location.assign(`/login?redirect=${encodeURIComponent(here)}`)
    throw new Error(await readErrorMessage(resp))
  }

  if (!resp.ok) {
    // 404 也走这里：会话不存在或不属于自己。后端那句话已经写好了，直接显示
    throw new Error(await readErrorMessage(resp))
  }
  if (!resp.body) {
    throw new Error(t('error.streamUnsupported'))
  }

  const reader = resp.body.getReader()
  // 中文在 UTF-8 里占 3 个字节，网络分块完全可能正好切在字符中间。
  // 逐块独立解码的话，每个分块边界都会吐出一个乱码字符；
  // { stream: true } 让解码器自己缓存没凑齐的那半个字符，下一块再拼。
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })

    // SSE 用空行分隔事件。一次 read 可能拿到半个事件，也可能拿到好几个，
    // 所以只能在缓冲区里找分隔符，不能假设「一次 read 就是一个事件」。
    let sep
    while ((sep = findSeparator(buffer)) !== -1) {
      const raw = buffer.slice(0, sep.index)
      buffer = buffer.slice(sep.index + sep.length)
      const evt = parseEvent(raw)
      if (evt) onEvent(evt)
    }
  }

  // 冲刷解码器里可能残留的半个字符，再处理缓冲区里最后一个没有跟空行的事件
  buffer += decoder.decode()
  const tail = parseEvent(buffer)
  if (tail) onEvent(tail)
}

/**
 * 找到最靠前的事件分隔符，同时兼容 \n\n 和 \r\n\r\n。
 * 两种都要找、取靠前的那个：只找一种的话，另一种会一直攒在缓冲区里不被切分。
 */
function findSeparator(buf) {
  const lf = buf.indexOf('\n\n')
  const crlf = buf.indexOf('\r\n\r\n')
  if (lf === -1 && crlf === -1) return -1
  if (lf === -1) return { index: crlf, length: 4 }
  if (crlf === -1) return { index: lf, length: 2 }
  return lf < crlf ? { index: lf, length: 2 } : { index: crlf, length: 4 }
}

/**
 * 解析一个事件块。格式形如：
 *
 *   event:TEXT
 *   data:{"type":"TEXT","text":"够，但","state":null}
 */
function parseEvent(raw) {
  if (!raw || !raw.trim()) return null

  let name = ''
  const dataLines = []

  for (const line of raw.split(/\r?\n/)) {
    // 空行是分隔符；冒号开头的是注释（心跳包常用这种形式），一律忽略
    if (!line || line.startsWith(':')) continue

    if (line.startsWith('event:')) {
      name = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      // 规范允许 data: 后面跟一个空格，这个空格不属于数据
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }

  if (!dataLines.length) return null

  const data = dataLines.join('\n')
  let payload
  try {
    payload = JSON.parse(data)
  } catch {
    // 万一是非 JSON 的裸文本，也别把整条流搞崩，包成 TEXT 处理
    payload = { type: name || 'TEXT', text: data, state: null }
  }

  // event 名和 payload.type 正常是一致的，以 event 名为准做兜底
  if (!payload.type) payload.type = name || 'TEXT'
  return { event: name || payload.type, payload }
}

/**
 * 把错误响应整理成一句给用户看的话。
 * 后端错误体是 { code, message }，优先用 message。
 */
async function readErrorMessage(resp) {
  try {
    const body = await resp.json()
    if (body?.message) return body.message
    if (body?.code) return t('error.code', { code: body.code })
  } catch {
    // 响应体不是 JSON，走下面的兜底
  }
  return t('error.http', { status: resp.status })
}
