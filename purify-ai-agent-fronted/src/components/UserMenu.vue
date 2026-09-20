<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as auth from '../auth.js'
import * as authApi from '../api/auth.js'

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

function toggle() {
  open.value = !open.value
}

function close() {
  open.value = false
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
  <div ref="root" class="user-menu" :class="`dir-${direction}`">
    <!-- 未登录：只给一个入口 -->
    <RouterLink v-if="!loggedIn" class="signin" to="/login">登录</RouterLink>

    <template v-else>
      <button
        class="trigger"
        type="button"
        :aria-expanded="open"
        aria-haspopup="menu"
        :title="displayName"
        @click="toggle"
      >
        <span class="avatar">{{ initial }}</span>
        <span class="name">{{ displayName }}</span>
        <svg class="caret" viewBox="0 0 12 12" aria-hidden="true">
          <path d="M3 4.5 6 7.5 9 4.5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>

      <!-- v-show 而不是 v-if：菜单开合很频繁，重建 DOM 会让过渡动画失效 -->
      <div v-show="open" class="dropdown" role="menu">
        <div class="who">
          <span class="who-name">{{ displayName }}</span>
          <span v-if="isAdmin" class="badge">超级管理员</span>
        </div>

        <RouterLink v-if="isAdmin" class="item" to="/knowledge" role="menuitem" @click="close">
          知识库
        </RouterLink>

        <button class="item danger" type="button" role="menuitem" @click="logout">退出登录</button>
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
</style>
