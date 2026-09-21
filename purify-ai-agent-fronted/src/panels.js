import { ref } from 'vue'

/**
 * 两个侧边抽屉（设置、资料库）的开合状态。
 *
 * **为什么单独一个模块，而不是放在 `ChatRoom.vue` 里：**
 * 这两个抽屉的入口在用户菜单上，而用户菜单在三个地方都会渲染——
 * 对话页侧边栏底部、对话页顶栏、以及首页顶栏。状态放在聊天组件里的话，
 * 首页那个菜单要么打不开抽屉，要么就得把组件再挂一份、两边同步状态。
 *
 * 抽屉组件本身挂在 `App.vue` 上（全局只有一份），这里只负责「开着没开着」。
 * 和 `auth.js` 一个套路：模块级的响应式状态，谁都能读、谁都能改。
 */

export const settingsOpen = ref(false)
export const resourcesOpen = ref(false)

/**
 * 打开一个抽屉时**顺手关掉另一个**。
 *
 * 两个抽屉都是盖在页面上的浮层，同时开着的话后开的那个会把先开的挡住，
 * 而先开的那个还占着 Esc 的响应——按一次 Esc 只关掉一个，用户以为坏了。
 * 在这里收口，就不用每个调用点自己记得。
 */
export function openSettings() {
  resourcesOpen.value = false
  settingsOpen.value = true
}

export function closeSettings() {
  settingsOpen.value = false
}

export function openResources() {
  settingsOpen.value = false
  resourcesOpen.value = true
}

export function closeResources() {
  resourcesOpen.value = false
}
