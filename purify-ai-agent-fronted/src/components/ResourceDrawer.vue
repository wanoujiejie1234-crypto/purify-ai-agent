<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchResources, deleteResource, downloadResourceFile } from '../api/http.js'
import { isChatDark } from '../theme.js'
import { closeResources } from '../panels.js'
import { message, rawMessage, resolveMessage, t } from '../i18n/index.js'

/**
 * 资料库：Manus 跑任务时产出的文件（生成的 PDF、下载的资源、写出来的文件）都归档在这里。
 *
 * <p>记录是工具在**产出的那一刻**写进去的（见后端的 ResourceRecorder），不是事后扫回答
 * 抓链接——所以 PDF、下载、纯写文件三种产出都在，而且带着文件名和大小。
 *
 * <p>配色和 `SettingsDrawer` 同一套逻辑：跟 `isChatDark`（当前页面实际是不是深色），
 * 不跟 `theme`（用户偏好）。理由见 theme.js。
 */

const items = ref([])
const loading = ref(true)
/** 存描述符不是句子，见 i18n/index.js 的 message()：存句子的话切语言不会跟着变 */
const loadError = ref(null)
const busyId = ref(null)

const loadErrorText = computed(() => resolveMessage(loadError.value))

async function load() {
  loading.value = true
  loadError.value = null
  try {
    items.value = await fetchResources()
  } catch (err) {
    loadError.value = err?.message
      ? rawMessage(err.message)
      : message('resources.loadFailed')
  } finally {
    loading.value = false
  }
}

/**
 * 下载。
 *
 * 分两条路，取决于后端有没有给「能直接打开的地址」：
 * - 有（生成的 PDF、下载回来的资源）：那个地址本来就是公开的，用普通链接打开即可；
 * - 没有（writeFile 写出来的文件，落在服务端一个没有对外映射的目录里）：
 *   只能走带鉴权的接口，所以要在这里用 axios 取成 blob 再保存。
 *
 * 为什么后一种不用 `<a href="/api/...">`：那是一次浏览器导航，不带 Authorization 头，
 * 而接口要求登录，点下去只会得到 401。细节见 api/http.js 里那个函数。
 */
async function download(item) {
  if (item.url) {
    window.open(item.url, '_blank', 'noopener')
    return
  }
  busyId.value = item.id
  try {
    await downloadResourceFile(item.id, item.title)
  } catch (err) {
    loadError.value = err?.message
      ? rawMessage(err.message)
      : message('resources.downloadFailed')
  } finally {
    busyId.value = null
  }
}

async function remove(item) {
  if (!window.confirm(t('resources.deleteConfirm', { title: item.title }))) return
  busyId.value = item.id
  try {
    await deleteResource(item.id)
    items.value = items.value.filter((entry) => entry.id !== item.id)
  } catch (err) {
    loadError.value = err?.message
      ? rawMessage(err.message)
      : message('resources.deleteFailed')
  } finally {
    busyId.value = null
  }
}

/** 148KB / 1.2MB 这种。拿不到大小时返回「—」，不要显示 0——那看起来像文件是坏的。 */
function formatSize(bytes) {
  if (bytes === null || bytes === undefined) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 后端给的是 ISO 字符串，只要到分钟。 */
function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function onKeydown(event) {
  if (event.key === 'Escape') closeResources()
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
  load()
})
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="drawer-root" :class="{ dark: isChatDark }">
    <div class="scrim" @click="closeResources"></div>

    <aside class="drawer" role="dialog" aria-modal="true" :aria-label="$t('resources.title')">
      <header class="head">
        <h2>{{ $t('resources.title') }}</h2>
        <button class="icon-btn" type="button" :title="$t('common.refresh')" :disabled="loading" @click="load">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M20 11a8 8 0 1 0-2.3 5.7M20 5v6h-6" />
          </svg>
        </button>
        <button class="icon-btn" type="button" :title="$t('common.close')" @click="closeResources">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </header>

      <div class="body">
        <p v-if="loadErrorText" class="error">{{ loadErrorText }}</p>
        <p v-if="loading" class="empty">{{ $t('common.loading') }}</p>

        <p v-else-if="!items.length" class="empty">
          {{ $t('resources.empty') }}<br />
          {{ $t('resources.emptyHint') }}
        </p>

        <ul v-else class="list">
          <li v-for="item in items" :key="item.id" class="row">
            <div class="info">
              <p class="title" :title="item.title">{{ item.title }}</p>
              <p class="meta">
                <!-- 种类标签由后端给（kindLabel），而且由后端翻译。
                     不在前端维护一份「枚举名 → 文案」的映射：那种映射迟早会和后端的枚举对不上，
                     而漏一项的表现是界面上直接露出 WRITTEN 这种内部名。
                     代价是它跟着语言走的时机是「下一次拉列表」，不是切换的那一刻 -->
                <span class="kind">{{ item.kindLabel || item.kind }}</span>
                <span>{{ formatSize(item.sizeBytes) }}</span>
                <span>{{ formatTime(item.createdAt) }}</span>
              </p>
              <!-- 下载回来的资源记一下来源：回头看时「这是从哪抓的」往往比文件本身有用 -->
              <a
                v-if="item.sourceUrl"
                class="source"
                :href="item.sourceUrl"
                target="_blank"
                rel="noopener noreferrer"
                :title="item.sourceUrl"
              >
                {{ $t('resources.source', { url: item.sourceUrl }) }}
              </a>
            </div>

            <div class="ops">
              <button
                class="mini"
                type="button"
                :disabled="busyId === item.id"
                :title="$t('common.download')"
                @click="download(item)"
              >
                {{ $t('common.download') }}
              </button>
              <button
                class="mini danger"
                type="button"
                :disabled="busyId === item.id"
                :title="$t('common.delete')"
                @click="remove(item)"
              >
                {{ $t('common.delete') }}
              </button>
            </div>
          </li>
        </ul>
      </div>
    </aside>
  </div>
</template>

<style scoped>
/* 和 SettingsDrawer 一套：自带颜色变量，因为抽屉挂在 App.vue 上，
   够不到 ChatRoom 里定义在 .shell 上的那些 token */
.drawer-root {
  --d-bg: #fff;
  --d-head: #fbfbfc;
  --d-line: var(--line);
  --d-text: var(--ink);
  --d-muted: var(--muted);
  --d-hover: #f3f5fa;
}

.drawer-root.dark {
  --d-bg: #1e2026;
  --d-head: #14161b;
  --d-line: #2e323a;
  --d-text: #e6e8ee;
  --d-muted: #8b93a3;
  --d-hover: #262a31;
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
  left: 0;
  z-index: 61;
  display: flex;
  flex-direction: column;
  width: min(440px, 92vw);
  border-right: 1px solid var(--d-line);
  background: var(--d-bg);
  color: var(--d-text);
  box-shadow: 0 0 40px rgba(16, 20, 30, 0.18);
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
  gap: 6px;
  flex: none;
  padding: 14px 12px 12px 18px;
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
.icon-btn:hover:not(:disabled) {
  background: var(--d-hover);
  color: var(--d-text);
}
.icon-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.icon-btn svg {
  width: 16px;
  height: 16px;
}

.body {
  flex: 1;
  overflow-y: auto;
  padding: 8px 14px 24px;
}

.list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 13px 4px;
  border-bottom: 1px solid var(--d-line);
}
.row:last-child {
  border-bottom: none;
}

.info {
  flex: 1;
  min-width: 0;
}

.title {
  /* 文件名可能很长，一行放不下就省略，别把右边的按钮挤走 */
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  margin-top: 5px;
  color: var(--d-muted);
  font-size: 11.5px;
}

.kind {
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--d-hover);
}

.source {
  display: block;
  margin-top: 5px;
  overflow: hidden;
  color: var(--d-muted);
  font-size: 11.5px;
  text-decoration: none;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.source:hover {
  text-decoration: underline;
}

.ops {
  display: flex;
  flex: none;
  gap: 6px;
}

.mini {
  padding: 5px 10px;
  border: 1px solid var(--d-line);
  border-radius: 8px;
  background: transparent;
  color: var(--d-text);
  font-size: 12px;
  transition: border-color 0.16s ease, color 0.16s ease;
}
.mini:hover:not(:disabled) {
  border-color: var(--brand);
  color: var(--brand);
}
.mini:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.mini.danger:hover:not(:disabled) {
  border-color: var(--danger);
  color: var(--danger);
}

.empty {
  margin-top: 40px;
  color: var(--d-muted);
  font-size: 13px;
  line-height: 1.8;
  text-align: center;
}

.error {
  margin: 10px 4px;
  color: var(--danger);
  font-size: 12.5px;
  line-height: 1.6;
}
</style>
