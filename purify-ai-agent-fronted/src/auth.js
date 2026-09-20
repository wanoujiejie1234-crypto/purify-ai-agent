import { reactive } from 'vue'

/**
 * 登录状态：令牌 + 当前用户。
 *
 * **这个模块只存状态，不发任何请求。** 请求在 `api/auth.js` 里，
 * 原因是一个会环的依赖：`api/http.js` 需要读令牌（每个请求都要带 Authorization），
 * 而 `api/auth.js` 又需要 `http.js` 发请求。如果登录逻辑写在这里，
 * 就变成 `http.js → auth.js → http.js`。拆开之后依赖是单向的：
 *
 *     auth.js  ←  http.js  ←  api/auth.js
 *        ↑                          │
 *        └──────────────────────────┘（只写状态，不反向依赖）
 *
 * 上一次是一个真正的循环（router → views → http.js → router），
 * 表现是模块初始化顺序不同时 `undefined`，这里不重蹈覆辙。
 */

const TOKEN_KEY = 'purify:token'
const USER_KEY = 'purify:user'

/**
 * sessionStorage 里会话 id 的键前缀。
 *
 * 这个前缀要和 `ChatRoom.vue` 里那个 `purify:chatId:${link}` 完全一致——
 * 两处不一致的话，退出登录时清不掉，而表现是「换个人登录，打开就是『会话不存在』」，
 * 一个要排查半天的 bug。所以清理由本模块统一提供，聊天组件只负责按这个前缀存。
 */
const CHAT_ID_PREFIX = 'purify:chatId:'

/**
 * 响应式的登录状态。用 reactive 而不是 ref：它是个对象，
 * 组件里读 `auth.state.user?.nickname` 比 `auth.user.value?.nickname` 顺眼。
 */
export const state = reactive({
  token: null,
  user: null,
})

/* ------------------------------------------------------------------ 读取 */

export function token() {
  return state.token
}

export function user() {
  return state.user
}

export function isLoggedIn() {
  return Boolean(state.token && state.user)
}

/**
 * 是不是超级用户。
 *
 * **这只是界面显隐用的**，不是安全边界。用户改一下 localStorage 就能让知识库入口
 * 显示出来，但点进去照样是 403——真正的拦截在后端的 `@RequireAdmin`。
 * 把它当成权限控制来用会得出「前端藏了就等于没有」这个错误结论。
 */
export function isAdmin() {
  return state.user?.role === 'SUPER'
}

/* ------------------------------------------------------------------ 写入 */

/**
 * 登录/注册成功后写入。两处都调它，所以这里顺带清一次会话 id——
 * 换个人登录时必须清，见 `clearChatIds` 的说明。
 */
export function setSession(newToken, newUser) {
  clearChatIds()
  state.token = newToken
  state.user = newUser
  persist()
}

/**
 * 退出登录：只清本地。
 *
 * **服务端什么都没做**，因为 JWT 是无状态的、没有会话可以销毁。也就是说，
 * 如果这个令牌已经被别人拿到了，用户点「退出」并不能把它作废——它在过期前一直有效。
 * 这是这套方案的已知边界，写在这里免得以后当成 bug 查。
 */
export function clear() {
  clearChatIds()
  state.token = null
  state.user = null
  try {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  } catch {
    // 隐私模式下 localStorage 可能直接抛异常。内存里已经清了，
    // 这次会话内是登出状态，只是刷新之后会恢复成登录——比整个页面白屏好
  }
}

/**
 * 从 localStorage 恢复登录态。应用启动时调一次。
 *
 * 这里能做的检查只有「令牌上的过期时间过了没」——那是个<b>本地</b>判断，
 * 不需要网络，能让一个明显过期的令牌在第一次导航之前就被丢掉（而不是让用户
 * 先看到页面、再被 401 弹走）。
 *
 * 但**它证明不了令牌是有效的**：签名对不对只有服务端知道，账号可能已经被删，
 * 密钥可能已经换了。所以恢复之后还要跑一次 `api/auth.js` 的 `refresh()`。
 */
export function restore() {
  let savedToken = null
  let savedUser = null
  try {
    savedToken = localStorage.getItem(TOKEN_KEY)
    const raw = localStorage.getItem(USER_KEY)
    savedUser = raw ? JSON.parse(raw) : null
  } catch {
    // 读不出来就当没登录。这里吞掉异常是安全的——最坏结果是让用户重新登录一次，
    // 而抛出去的后果是整个应用起不来
    return false
  }

  if (!savedToken || !savedUser || isExpired(savedToken)) {
    clear()
    return false
  }

  state.token = savedToken
  state.user = savedUser
  return true
}

/** 用服务端返回的最新用户信息刷新本地那份（`GET /api/auth/me` 的结果）。 */
export function updateUser(newUser) {
  state.user = newUser
  persist()
}

/* -------------------------------------------------------------- 会话 id */

/**
 * 清掉本地记着的全部会话 id。
 *
 * **登录、退出、401 三处都必须调。** sessionStorage 是「这个标签页」而不是
 * 「这个人」的存储——不清的话，换个账号登录会继承上一个人的 chatId，
 * 而后端现在会检查归属，于是打开就是 404「会话不存在或已被删除」。
 * 这个症状看起来像是会话数据丢了，实际原因却在这里，很难联想到。
 */
export function clearChatIds() {
  try {
    const doomed = []
    for (let i = 0; i < sessionStorage.length; i += 1) {
      const key = sessionStorage.key(i)
      if (key && key.startsWith(CHAT_ID_PREFIX)) doomed.push(key)
    }
    // 先收集再删：边遍历边删会让索引错位，可能漏掉一半
    for (const key of doomed) sessionStorage.removeItem(key)
  } catch {
    // 同 persist()：隐私模式下存不了也读不了，忽略即可
  }
}

/* ------------------------------------------------------------------ 内部 */

function persist() {
  try {
    localStorage.setItem(TOKEN_KEY, state.token)
    localStorage.setItem(USER_KEY, JSON.stringify(state.user))
  } catch {
    // 存不下就退化成「本次会话有效」。页面照样能用，只是刷新之后要重新登录
  }
}

/**
 * 令牌过期了吗。
 *
 * 只解码 payload、**不验签**——前端没有密钥，也验不了。所以这个函数只能用来
 * 「提前丢掉明显过期的令牌」，绝不能当成「令牌有效」的判断。
 *
 * exp 是 Unix 秒。留 10 秒余量：正好卡在过期那一刻发请求，服务端那边可能已经过了。
 */
function isExpired(jwt) {
  const payload = decodePayload(jwt)
  if (!payload || typeof payload.exp !== 'number') {
    // 解不出来就当作有效，交给服务端去判定。反过来（当作过期）的话，
    // 一个格式正常但我们解析失败的令牌会让用户莫名其妙地被登出
    return false
  }
  return payload.exp * 1000 <= Date.now() + 10_000
}

/**
 * 解出 JWT 的 payload 部分。
 *
 * 注意 base64url 的字母表（`-` `_`）和标准 base64 不同，而且**不带填充**，
 * `atob` 两种都不认，所以要先替换再补 `=`。
 */
function decodePayload(jwt) {
  try {
    const parts = jwt.split('.')
    if (parts.length !== 3) return null
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
    // payload 里可能有中文（username/nickname），atob 出来的是 latin1 字节串，
    // 要先转成字节再按 UTF-8 解码，否则中文会变成乱码
    const binary = atob(padded)
    const bytes = Uint8Array.from(binary, (ch) => ch.charCodeAt(0))
    return JSON.parse(new TextDecoder('utf-8').decode(bytes))
  } catch {
    return null
  }
}
