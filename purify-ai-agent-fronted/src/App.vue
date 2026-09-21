<script setup>
import SettingsDrawer from './components/SettingsDrawer.vue'
import ResourceDrawer from './components/ResourceDrawer.vue'
import { settingsOpen, resourcesOpen } from './panels.js'

/**
 * 根组件：路由出口 + 两个全局抽屉。
 *
 * **抽屉为什么挂在这里而不是挂在聊天页里：** 它们的入口是用户菜单，而用户菜单
 * 在三个地方都会渲染（对话页侧栏底部、对话页顶栏、首页顶栏）。挂在聊天页里的话，
 * 首页那个菜单就打不开抽屉；两处各挂一份又要同步状态。挂在这儿全局只有一份，
 * 状态由 `panels.js` 管，谁都能打开。
 *
 * `v-if` 而不是 `v-show`：抽屉每次打开都要重新拉一次画像，组件重建是最省事的做法
 * （在 onMounted 里取），也省得为「打开时刷新」加一个 watch——这个项目里没有 watch，
 * 不打算为这件事开个头。
 */
</script>

<template>
  <RouterView />
  <SettingsDrawer v-if="settingsOpen" />
  <ResourceDrawer v-if="resourcesOpen" />
</template>
