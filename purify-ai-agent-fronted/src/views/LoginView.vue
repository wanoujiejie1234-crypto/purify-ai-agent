<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthShell from '../components/AuthShell.vue'
import * as authApi from '../api/auth.js'

/**
 * 登录页。
 *
 * 用户名 + 密码，**不是邮箱**：项目里那个超级用户叫 `root_agent` 而且没有邮箱
 * （`user.email` 可空），用邮箱当账号的话它就没法登录了。后端的说明在 `LoginRequest` 里。
 *
 * 成功后跳到 `?redirect=` 指定的地方——用户常常是从某个功能页被拦下来的，
 * 把他丢回首页等于让他重新找一遍刚才想去的地方。
 */

const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const error = ref('')
const submitting = ref(false)

/**
 * 登录成功后该去哪儿。
 *
 * `redirect` 只接受**站内路径**：以 `/` 开头、且不是 `//`（`//evil.com` 是一个
 * 合法的绝对 URL，直接丢给 `router.push` 会把用户送去外站——一个开放重定向）。
 * 不合法就回首页。
 */
const redirectTo = computed(() => {
  const raw = route.query.redirect
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//')) {
    return '/'
  }
  return raw
})

async function submit() {
  if (submitting.value) return
  error.value = ''

  if (!username.value.trim()) {
    error.value = '请输入用户名'
    return
  }
  if (!password.value) {
    error.value = '请输入密码'
    return
  }

  submitting.value = true
  try {
    await authApi.login(username.value.trim(), password.value)
    // 用 replace 而不是 push：登录页不该留在浏览器的后退栈里，
    // 否则用户从聊天页点后退会回到登录表单，看起来像掉线了
    await router.replace(redirectTo.value)
  } catch (err) {
    // 后端对「用户名不存在」和「密码不对」返回的是同一句话，这是有意的
    // （否则这个接口就成了一个账号枚举器）。所以这里原样显示即可，不用自己再包装
    error.value = err.message || '登录失败，请稍后重试。'
    // 只清密码，留着用户名——多数情况是密码打错了，让人重打一遍用户名没必要
    password.value = ''
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell title="登录" subtitle="登录后才能开始对话、查看自己的历史记录。">
    <form @submit.prevent="submit">
      <label class="field">
        <span class="field-label">用户名</span>
        <input
          v-model="username"
          class="field-input"
          type="text"
          name="username"
          autocomplete="username"
          placeholder="请输入用户名"
          :disabled="submitting"
        />
      </label>

      <label class="field">
        <span class="field-label">
          密码
          <RouterLink to="/forgot" class="field-hint">忘记密码？</RouterLink>
        </span>
        <input
          v-model="password"
          class="field-input"
          type="password"
          name="password"
          autocomplete="current-password"
          placeholder="请输入密码"
          :disabled="submitting"
        />
      </label>

      <p v-if="error" class="form-error">{{ error }}</p>

      <button class="btn-primary" type="submit" :disabled="submitting">
        {{ submitting ? '登录中…' : '登录' }}
      </button>
    </form>

    <template #footer>
      还没有账号？<RouterLink to="/register">去注册</RouterLink>
    </template>
  </AuthShell>
</template>
