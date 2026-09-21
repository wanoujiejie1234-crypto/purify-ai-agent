import { computed, onBeforeUnmount, ref } from 'vue'
import * as authApi from './api/auth.js'
import { message, rawMessage, resolveMessage } from './i18n/index.js'

/**
 * 「填邮箱 → 发验证码 → 倒计时」这一小段交互。注册页和找回密码页共用。
 *
 * 抽出来的理由不是省几行：倒计时一旦两边各写一份，就一定会漂移成
 * 「注册页等 60 秒、找回密码页等 30 秒」，而用户是在这两个页面之间来回跳的
 * （注册时发现邮箱注册过了 → 转去找回密码）。而且**等待秒数必须和后端一致**，
 * 后端那边的 `purify.auth.code.resend-seconds` 是 60。
 */

/**
 * 倒计时记在这里而不是只放内存里。
 *
 * 用户在等待期间刷新一下页面，内存里的倒计时就没了，于是按钮又能点——
 * 点下去后端会正确地拒绝（`CODE_TOO_FREQUENT`），但用户看到的是
 * 「我明明刚发过，怎么又说太频繁」，像是系统在乱报错。
 * 存在 sessionStorage 里，刷新之后倒计时接着走。
 */
const SENT_AT_PREFIX = 'purify:codeSentAt:'

/** 和后端 `purify.auth.code.resend-seconds` 一致。改一边要改另一边。 */
const RESEND_SECONDS = 60

export function useVerifyCode(purpose) {
  const email = ref('')
  const sending = ref(false)
  /** 剩余秒数。大于 0 时按钮禁用并显示倒计时。 */
  const countdown = ref(0)
  /**
   * 中性提示（「验证码已发送」这类）和错误。
   *
   * **存的是描述符不是句子**（见 i18n/index.js 的 message()）：这两个 ref 会被
   * 直接渲染到注册页和找回密码页上，存句子的话切了语言它们不会重算，
   * 表现是「界面英文了，一点获取验证码又冒出一句中文」。
   * 所以这里额外给出 `noteText` / `errorText` 两个翻好的版本，模板直接用它。
   */
  const note = ref(null)
  const error = ref(null)

  const noteText = computed(() => resolveMessage(note.value))
  const errorText = computed(() => resolveMessage(error.value))

  let timer = null

  function stopTimer() {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  /** 从 `seconds` 开始往下数。每秒减一（而不是按绝对时间算），够用且简单。 */
  function startCountdown(seconds) {
    stopTimer()
    countdown.value = Math.max(0, Math.ceil(seconds))
    if (countdown.value === 0) return
    timer = setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0) stopTimer()
    }, 1000)
  }

  /**
   * 读上次发送时间，把倒计时接上。
   *
   * 页面挂载时调一次。注意它按 (用途, 邮箱) 分开记——用户改了邮箱就应该能立刻重发，
   * 因为那是发给另一个地址，和限流要防的「对同一个邮箱刷」不是一件事。
   */
  function restoreCountdown() {
    const sentAt = readSentAt(email.value)
    if (!sentAt) return
    const elapsed = (Date.now() - sentAt) / 1000
    startCountdown(RESEND_SECONDS - elapsed)
  }

  async function send() {
    if (sending.value || countdown.value > 0) return

    error.value = null
    note.value = null

    const address = email.value.trim()
    if (!address) {
      error.value = message('auth.code.needEmail')
      return
    }

    sending.value = true
    try {
      const result = await authApi.sendCode(address, purpose)
      email.value = address
      writeSentAt(address)
      startCountdown(RESEND_SECONDS)
      // 用服务端那句话。找回密码那条链路无论邮箱存不存在都是同一句
      // （后端刻意不泄露账号是否存在），原样显示才对得上
      note.value = result?.message ? rawMessage(result.message) : message('auth.code.sent')
    } catch (err) {
      error.value = err.message ? rawMessage(err.message) : message('auth.code.failed')
      // 后端说还在冷却里：它知道精确的剩余秒数（本地这份可能因为换标签页而丢了），
      // 但那个秒数只在 message 里。与其去解析文案，不如把按钮重新放开——
      // 用户再点一次就会再次看到剩余时间，不会出现「按钮灰着但不说为什么」
      countdown.value = 0
      stopTimer()
    } finally {
      sending.value = false
    }
  }

  onBeforeUnmount(stopTimer)

  return { email, sending, countdown, note, noteText, error, errorText, send, restoreCountdown }
}

/* ------------------------------------------------------------------ 内部 */

function storageKey(email) {
  // 邮箱用小写：后端那边统一归一化过，这里也归一化，
  // 否则 A@x.com 和 a@x.com 会被当成两个不同的冷却窗口
  return `${SENT_AT_PREFIX}${email.trim().toLowerCase()}`
}

function readSentAt(email) {
  if (!email) return 0
  try {
    const raw = sessionStorage.getItem(storageKey(email))
    const value = raw ? Number(raw) : 0
    return Number.isFinite(value) ? value : 0
  } catch {
    return 0
  }
}

function writeSentAt(email) {
  try {
    sessionStorage.setItem(storageKey(email), String(Date.now()))
  } catch {
    // 存不下就退化成「本次会话内存里有效」：倒计时照走，只是刷新之后会重置
  }
}
