<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import AuthShell from '../components/AuthShell.vue'
import * as authApi from '../api/auth.js'
import { useVerifyCode } from '../useVerifyCode.js'

/**
 * 找回密码：用邮箱验证码换一次改密码的机会。
 *
 * 验证码和注册用的是同一套机制，但**用途不同**（`RESET_PASSWORD`），
 * 所以注册时收到的码在这里用不了。后端按用途分开存，见 `VerificationPurpose`。
 *
 * 注意这个接口对「邮箱是否注册过」返回的是同一句话——后端刻意不泄露账号是否存在
 * （这是个公开接口，能区分就成了账号枚举器）。所以用户可能收不到邮件也不知道为什么，
 * 那是有意的取舍，页面上的文案要把这一点说清楚。
 */

const router = useRouter()

const {
  email,
  sending: codeSending,
  countdown,
  note: codeNote,
  error: codeError,
  send: sendCode,
  restoreCountdown,
} = useVerifyCode(authApi.PURPOSE.RESET_PASSWORD)

const codeValue = ref('')
const password = ref('')
const confirm = ref('')
const formError = ref('')
const submitting = ref(false)
/** 改成功了就切到「完成」状态，让用户知道下一步是去登录。 */
const done = ref(false)

const passwordMismatch = computed(() => confirm.value.length > 0 && password.value !== confirm.value)

const canSubmit = computed(
  () =>
    !submitting.value &&
    Boolean(email.value.trim()) &&
    Boolean(codeValue.value.trim()) &&
    Boolean(password.value) &&
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
    await authApi.resetPassword(email.value.trim(), codeValue.value.trim(), password.value)
    // 不自动登录：后端这一步只改密码、不发令牌。
    // 让用户用新密码登一次，也是对他「密码确实改成这个了」的一次确认
    done.value = true
  } catch (err) {
    formError.value = err.message || '重置失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell title="找回密码" subtitle="验证码会发到你注册时用的邮箱。">
    <!-- 成功之后把整个表单换掉，而不是弹一条提示：用户在这个页面上已经没事可做了，
         留着一堆填好的输入框会让人以为还没提交成功 -->
    <template v-if="done">
      <p class="form-note">密码已经改好了。用新密码登录即可。</p>
      <RouterLink class="btn-primary done-btn" to="/login">去登录</RouterLink>
    </template>

    <form v-else @submit.prevent="submit">
      <label class="field">
        <span class="field-label">注册邮箱</span>
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
        <span class="field-label">新密码</span>
        <input
          v-model="password"
          class="field-input"
          type="password"
          name="new-password"
          autocomplete="new-password"
          placeholder="至少 8 位"
          :disabled="submitting"
        />
      </label>

      <label class="field">
        <span class="field-label">
          确认新密码
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
        {{ submitting ? '提交中…' : '重置密码' }}
      </button>
    </form>

    <template #footer>
      想起来了？<RouterLink to="/login">去登录</RouterLink>
    </template>
  </AuthShell>
</template>

<style scoped>
/* 完成状态下那个「去登录」是个 RouterLink（渲染成 <a>），而 .btn-primary 是
   给 <button> 写的，所以要补上文字装饰和下划线这两处 */
.done-btn {
  text-decoration: none;
  margin-top: 18px;
}
</style>
