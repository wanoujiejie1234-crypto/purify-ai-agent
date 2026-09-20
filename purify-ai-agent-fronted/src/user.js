/**
 * 用户标识。
 *
 * 这个项目还没有登录体系，所以「用户是谁」由浏览器自己编一个 UUID 出来，
 * 存在 localStorage 里长期不变，并在每个请求上通过 X-User-Id 头带给后端。
 *
 * 用 localStorage 而不是 sessionStorage（会话 id 用的是后者）：
 * 两者要表达的东西不同 —— 会话 id 换一个就是「开一段新对话」，而用户标识换了
 * 就等于「换了个人」，之前所有的历史会话都会从侧边栏消失。开个新标签页不该有这种后果。
 *
 * 局限要写清楚：这个值前端能随便伪造，所以它做到的是「同一个浏览器的会话归到一起」，
 * 不是真正的隔离。接上登录之后，把它换成登录态里的用户 ID 即可，后端表结构不用动。
 */

const STORAGE_KEY = 'purify:userId'

let cached = null

export function currentUserId() {
  if (cached) return cached

  let stored = null
  try {
    stored = localStorage.getItem(STORAGE_KEY)
  } catch {
    // 隐私模式下 localStorage 可能直接抛异常。这时退化成「本次会话有效」的临时 id，
    // 页面照样能用，只是刷新之后侧边栏会换一批 —— 比整个页面白屏好
  }

  if (stored) {
    cached = stored
    return cached
  }

  cached = crypto.randomUUID()
  try {
    localStorage.setItem(STORAGE_KEY, cached)
  } catch {
    // 同上，存不下就算了
  }
  return cached
}
