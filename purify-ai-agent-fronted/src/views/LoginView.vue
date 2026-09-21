<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthShell from '../components/AuthShell.vue'
import * as authApi from '../api/auth.js'
import { message, rawMessage, resolveMessage } from '../i18n/index.js'

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
/** 存的是描述符不是句子，见 i18n/index.js 的 message()：存句子的话切语言不会跟着变 */
const error = ref(null)
const submitting = ref(false)

const errorText = computed(() => resolveMessage(error.value))

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
  error.value = null

  if (!username.value.trim()) {
    error.value = message('auth.login.needUsername')
    return
  }
  if (!password.value) {
    error.value = message('auth.login.needPassword')
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
    error.value = err.message ? rawMessage(err.message) : message('auth.login.failed')
    // 只清密码，留着用户名——多数情况是密码打错了，让人重打一遍用户名没必要
    password.value = ''
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell :title="$t('auth.login.title')" :subtitle="$t('auth.login.subtitle')">
    <form @submit.prevent="submit">
      <label class="field">
        <span class="field-label">{{ $t('auth.login.username') }}</span>
        <input
          v-model="username"
          class="field-input"
          type="text"
          name="username"
          autocomplete="username"
          :placeholder="$t('auth.login.usernamePlaceholder')"
          :disabled="submitting"
        />
      </label>

      <label class="field">
        <span class="field-label">
          {{ $t('auth.login.password') }}
          <RouterLink to="/forgot" class="field-hint">{{ $t('auth.login.forgot') }}</RouterLink>
        </span>
        <input
          v-model="password"
          class="field-input"
          type="password"
          name="password"
          autocomplete="current-password"
          :placeholder="$t('auth.login.passwordPlaceholder')"
          :disabled="submitting"
        />
      </label>

      <p v-if="errorText" class="form-error">{{ errorText }}</p>

      <button class="btn-primary" type="submit" :disabled="submitting">
        {{ submitting ? $t('auth.login.submitting') : $t('auth.login.submit') }}
      </button>
    </form>

    <template #footer>
      {{ $t('auth.login.noAccount') }}<RouterLink to="/register">{{ $t('auth.login.toRegister') }}</RouterLink>
    </template>
  </AuthShell>
</template>
