<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import AuthShell from '../components/AuthShell.vue'
import * as authApi from '../api/auth.js'
import { useVerifyCode } from '../useVerifyCode.js'

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
  note: codeNote,
  error: codeError,
  send: sendCode,
  restoreCountdown,
} = useVerifyCode(authApi.PURPOSE.REGISTER)

const username = ref('')
const password = ref('')
const confirm = ref('')
const codeValue = ref('')
const formError = ref('')
const submitting = ref(false)

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
  formError.value = ''

  if (passwordMismatch.value) {
    formError.value = '两次输入的密码不一样'
    return
  }
  if (password.value.length < 8) {
    formError.value = '密码至少 8 位'
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
    formError.value = err.message || '注册失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell title="创建账号" subtitle="需要一个能收信的邮箱来完成验证。">
    <form @submit.prevent="submit">
      <label class="field">
        <span class="field-label">邮箱</span>
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
            <template v-if="countdown > 0">{{ countdown }}s 后重发</template>
            <template v-else-if="codeSending">发送中…</template>
            <template v-else>获取验证码</template>
          </button>
        </span>
      </label>

      <label class="field">
        <span class="field-label">验证码</span>
        <input
          v-model="codeValue"
          class="field-input"
          type="text"
          name="code"
          inputmode="numeric"
          autocomplete="one-time-code"
          maxlength="6"
          placeholder="6 位数字"
          :disabled="submitting"
        />
      </label>

      <label class="field">
        <span class="field-label">用户名</span>
        <input
          v-model="username"
          class="field-input"
          type="text"
          name="username"
          autocomplete="username"
          placeholder="3-20 个字符"
          :disabled="submitting"
        />
      </label>

      <label class="field">
        <span class="field-label">密码</span>
        <input
          v-model="password"
          class="field-input"
          type="password"
          name="password"
          autocomplete="new-password"
          placeholder="至少 8 位"
          :disabled="submitting"
        />
      </label>

      <label class="field">
        <span class="field-label">
          确认密码
          <span v-if="passwordMismatch" class="field-hint">两次输入不一致</span>
        </span>
        <input
          v-model="confirm"
          class="field-input"
          type="password"
          name="confirm"
          autocomplete="new-password"
          placeholder="再输一遍"
          :disabled="submitting"
        />
      </label>

      <p v-if="codeNote" class="form-note">{{ codeNote }}</p>
      <p v-if="codeError" class="form-error">{{ codeError }}</p>
      <p v-if="formError" class="form-error">{{ formError }}</p>

      <button class="btn-primary" type="submit" :disabled="!canSubmit">
        {{ submitting ? '注册中…' : '注册并登录' }}
      </button>
    </form>

    <template #footer>
      已经有账号了？<RouterLink to="/login">去登录</RouterLink>
    </template>
  </AuthShell>
</template>
