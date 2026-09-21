import { http } from './http.js'
import * as auth from '../auth.js'

/**
 * 登录 / 注册 / 找回密码的接口。
 *
 * 和 `http.js` 里知识库那一节的分工不同：这里不只是拼 URL，
 * 还要**把结果写进登录态**（`setSession`）——否则每个调用点都要记得写一次，
 * 漏掉的那个表现是「登录成功了，但界面还是未登录」。
 *
 * 所以视图组件调这里的 `login()` / `register()`，不要直接调 `http.post`。
 */

/** 验证码的用途。和后端 `VerificationPurpose` 的枚举名一致。 */
export const PURPOSE = {
  REGISTER: 'REGISTER',
  RESET_PASSWORD: 'RESET_PASSWORD',
}

/**
 * 发验证码。
 *
 * 后端对两种用途的回应不一样：注册时如果邮箱已注册会明确报错；
 * 找回密码时无论邮箱存不存在都返回同样的成功响应（防止这个公开接口
 * 被拿来探测哪些邮箱在本站注册过）。这里不做区分，原样把服务端的话转达给用户。
 */
export async function sendCode(email, purpose) {
  const { data } = await http.post('/api/auth/code', { email, purpose })
  return data
}

/** 注册。成功后直接进入登录态，不用再走一次登录。 */
export async function register({ username, password, email, code, nickname }) {
  const { data } = await http.post('/api/auth/register', {
    username,
    password,
    email,
    code,
    nickname,
  })
  auth.setSession(data.token, data.user)
  return data.user
}

/** 登录。 */
export async function login(username, password) {
  const { data } = await http.post('/api/auth/login', { username, password })
  auth.setSession(data.token, data.user)
  return data.user
}

/**
 * 换头像。
 *
 * 成功了要把返回的用户信息写回本地那份（`auth.updateUser`）——否则头像在界面上
 * 要等下一次刷新才出现，用户会以为没传上去又传一遍。
 *
 * 这里不做类型和大小的校验：那些规则在后端（`ImageTypes`），前端再判一遍
 * 只会多一份会走偏的副本。传错了后端会返回一句能看懂的话，直接展示即可。
 */
export async function uploadAvatar(file) {
  const form = new FormData()
  form.append('file', file)
  const { data } = await http.post('/api/auth/avatar', form)
  auth.updateUser(data)
  return data
}

/** 用邮箱验证码重置密码。成功后**不会**自动登录，要用户自己用新密码登一次。 */
export async function resetPassword(email, code, newPassword) {
  await http.post('/api/auth/password/reset', { email, code, newPassword })
}

/**
 * 用令牌问一次「我是谁」。
 *
 * 应用启动时跑一次，用来把本地那个可能已经失效的令牌清掉
 * （见 `auth.js` 的 `restore()`：本地只能判过期时间，证明不了有效性）。
 *
 * **不能让它把用户登出**：令牌无效时后端返回 401，而 `http.js` 的拦截器
 * 看到 401 会清令牌并跳登录页。那正是这里想要的结果，所以不用特殊处理。
 */
export async function fetchMe() {
  const { data } = await http.get('/api/auth/me')
  auth.updateUser(data)
  return data
}

/**
 * 退出登录：先告诉服务端（只为留一条日志），然后清本地。
 *
 * 服务端调用失败也要清本地——用户点了退出，界面上就必须退出。
 * 因为一次网络抖动把人留在登录状态里，比「服务端少记了一行日志」严重得多。
 */
export async function logout() {
  try {
    await http.post('/api/auth/logout')
  } catch {
    // 令牌可能已经过期了，那次调用会 401。这本来就是要退出，忽略即可
  } finally {
    auth.clear()
  }
}
