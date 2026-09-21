import { computed, ref } from 'vue'

/**
 * 白天 / 黑夜主题。
 *
 * **主题只作用于对话页**，这是有意的：全站深色要把首页、登录注册、知识库、
 * 用户菜单四套样式一起翻过来（全项目约 179 处硬编码颜色），而现阶段的诉求
 * 只是「设置能改对话页的样子」。所以这里不碰 `base.css` 的 `:root` ——
 * `--page` / `--line` / `--ink` 是其它页面共用的，改了会连累它们。
 *
 * 那深色靠什么生效？靠挂在 `<html>` 上的 `theme-dark` 类，**而这个类只在
 * 对话页开着的时候存在**（见下面的 enterChatPage / leaveChatPage）。
 * 于是从对话页返回首页时，深色自动消失，首页不会被带偏。
 * `base.css` 里只有两条规则吃这个类：body 的背景色和全局滚动条 ——
 * 那两处没法靠组件里的 scoped 样式覆盖（一个在组件之上，一个是 `*` 选择器）。
 *
 * 和 `purify:sidebarCollapsed` 一样存在 localStorage 而不是 sessionStorage：
 * 这是**用户对这个界面的偏好**，不该因为开个新标签页就变回去。
 * 同理，这里也**没有 watch** —— 写入和唯一的修改入口放在一起，
 * 是这个项目既有的写法（全项目没有一处 watch）。
 */

const THEME_KEY = 'purify:theme'

const DARK = 'dark'
const LIGHT = 'light'

/** 当前主题。响应式的，设置面板和聊天页都读它。 */
export const theme = ref(LIGHT)

/**
 * 对话页是不是开着。
 *
 * 单独用一个变量记着，而不是让 theme.js 去问路由：这样文件之间的关系是单向的，
 * 也就不用 import router（那会绕回 router → views → theme.js 的环）。
 *
 * 它是响应式的，因为**界面要按它来决定自己长什么样**：用户菜单、设置抽屉
 * 这些组件在两个地方都会被用到（对话页里和首页上），它们需要知道
 * 「我现在是不是一个深色界面的一部分」——在首页上答案是「不是」，
 * 哪怕用户的偏好就是深色，因为首页并没有深色样式。
 */
export const chatPageActive = ref(false)

/**
 * 现在渲染出来的界面是不是深色的。
 *
 * 组件里绑类名一律用这个，**不要直接用 `theme`**：用 `theme` 的话，
 * 用户在首页把偏好设成深色，首页上的用户菜单和设置抽屉也会跟着变黑，
 * 而首页本身是浅色的——看起来像坏了。
 */
export const isChatDark = computed(() => chatPageActive.value && theme.value === DARK)

/**
 * 切换主题。
 *
 * 唯一的写入口：改状态、落盘、应用到 document 三件事必须一起发生，
 * 分开写的话总有一处会漏。
 */
export function setTheme(next) {
  theme.value = next === DARK ? DARK : LIGHT
  try {
    localStorage.setItem(THEME_KEY, theme.value)
  } catch {
    // 隐私模式下写不进去。内存里已经改了，本次会话有效、刷新后回到默认，
    // 比整个页面白屏好
  }
  apply()
}

/**
 * 从 localStorage 恢复。应用启动时调一次。
 *
 * **要在 `createApp().mount()` 之前调**（见 main.js）：挂载之后再恢复的话，
 * 深色用户每次刷新都会先看到一帧浅色再跳成深色。
 */
export function restoreTheme() {
  try {
    if (localStorage.getItem(THEME_KEY) === DARK) {
      theme.value = DARK
    }
  } catch {
    // 读不出来就用浅色，不影响使用
  }
  apply()
}

/**
 * 进对话页 / 离开对话页。
 *
 * 由 `ChatRoom.vue` 调用。进的时候**在 setup 里同步调**（不是 onMounted）：
 * mounted 时首帧已经画完了，深色会闪一下。离开时必须调，
 * 否则深色会跟着用户回到首页。
 */
export function enterChatPage() {
  chatPageActive.value = true
  apply()
}

export function leaveChatPage() {
  chatPageActive.value = false
  apply()
}

/** 把当前主题落到 `<html>` 上。只有「对话页开着 且 选了深色」时才加类。 */
function apply() {
  document.documentElement.classList.toggle('theme-dark', isChatDark.value)
}
