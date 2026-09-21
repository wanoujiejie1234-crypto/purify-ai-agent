import { createI18n } from 'vue-i18n'
import zhCN from './zh-CN.js'
import enUS from './en-US.js'

/**
 * 中英双语。
 *
 * 设计上和 `theme.js` 是同一套：用户偏好存 localStorage、启动时在挂载之前恢复、
 * 模块里没有 `watch`（全项目一处都没有，写入和唯一的修改入口放在一起是这个项目的既有约定）。
 *
 * **它和主题有一个关键差别**：主题是「当前页面渲不渲染成深色」，
 * 所以深色只在对话页生效；而语言是**全局**的 —— 没有「首页中文、对话页英文」
 * 这种状态可言，用户在任何一页切了语言，其它页面跟着变才是对的。
 * 所以这里不学 theme.js 的 `chatPageActive`，就是一个全局值。
 */

const LOCALE_KEY = 'purify:locale'

export const ZH = 'zh-CN'
export const EN = 'en-US'

/** 默认中文。后端 `messages.properties` 的默认 locale 也是中文，两边一致。 */
export const DEFAULT_LOCALE = ZH

/** 设置抽屉里那组选项。顺序就是显示顺序。 */
export const LOCALE_OPTIONS = [
  { value: ZH, label: '简体中文' },
  // 语言名用它自己的语言写（不写成「英语」）：用户看不懂当前语言时，
  // 一个「English」比一个「英语」有用得多，这是各家设置页的通行做法
  { value: EN, label: 'English' },
]

export const i18n = createI18n({
  // 用组合式 API 那一套。legacy 模式下 `i18n.global` 不是 ref，
  // 下面那些从模块里读 locale 的地方就都拿不到响应式
  legacy: false,
  // 模板里可以直接写 $t，不用每个组件都 import 一遍
  globalInjection: true,
  locale: readStoredLocale(),
  fallbackLocale: DEFAULT_LOCALE,
  // 少打一条「翻译缺失」的控制台警告前先想清楚：缺键是**必须**能被看见的
  missingWarn: true,
  fallbackWarn: true,
  messages: { [ZH]: zhCN, [EN]: enUS },
})

/**
 * 翻译。给**模块级代码**用（`chatConfig.js` 的欢迎语、`api/http.js` 的报错文案……）。
 *
 * 组件里请用 `useI18n()` 拿到的那个 `t`；这里导出的这个是为了让「不在组件里」的地方
 * 也能翻——那些地方没有 setup 上下文，`useI18n()` 会直接报错。
 *
 * <b>先读一下 `localeRef.value` 再翻。</b>这不是废话：`i18n.global.t` 内部确实会读
 * locale，但那份依赖关系记在这个函数**之外**，写在组件里没问题（渲染函数本身就是
 * 一个响应式作用域），而写在 `computed` 里就未必 —— 一旦 vue-i18n 哪天改了内部实现，
 * 表现是「切了语言，某些地方不跟着变」，而且不报任何错。
 * 显式读一次，依赖就落在调用方自己的作用域里，这个保证不再依赖第三方实现细节。
 */
export function t(key, ...args) {
  localeRef.value
  return i18n.global.t(key, ...args)
}

/**
 * 当前的 locale，响应式。
 *
 * 暴露成 ref 而不是函数，是为了让 `computed`/模板能直接依赖它。
 */
export const localeRef = i18n.global.locale

/** 是不是英文。控制少数「非文案」的差异时用它（比如日期格式）。 */
export function isEnglish() {
  return localeRef.value === EN
}

/**
 * 切语言。
 *
 * 唯一的写入口：改状态、落盘、同步 `<html lang>` 三件事必须一起发生。
 * `lang` 不是装饰 —— 它决定浏览器用哪套字体、拼写检查认哪种语言、
 * 以及读屏软件按哪种语音朗读。
 */
export function setLocale(next) {
  localeRef.value = next === EN ? EN : ZH
  document.documentElement.lang = localeRef.value
  try {
    localStorage.setItem(LOCALE_KEY, localeRef.value)
  } catch {
    // 隐私模式下写不进去。内存里已经改了，本次会话有效、刷新后回到默认，
    // 比整个页面白屏好（同 theme.js）
  }
}

/**
 * 从 localStorage 恢复。**要在 `createApp().mount()` 之前调**，理由和主题一样：
 * 挂载之后再恢复的话，英文用户每次刷新都会先看到一帧中文。
 */
export function restoreLocale() {
  setLocale(readStoredLocale())
}

/**
 * 一条「待翻译」的提示：本地文案存**键**，后端来的那句话存**原文**。
 *
 * <p><b>为什么不能把翻好的字符串直接存进 ref。</b>这是这批 i18n 里最容易漏的一处：
 * `ref('请输入用户名')` 是在赋值的**那一刻**求值的，之后切语言它不会重算 ——
 * 界面整个变成英文了，一报错又冒出一句中文，而且只有真去制造一次错误才看得见。
 * 所以本地文案一律存键，渲染时再翻。
 *
 * <p>为什么还要留 `text` 那一支：从后端来的报错（「这个邮箱已经注册过了」）
 * 是后端按 `Accept-Language` 翻好之后发过来的**散文**，前端手里没有它的键，
 * 只能原样显示。它的语言在收到那一刻就定下了，切语言不会跟着变 ——
 * 这是个已知的取舍：要让它也跟着变，得让后端返回错误码 + 参数，
 * 而不是一句话，那是另一个量级的改动。
 */
export function message(key, params) {
  return { key, params }
}

/** 把 {@link message} 造的描述符解成当前语言下的文案。请放在 computed 里调。 */
export function resolveMessage(descriptor) {
  if (!descriptor) return ''
  if (descriptor.text) return descriptor.text
  return t(descriptor.key, descriptor.params)
}

/** 直接包一份后端原文（或者任何不需要翻译的字符串）。 */
export function rawMessage(text) {
  return { text }
}

/** 没存过、或者存的值不认识，一律回默认值 —— 别把 undefined 塞进 locale。 */
function readStoredLocale() {
  try {
    const saved = localStorage.getItem(LOCALE_KEY)
    return LOCALE_OPTIONS.some((option) => option.value === saved) ? saved : DEFAULT_LOCALE
  } catch {
    return DEFAULT_LOCALE
  }
}

/**
 * 当前该发给后端的 `Accept-Language`。
 *
 * 后端用 Spring Boot 默认的 `AcceptHeaderLocaleResolver` 解析这个头，
 * 翻译 `messages*.properties` 里的文案。**不带这个头的话后端会回落成中文**，
 * 表现是「界面全英文了，一报错冒出一句中文」。
 */
export function acceptLanguageHeader() {
  return localeRef.value
}
