<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as auth from '../auth.js'
import * as authApi from '../api/auth.js'
import { isChatDark } from '../theme.js'
import { openSettings, openResources } from '../panels.js'

/**
 * 右上角（聊天页是侧边栏底部）的用户菜单：头像 + 下拉。
 *
 * 未登录时渲染一个「登录」按钮而不是空着——首页是公开的，
 * 未登录的访客需要一个明确的入口，而不是自己去找地址栏。
 *
 * **「知识库」那一项只对超级用户显示**，这只是界面显隐。真正的拦截是后端的
 * `@RequireAdmin`：手工改 localStorage 能让这一项冒出来，但点进去每个接口都 403。
 * 把它当成权限控制是错的，写在注释里免得以后有人依赖它。
 */

defineProps({
  /**
   * 菜单展开的方向。
   * `up` 给聊天页侧边栏底部用（菜单往上弹，不然会被窗口底边裁掉），
   * `down` 给首页顶栏用。
   */
  direction: { type: String, default: 'down' },
})

const router = useRouter()
const open = ref(false)
const root = ref(null)

const loggedIn = computed(() => auth.isLoggedIn())
const isAdmin = computed(() => auth.isAdmin())

/** 头像上那个字：优先昵称，退回用户名。空字符串会让圆圈看起来像坏了。 */
const initial = computed(() => {
  const name = auth.user()?.nickname || auth.user()?.username || ''
  return name.trim().charAt(0).toUpperCase() || '?'
})

const displayName = computed(() => auth.user()?.nickname || auth.user()?.username || '')

/** 用户设置的头像地址；没设过就是空串，那时退回首字母圆圈 */
const avatarUrl = computed(() => auth.user()?.avatar || '')

function toggle() {
  open.value = !open.value
}

function close() {
  open.value = false
}

/**
 * 打开一个侧边抽屉。
 *
 * 必须先 `close()` 收起菜单：不关的话菜单会留在抽屉底下，
 * 而它的「点别处收起」监听还挂着——抽屉里点第一下会先被它吃掉。
 */
function openPanel(openPanelFn) {
  close()
  openPanelFn()
}

async function logout() {
  close()
  await authApi.logout()
  // 回首页而不是留在聊天页：那个页面上的会话列表、历史都属于上一个账号，
  // 留在原地会先闪一下别人的内容再被 401 弹走
  await router.push('/')
}

/**
 * 点击别处收起菜单。
 *
 * 用 document 上的捕获阶段监听而不是给页面加一层遮罩：遮罩会挡住底下内容的点击，
 * 而用户点菜单外面一下的本意通常是「点那个东西」而不是「关掉菜单」。
 */
function onDocumentClick(event) {
  if (!open.value) return
  if (root.value && !root.value.contains(event.target)) close()
}

/** Esc 收起。键盘用户没法「点到外面去」。 */
function onKeydown(event) {
  if (event.key === 'Escape') close()
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div ref="root" class="user-menu" :class="[`dir-${direction}`, { dark: isChatDark }]">
    <!-- 未登录：只给一个入口 -->
    <RouterLink v-if="!loggedIn" class="signin" to="/login">{{ $t('menu.login') }}</RouterLink>

    <template v-else>
      <button
        class="trigger"
        type="button"
        :aria-expanded="open"
        aria-haspopup="menu"
        :title="displayName"
        @click="toggle"
      >
        <span class="avatar">
          <!-- 设过头像就显示图片，否则退回首字母。用 alt 而不是 aria-hidden：
               图片加载失败时浏览器会把 alt 当文字显示，那正好就是我们要的退路 -->
          <img v-if="avatarUrl" :src="avatarUrl" :alt="initial" />
          <template v-else>{{ initial }}</template>
        </span>
        <span class="name">{{ displayName }}</span>
        <svg class="caret" viewBox="0 0 12 12" aria-hidden="true">
          <path d="M3 4.5 6 7.5 9 4.5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>

      <!-- v-show 而不是 v-if：菜单开合很频繁，重建 DOM 会让过渡动画失效 -->
      <div v-show="open" class="dropdown" role="menu">
        <div class="who">
          <span class="who-name">{{ displayName }}</span>
          <span v-if="isAdmin" class="badge">{{ $t('menu.admin') }}</span>
        </div>

        <RouterLink v-if="isAdmin" class="item" to="/knowledge" role="menuitem" @click="close">
          {{ $t('menu.knowledge') }}
        </RouterLink>

        <!-- 这两个打开的是盖在页面上的抽屉，不是路由，所以用 button 而不是 RouterLink -->
        <button class="item" type="button" role="menuitem" @click="openPanel(openResources)">
          {{ $t('menu.resources') }}
        </button>
        <button class="item" type="button" role="menuitem" @click="openPanel(openSettings)">
          {{ $t('menu.settings') }}
        </button>

        <button class="item danger" type="button" role="menuitem" @click="logout">{{ $t('menu.logout') }}</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.user-menu {
  position: relative;
}

/* 未登录时那个「登录」。描边样式，不抢首页主按钮的注意力 */
.signin {
  display: inline-flex;
  align-items: center;
  padding: 6px 14px;
  border: 1px solid var(--line-strong);
  border-radius: 9px;
  color: #3d4658;
  font-size: 13.5px;
  text-decoration: none;
  transition: border-color 0.18s ease, color 0.18s ease;
}
.signin:hover {
  border-color: var(--brand);
  color: var(--brand);
}

.trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  border: none;
  border-radius: 10px;
  background: transparent;
  text-align: left;
  transition: background 0.16s ease;
}
.trigger:hover {
  background: rgba(10, 15, 30, 0.05);
}

.avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--brand);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  /* 头像图片要裁成圆形，所以这里不能让它溢出 */
  overflow: hidden;
}

.avatar img {
  display: block;
  width: 100%;
  height: 100%;
  /* cover 而不是 fill：用户传的图多半不是正方形，拉伸会把脸压扁 */
  object-fit: cover;
}

.name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: #2c3444;
  font-size: 13.5px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.caret {
  flex: none;
  width: 12px;
  height: 12px;
  color: #8b93a6;
}

.dropdown {
  position: absolute;
  z-index: 40;
  min-width: 172px;
  padding: 6px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 16px 34px -18px rgba(10, 15, 30, 0.32);
}

/* 往下弹（首页顶栏） */
.dir-down .dropdown {
  top: calc(100% + 8px);
  right: 0;
}
/* 往上弹（聊天页侧边栏底部）。往上的话菜单底边要贴住按钮，
   所以 right 也跟着左对齐，让它从侧边栏宽度里长出来 */
.dir-up .dropdown {
  bottom: calc(100% + 8px);
  left: 0;
  right: 0;
}

.who {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px 9px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 5px;
}

.who-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.badge {
  flex: none;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--brand-soft);
  color: var(--brand-deep);
  font-size: 11px;
  font-weight: 500;
}

.item {
  display: block;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #3d4658;
  font-size: 13.5px;
  text-align: left;
  text-decoration: none;
  transition: background 0.14s ease, color 0.14s ease;
}
.item:hover {
  background: #f3f5fa;
  color: var(--ink);
}

.item.danger:hover {
  background: #fdf4f4;
  color: var(--danger);
}

/* ------------------------------------------------------------------- 深色 */

/*
 * 用户菜单是**唯一一个横跨两种配色页面的组件**：它在对话页（可能深色）
 * 和首页（永远浅色）里都会被渲染。所以它不能读 `theme`，要读 `isChatDark`——
 * 那个值同时算进了「用户在哪儿」和「用户选了什么」，见 theme.js。
 *
 * 颜色是直接写死的，没走 ChatRoom 那套 --c-* token：那些 token 定义在
 * `.shell` 上，而首页上的用户菜单根本不是 `.shell` 的后代，拿不到。
 * 用户菜单要用的颜色就这么几个，重复一遍比把 token 提到全局（那会波及
 * 另外四个页面）划算。
 */
.user-menu.dark .trigger:hover {
  background: rgba(255, 255, 255, 0.07);
}
.user-menu.dark .name {
  color: #c8ccd6;
}
.user-menu.dark .caret {
  color: #737b8b;
}
.user-menu.dark .dropdown {
  border-color: #2e323a;
  background: #1e2026;
  /* 深色下用纯黑投影，不然浮层和底下的深色糊在一起看不出层次 */
  box-shadow: 0 16px 34px -18px rgba(0, 0, 0, 0.75);
}
.user-menu.dark .who {
  border-bottom-color: #2e323a;
}
.user-menu.dark .who-name {
  color: #e6e8ee;
}
.user-menu.dark .badge {
  background: rgba(77, 107, 254, 0.24);
  color: #a9b8ff;
}
.user-menu.dark .item {
  color: #c8ccd6;
}
.user-menu.dark .item:hover {
  background: #262a31;
  color: #e6e8ee;
}
.user-menu.dark .item.danger:hover {
  background: #3a2020;
  color: #f08a86;
}
</style>
