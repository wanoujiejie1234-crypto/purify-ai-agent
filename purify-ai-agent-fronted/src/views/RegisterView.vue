<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import AuthShell from '../components/AuthShell.vue'
import * as authApi from '../api/auth.js'
import { useVerifyCode } from '../useVerifyCode.js'
import { message, rawMessage, resolveMessage } from '../i18n/index.js'

/**
 * 注册页。
 *
 * 单个表单（而不是「先验邮箱，再设密码」那种两步式）：后端的注册接口本来就是
 * 一次调用收全部字段 + 验证码，拆成两步只会多一层本地状态，
 * 而用户最后还是要在同一步里看到「用户名被占用」这类错误。
 *
 * 邮箱必须真实可收信——验证码是唯一的凭证，后端不会把码放在响应里（那是给爆破送弹药）。
 */

const router = useRouter()

// 解构成顶层 ref，模板里就能直接写 email / countdown（Vue 会对顶层 ref 自动解包）。
// 不这么做的话模板里到处都是 `code.email.value`，既难看又容易漏掉 .value
const {
  email,
  sending: codeSending,
  countdown,
  noteText: codeNote,
  errorText: codeError,
  send: sendCode,
  restoreCountdown,
} = useVerifyCode(authApi.PURPOSE.REGISTER)

const username = ref('')
const password = ref('')
const confirm = ref('')
const codeValue = ref('')
/** 存描述符不是句子，见 i18n/index.js 的 message() */
const formError = ref(null)
const submitting = ref(false)

const formErrorText = computed(() => resolveMessage(formError.value))

/**
 * 两边密码一致才能提交。
 *
 * 在前端挡一次是因为「两次输入不一致」让用户白等一次网络往返很没必要；
 * 但**密码强度的真正校验在后端**（`AuthService.requirePassword`），
 * 前端不重复实现一遍——两处规则迟早会漂移，而且后端的才算数。
 * 这里那个「至少 8 位」只是一句提前提示。
 */
const passwordMismatch = computed(() => confirm.value.length > 0 && password.value !== confirm.value)

const canSubmit = computed(
  () =>
    !submitting.value &&
    Boolean(username.value.trim()) &&
    Boolean(password.value) &&
    Boolean(email.value.trim()) &&
    Boolean(codeValue.value.trim()) &&
    !passwordMismatch.value,
)

async function submit() {
  if (submitting.value) return
  formError.value = null

  if (passwordMismatch.value) {
    formError.value = message('auth.register.mismatch')
    return
  }
  if (password.value.length < 8) {
    formError.value = message('auth.register.passwordTooShort')
    return
  }

  submitting.value = true
  try {
    await authApi.register({
      username: username.value.trim(),
      password: password.value,
      email: email.value.trim(),
      // trim 不能省：从邮件里复制粘贴验证码很容易带上空白字符，
      // 而它是 CHAR(6)，多一个空格就永远对不上
      code: codeValue.value.trim(),
    })
    // 注册成功即登录（后端一并签发了令牌），直接进首页
    await router.replace('/')
  } catch (err) {
    formError.value = err.message ? rawMessage(err.message) : message('auth.register.failed')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell :title="$t('auth.register.title')" :subtitle="$t('auth.register.subtitle')">
    <form @submit.prevent="submit">
      <label class="field">
        <span class="field-label">{{ $t('auth.register.email') }}</span>
        <span class="field-row">
          <input
            v-model="email"
            class="field-input"
            type="email"
            name="email"
            autocomplete="email"
            placeholder="you@example.com"
            :disabled="submitting"
            @blur="restoreCountdown()"
          />
          <button
            class="btn-ghost"
            type="button"
            :disabled="codeSending || countdown > 0 || submitting"
            @click="sendCode()"
          >
            <template v-if="countdown > 0">{{ $t('auth.code.resendIn', { n: countdown }) }}</template>
            <template v-else-if="codeSending">{{ $t('auth.code.sending') }}</template>
            <template v-else>{{ $t('auth.code.send') }}</template>
          </button>
        </span>
      </label>

      <label class="field">
        <span class="field-label">{{ $t('auth.register.code') }}</span>
        <input
          v-model="codeValue"
          class="field-input"
          type="text"
          name="code"
          inputmode="numeric"
          autocomplete="one-time-code"
          maxlength="6"
          :placeholder="$t('auth.register.codePlaceholder')"
          :disabled="submitting"
        />
      </label>

      <label class="field">
        <span class="field-label">{{ $t('auth.register.username') }}</span>
        <input
          v-model="username"
          class="field-input"
          type="text"
          name="username"
          autocomplete="username"
          :placeholder="$t('auth.register.usernamePlaceholder')"
          :disabled="submitting"
        />
      </label>

      <label class="field">
        <span class="field-label">{{ $t('auth.register.password') }}</span>
        <input
          v-model="password"
          class="field-input"
          type="password"
          name="password"
          autocomplete="new-password"
          :placeholder="$t('auth.register.passwordPlaceholder')"
          :disabled="submitting"
        />
      </label>

      <label class="field">
        <span class="field-label">
          {{ $t('auth.register.confirm') }}
          <span v-if="passwordMismatch" class="field-hint">{{ $t('auth.register.mismatchHint') }}</span>
        </span>
        <input
          v-model="confirm"
          class="field-input"
          type="password"
          name="confirm"
          autocomplete="new-password"
          :placeholder="$t('auth.register.confirmPlaceholder')"
          :disabled="submitting"
        />
      </label>

      <p v-if="codeNote" class="form-note">{{ codeNote }}</p>
      <p v-if="codeError" class="form-error">{{ codeError }}</p>
      <p v-if="formErrorText" class="form-error">{{ formErrorText }}</p>

      <button class="btn-primary" type="submit" :disabled="!canSubmit">
        {{ submitting ? $t('auth.register.submitting') : $t('auth.register.submit') }}
      </button>
    </form>

    <template #footer>
      {{ $t('auth.register.hasAccount') }}<RouterLink to="/login">{{ $t('auth.register.toLogin') }}</RouterLink>
    </template>
  </AuthShell>
</template>
