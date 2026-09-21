<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as auth from '../auth.js'
import { uploadAvatar } from '../api/auth.js'
import { fetchProfile, updateProfile } from '../api/http.js'
import { isChatDark, setTheme, theme } from '../theme.js'
import { closeSettings } from '../panels.js'
import { LOCALE_OPTIONS, localeRef, setLocale } from '../i18n/index.js'
import { message, rawMessage, resolveMessage } from '../i18n/index.js'

/**
 * 设置抽屉：头像、外观、语言、用户画像。
 *
 * 和 `UserMenu` 一样，这个组件在对话页（可能深色）和首页（永远浅色）都会出现，
 * 所以配色一律跟着 `isChatDark` 走，不读 `theme`——用户在首页把偏好设成深色时，
 * 首页上的抽屉仍然是浅色的，因为首页本身没有深色样式。
 * 颜色不共用 ChatRoom 的 `--c-*` token：那些定义在 `.shell` 上，首页的抽屉拿不到。
 * 这里自带一套 `--d-*`，见样式块。
 */

/**
 * 用户选的是不是深色。
 *
 * 和 `isChatDark` 不是一回事：那个是「当前页面实际渲染成什么样」，
 * 在首页上永远是 false；这个是「偏好是什么」。白天/黑夜两个按钮的选中态
 * 要看偏好——看 isChatDark 的话，用户在首页选深色会看不到任何按钮被点亮。
 */
const darkSelected = computed(() => theme.value === 'dark')

/* ------------------------------------------------------------------ 头像 */

const avatarUrl = computed(() => auth.user()?.avatar || '')
const initial = computed(() => {
  const name = auth.user()?.nickname || auth.user()?.username || ''
  return name.trim().charAt(0).toUpperCase() || '?'
})

const fileInput = ref(null)
const avatarBusy = ref(false)
/** 存描述符不是句子，见 i18n/index.js 的 message()：存句子的话切语言不会跟着变 */
const avatarError = ref(null)

const avatarErrorText = computed(() => resolveMessage(avatarError.value))

function pickAvatar() {
  fileInput.value?.click()
}

async function onAvatarPicked(event) {
  const file = event.target.files?.[0]
  // 选完立刻把 input 清空：不清的话，用户选同一个文件第二次不会触发 change，
  // 看起来就像「点了没反应」
  event.target.value = ''
  if (!file) return

  avatarBusy.value = true
  avatarError.value = null
  try {
    // 成功后 api 层已经把新的用户信息写回本地，头像会自动刷新
    await uploadAvatar(file)
  } catch (err) {
    avatarError.value = err?.message ? rawMessage(err.message) : message('settings.avatar.failed')
  } finally {
    avatarBusy.value = false
  }
}

/* ------------------------------------------------------------------ 画像 */

const form = reactive({
  age: '',
  heightCm: '',
  weightKg: '',
  goal: '',
  activityLevel: '',
  dietPreference: '',
  avoidFood: '',
})

/** 可选项由后端给（跟着活动水平枚举走），前端不写死一份 */
const activityOptions = ref([])
const bmi = ref(null)
const loading = ref(true)
const saving = ref(false)
const loadError = ref(null)
const saveError = ref(null)
const saved = ref(false)

const loadErrorText = computed(() => resolveMessage(loadError.value))
const saveErrorText = computed(() => resolveMessage(saveError.value))

function fillFrom(profile) {
  form.age = profile.age ?? ''
  form.heightCm = profile.heightCm ?? ''
  form.weightKg = profile.weightKg ?? ''
  form.goal = profile.goal ?? ''
  form.activityLevel = profile.activityLevel ?? ''
  form.dietPreference = profile.dietPreference ?? ''
  form.avoidFood = profile.avoidFood ?? ''
  activityOptions.value = profile.activityLevelOptions ?? []
  bmi.value = profile.bmi ?? null
}

/**
 * 空串转成 null。
 *
 * 数字输入框空着时 `v-model` 给的是空串，直接发给后端会被 Jackson 判成
 * 解析失败（400，而且错误信息是用户看不懂的英文）。转成 null 才是
 * 「这一项没填」的意思。
 */
function toNumber(value) {
  if (value === '' || value === null || value === undefined) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

onMounted(async () => {
  if (!auth.isLoggedIn()) {
    loading.value = false
    return
  }
  try {
    fillFrom(await fetchProfile())
  } catch (err) {
    loadError.value = err?.message ? rawMessage(err.message) : message('settings.profile.loadFailed')
  } finally {
    loading.value = false
  }
})

/**
 * 保存。
 *
 * **七个字段一起发**，不是只发改动过的：后端的语义是整体替换，
 * 只发改动过的那些会把其余字段当成「清空」。这也正是用户能删掉
 * 某个字段的原因——表单上是什么，库里就是什么。
 */
async function save() {
  saving.value = true
  saveError.value = null
  saved.value = false
  try {
    const updated = await updateProfile({
      age: toNumber(form.age),
      heightCm: toNumber(form.heightCm),
      weightKg: toNumber(form.weightKg),
      goal: form.goal,
      activityLevel: form.activityLevel,
      dietPreference: form.dietPreference,
      avoidFood: form.avoidFood,
    })
    // 用后端返回的那份回填，而不是拿本地表单：后端可能会把空白折成 null，
    // 也可能会因为体重变化追加一条流水。以后端为准，界面才不会和库里不一致
    fillFrom(updated)
    saved.value = true
  } catch (err) {
    saveError.value = err?.message ? rawMessage(err.message) : message('settings.profile.saveFailed')
  } finally {
    saving.value = false
  }
}

/* ------------------------------------------------------------------ 开合 */

function onKeydown(event) {
  if (event.key === 'Escape') closeSettings()
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="drawer-root" :class="{ dark: isChatDark }">
    <!-- 遮罩：点它就关。和窄屏侧边栏那个 .scrim 是同一个套路 -->
    <div class="scrim" @click="closeSettings"></div>

    <aside class="drawer" role="dialog" aria-modal="true" :aria-label="$t('settings.title')">
      <header class="head">
        <h2>{{ $t('settings.title') }}</h2>
        <button class="icon-btn" type="button" :title="$t('common.close')" @click="closeSettings">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </header>

      <div class="body">
        <!-- ============================ 头像 ============================ -->
        <section class="block">
          <h3>{{ $t('settings.avatar.title') }}</h3>
          <div class="avatar-row">
            <span class="avatar">
              <img v-if="avatarUrl" :src="avatarUrl" :alt="initial" />
              <template v-else>{{ initial }}</template>
            </span>
            <div class="avatar-actions">
              <button class="btn" type="button" :disabled="avatarBusy" @click="pickAvatar">
                {{ avatarBusy ? $t('settings.avatar.uploading') : $t('settings.avatar.upload') }}
              </button>
              <p class="note">{{ $t('settings.avatar.note') }}</p>
            </div>
            <!-- 真正的 file input 藏起来：原生的那个按钮样式改不动，
                 而且各浏览器长得都不一样。label 包一层的话又要处理键盘焦点 -->
            <input
              ref="fileInput"
              class="file-input"
              type="file"
              accept="image/png,image/jpeg,image/webp,image/gif"
              @change="onAvatarPicked"
            />
          </div>
          <p v-if="avatarErrorText" class="error">{{ avatarErrorText }}</p>
        </section>

        <!-- ============================ 外观 ============================ -->
        <section class="block">
          <h3>{{ $t('settings.appearance.title') }}</h3>
          <!-- 选中态看的是 theme（用户的偏好），不是 isChatDark（当前页面实际渲染成什么样）：
               在首页上把偏好切成深色时，首页仍然是浅色的，但这两个按钮必须正确反映
               「你选的是深色」——否则用户会以为没点中，反复点 -->
          <div class="segment">
            <button
              class="seg"
              type="button"
              :class="{ on: !darkSelected }"
              @click="setTheme('light')"
            >
              {{ $t('settings.appearance.light') }}
            </button>
            <button
              class="seg"
              type="button"
              :class="{ on: darkSelected }"
              @click="setTheme('dark')"
            >
              {{ $t('settings.appearance.dark') }}
            </button>
          </div>
          <p class="note">
            {{ $t('settings.appearance.note') }}
          </p>
        </section>

        <!-- ============================ 语言 ============================ -->
        <section class="block">
          <h3>{{ $t('settings.language.title') }}</h3>
          <!-- 语言是**全局**的，和主题不一样：没有「首页中文、对话页英文」这种状态，
               所以在哪一页切都立刻全站生效。选项标签用它自己的语言写，见 i18n/index.js -->
          <div class="segment">
            <button
              v-for="option in LOCALE_OPTIONS"
              :key="option.value"
              class="seg"
              type="button"
              :class="{ on: localeRef === option.value }"
              @click="setLocale(option.value)"
            >
              {{ option.label }}
            </button>
          </div>
          <p class="note">{{ $t('settings.language.note') }}</p>
        </section>

        <!-- ========================== 用户画像 ========================== -->
        <section class="block">
          <h3>{{ $t('settings.profile.title') }}</h3>

          <p v-if="!auth.isLoggedIn()" class="note">{{ $t('settings.profile.needLogin') }}</p>
          <p v-else-if="loading" class="note">{{ $t('common.loading') }}</p>
          <p v-else-if="loadErrorText" class="error">{{ loadErrorText }}</p>

          <template v-else>
            <p class="note">
              {{ $t('settings.profile.intro') }}
            </p>

            <div class="grid">
              <label class="field">
                <span class="label">{{ $t('settings.profile.age') }}</span>
                <input v-model="form.age" type="number" min="1" max="150" :placeholder="$t('settings.profile.agePlaceholder')" />
              </label>
              <label class="field">
                <span class="label">{{ $t('settings.profile.height') }}</span>
                <input v-model="form.heightCm" type="number" min="50" max="250" step="0.5" :placeholder="$t('settings.profile.heightPlaceholder')" />
              </label>
              <label class="field">
                <span class="label">{{ $t('settings.profile.weight') }}</span>
                <input v-model="form.weightKg" type="number" min="1" max="500" step="0.1" :placeholder="$t('settings.profile.weightPlaceholder')" />
              </label>
              <label class="field">
                <span class="label">{{ $t('settings.profile.bmi') }}</span>
                <!-- 只读，由后端算。它会在明显不合理的数据上返回空，
                     那时显示「—」比显示一个错数好 -->
                <input :value="bmi ?? '—'" type="text" readonly tabindex="-1" />
              </label>
            </div>

            <label class="field full">
              <span class="label">{{ $t('settings.profile.goal') }}</span>
              <input v-model="form.goal" type="text" :placeholder="$t('settings.profile.goalPlaceholder')" />
            </label>

            <label class="field full">
              <span class="label">{{ $t('settings.profile.activity') }}</span>
              <!-- 选项由后端返回（跟着枚举走），不在这儿写死一份。
                   **label / hint 也不翻译**：后端拿 label 当匹配依据（ActivityLevel.parse），
                   翻了用户画像就回写不进库了。见后端的 messages.properties 说明 -->
              <select v-model="form.activityLevel">
                <option value="">{{ $t('settings.profile.activityEmpty') }}</option>
                <option v-for="option in activityOptions" :key="option.value" :value="option.value">
                  {{ option.label }} · {{ option.hint }}
                </option>
              </select>
            </label>

            <label class="field full">
              <span class="label">{{ $t('settings.profile.diet') }}</span>
              <input v-model="form.dietPreference" type="text" :placeholder="$t('settings.profile.dietPlaceholder')" />
            </label>

            <label class="field full">
              <span class="label">{{ $t('settings.profile.avoid') }}</span>
              <input v-model="form.avoidFood" type="text" :placeholder="$t('settings.profile.avoidPlaceholder')" />
            </label>

            <p class="note">{{ $t('settings.profile.clearNote') }}</p>

            <div class="actions">
              <button class="btn primary" type="button" :disabled="saving" @click="save">
                {{ saving ? $t('common.saving') : $t('common.save') }}
              </button>
              <span v-if="saved" class="ok">{{ $t('common.saved') }}</span>
            </div>
            <p v-if="saveErrorText" class="error">{{ saveErrorText }}</p>
          </template>
        </section>
      </div>
    </aside>
  </div>
</template>

<style scoped>
/*
 * 自带一套颜色变量，而不是共用 ChatRoom 的 --c-*：那些定义在 `.shell` 上，
 * 而这个抽屉挂在 App.vue 上——首页打开它时根本不是 `.shell` 的后代，拿不到。
 * 两套变量各管各的，代价是颜色值重复了一遍，换来的是抽屉在哪儿都能正确渲染。
 */
.drawer-root {
  --d-bg: #fff;
  --d-head: #fbfbfc;
  --d-line: var(--line);
  --d-text: var(--ink);
  --d-muted: var(--muted);
  --d-input-bg: #fff;
  --d-input-line: var(--line-strong);
  --d-hover: #f3f5fa;
  --d-danger: var(--danger);
}

.drawer-root.dark {
  --d-bg: #1e2026;
  --d-head: #14161b;
  --d-line: #2e323a;
  --d-text: #e6e8ee;
  --d-muted: #8b93a3;
  --d-input-bg: #262a31;
  --d-input-line: #33373f;
  --d-hover: #262a31;
  --d-danger: #f08a86;
}

.scrim {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgba(16, 20, 30, 0.32);
}

.drawer-root.dark .scrim {
  background: rgba(0, 0, 0, 0.55);
}

.drawer {
  position: fixed;
  top: 0;
  bottom: 0;
  /* 从左边出来：入口在左下角的用户菜单里，从那一侧展开最顺 */
  left: 0;
  z-index: 61;
  display: flex;
  flex-direction: column;
  width: min(380px, 92vw);
  border-right: 1px solid var(--d-line);
  background: var(--d-bg);
  color: var(--d-text);
  box-shadow: 0 0 40px rgba(16, 20, 30, 0.18);
  /* v-if 挂载出来的，没有退出过渡，所以只用入场动画 */
  animation: drawer-in 0.22s cubic-bezier(0.16, 0.84, 0.44, 1);
}

@keyframes drawer-in {
  from {
    transform: translateX(-100%);
  }
}

.head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
  padding: 14px 14px 12px 18px;
  border-bottom: 1px solid var(--d-line);
  background: var(--d-head);
}

.head h2 {
  flex: 1;
  font-size: 15px;
  font-weight: 600;
}

.icon-btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 9px;
  background: none;
  color: var(--d-muted);
}
.icon-btn:hover {
  background: var(--d-hover);
  color: var(--d-text);
}
.icon-btn svg {
  width: 16px;
  height: 16px;
}

.body {
  flex: 1;
  overflow-y: auto;
  padding: 4px 18px 28px;
}

.block {
  padding: 18px 0;
  border-bottom: 1px solid var(--d-line);
}
.block:last-child {
  border-bottom: none;
}

.block h3 {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 600;
  color: var(--d-text);
}

/* ---------------------------------------------------------------- 头像 */

.avatar-row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--brand);
  color: #fff;
  font-size: 22px;
  font-weight: 600;
  overflow: hidden;
}

.avatar img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-actions {
  flex: 1;
  min-width: 0;
}

/* 原生的 file input 各浏览器长得都不一样，而且改不动样式，
   所以藏起来、用按钮去触发它 */
.file-input {
  display: none;
}

/* ------------------------------------------------------------ 分段控件 */

.segment {
  display: flex;
  gap: 6px;
}

.seg {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid var(--d-input-line);
  border-radius: 9px;
  background: var(--d-input-bg);
  color: var(--d-text);
  font-size: 13px;
  transition: border-color 0.16s ease, color 0.16s ease;
}
.seg:hover {
  border-color: var(--brand);
  color: var(--brand);
}
.seg.on {
  border-color: var(--brand);
  background: var(--brand-soft);
  color: var(--brand);
  font-weight: 500;
}

/* ---------------------------------------------------------------- 表单 */

.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.field {
  display: block;
  margin-top: 12px;
}
.grid .field {
  margin-top: 0;
}

.label {
  display: block;
  margin-bottom: 5px;
  color: var(--d-muted);
  font-size: 12.5px;
}

.field input,
.field select {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--d-input-line);
  border-radius: 9px;
  background: var(--d-input-bg);
  color: var(--d-text);
  font: inherit;
  font-size: 13.5px;
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.field input:focus,
.field select:focus {
  outline: none;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-soft);
}

.field input[readonly] {
  color: var(--d-muted);
  cursor: default;
}

/* 关掉数字输入框的上下箭头：它们很窄，容易误点，而这两项都是偶尔填一次 */
.field input[type='number']::-webkit-outer-spin-button,
.field input[type='number']::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}
.field input[type='number'] {
  -moz-appearance: textfield;
  appearance: textfield;
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 18px;
}

.btn {
  padding: 9px 16px;
  border: 1px solid var(--d-input-line);
  border-radius: 9px;
  background: var(--d-input-bg);
  color: var(--d-text);
  font-size: 13.5px;
  transition: border-color 0.16s ease, color 0.16s ease, opacity 0.16s ease;
}
.btn:hover:not(:disabled) {
  border-color: var(--brand);
  color: var(--brand);
}
.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.btn.primary {
  border-color: transparent;
  background: var(--brand);
  color: #fff;
}
.btn.primary:hover:not(:disabled) {
  background: var(--brand-deep);
  color: #fff;
}

.note {
  margin-top: 10px;
  color: var(--d-muted);
  font-size: 12px;
  line-height: 1.6;
}
.avatar-actions .note {
  margin-top: 6px;
}

.ok {
  color: var(--brand);
  font-size: 13px;
}

.error {
  margin-top: 10px;
  color: var(--d-danger);
  font-size: 12.5px;
  line-height: 1.6;
}

@media (max-width: 480px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
