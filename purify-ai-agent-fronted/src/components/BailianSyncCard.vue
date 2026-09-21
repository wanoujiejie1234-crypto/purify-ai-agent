<script setup>
import { ref, computed, onMounted } from 'vue'
import {
  fetchBailianStatus,
  fetchBailianDocuments,
  fetchBailianChunks,
  syncBailianDocuments,
} from '../api/http.js'
import { isEnglish, message, rawMessage, resolveMessage, t } from '../i18n/index.js'

/**
 * 「百炼同步」卡片 —— 把百炼云知识库**已经切好的**切片搬进本地 pgvector。
 *
 * 为什么要有它：百炼控制台的切片效果比本地 TokenTextSplitter 好，而效果全在「怎么切」上。
 * 所以这条路上不再切一次，切片原样搬过来，只有向量化在本地做。
 *
 * 拆成独立组件而不是塞进 KnowledgeView.vue：那一页已经八百多行，而且
 * ResourceDrawer.vue 已经立了「组件自己加载自己的数据」的先例。
 *
 * 两处刻意的克制，都是为了不把百炼那个限流 10 QPS 的接口打爆：
 *   1. **一次只展开一份文档的切片**，展开新的就收起旧的；
 *   2. 列表页**不显示「百炼侧有多少片」**——那个数要逐份调切片接口才拿得到，
 *      一页十行就是十次远程请求，只为在表格里显示一个数字。
 */

const emit = defineEmits(['synced'])

const PAGE_SIZE = 10

/* ------------------------------------------------------------------ 配置 */

const status = ref(null)
const statusError = ref(null)

const statusErrorText = computed(() => resolveMessage(statusError.value))

/**
 * 可选分类来自后端的 status 接口，**不在前端硬编码**。
 * 这些值必须和 purify.rag.router.categories 一字不差——写错了检索查不到任何东西
 * 而且不报任何错，所以能少一处硬编码就少一处。
 */
const categories = computed(() => status.value?.categories || [])

async function loadStatus() {
  try {
    status.value = await fetchBailianStatus()
    statusError.value = null
  } catch (err) {
    statusError.value = err.message ? rawMessage(err.message) : message('knowledge.bailian.loadFailed')
  }
}

/* ------------------------------------------------------------------ 清单 */

const docs = ref([])
const page = ref(1)
const total = ref(0)
const statusFilter = ref('FINISH')
const loadingDocs = ref(false)
const docsError = ref(null)

const docsErrorText = computed(() => resolveMessage(docsError.value))

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

const selectedCount = computed(() => docs.value.filter((d) => d.selected).length)

async function loadDocuments() {
  loadingDocs.value = true
  try {
    const data = await fetchBailianDocuments(page.value, PAGE_SIZE, statusFilter.value)
    // 「未同步」和「百炼侧已更新」默认勾上 —— 这两个状态点了都是有意义的；
    // 「已同步」勾了纯属白花钱，「同名冲突」点了会覆盖本地，都得用户自己决定
    docs.value = (data?.items || []).map((item) => ({
      ...item,
      selected: item.syncState === 'MISSING' || item.syncState === 'STALE',
    }))
    total.value = data?.total || 0
    docsError.value = null
  } catch (err) {
    docsError.value = err.message ? rawMessage(err.message) : message('knowledge.bailian.loadFailed')
  } finally {
    loadingDocs.value = false
  }
}

function goto(next) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
  collapse()
  loadDocuments()
}

function changeFilter() {
  page.value = 1
  collapse()
  loadDocuments()
}

function toggleAll() {
  const allSelected = docs.value.every((d) => d.selected || d.syncState === 'SYNCED')
  docs.value.forEach((d) => {
    // 「已同步」不参与全选：它同步过来只是重复劳动，而百炼那边限流 10 QPS，
    // 白白多拉几份没意义的文档是有代价的
    if (d.syncState !== 'SYNCED') d.selected = !allSelected
  })
}

async function reload() {
  collapse()
  await Promise.all([loadStatus(), loadDocuments()])
}

function stateLabel(doc) {
  if (doc.syncState === 'SYNCED') return t('knowledge.bailian.state.synced', { chunks: doc.localChunks })
  if (doc.syncState === 'STALE') return t('knowledge.bailian.state.stale')
  if (doc.syncState === 'NAME_CONFLICT') return t('knowledge.bailian.state.conflict')
  return t('knowledge.bailian.state.missing')
}

function formatSize(bytes) {
  if (bytes == null) return '—'
  return `${(bytes / 1024).toFixed(1)} KB`
}

function formatTime(millis) {
  if (!millis) return '—'
  return new Date(millis).toLocaleString()
}

/* -------------------------------------------------------------- 切片预览 */

/** 当前展开的那一份。null 表示没展开——一次只展开一份，理由见文件头。 */
const expandedId = ref(null)
const chunks = ref(null)
const chunkPage = ref(1)
const chunksError = ref(null)
const loadingChunks = ref(false)

const chunksErrorText = computed(() => resolveMessage(chunksError.value))

const chunkTotalPages = computed(() =>
  Math.max(1, Math.ceil((chunks.value?.total || 0) / (chunks.value?.pageSize || 20))),
)

/**
 * 每一份文档选定的分类。
 *
 * 百炼的元数据里标了分类就用它，没标（或标了多个）就得由人来选——
 * 不猜。分类值错了会让这些切片在带分类过滤的提问下永远检索不到，而且不报任何错。
 */
const chosen = ref({})

const expandedDoc = computed(() => docs.value.find((d) => d.fileId === expandedId.value) || null)

const chunkHeading = computed(() => {
  const data = chunks.value
  if (!data) return null
  const from = (data.pageNum - 1) * data.pageSize + 1
  const to = from + data.chunks.length - 1
  return { name: expandedDoc.value?.name || '', count: data.total, from, to }
})

async function expand(doc) {
  if (expandedId.value === doc.fileId) {
    collapse()
    return
  }
  expandedId.value = doc.fileId
  chunkPage.value = 1
  chunks.value = null
  await loadChunks()
}

function collapse() {
  expandedId.value = null
  chunks.value = null
  chunksError.value = null
}

async function gotoChunk(next) {
  if (next < 1 || next > chunkTotalPages.value) return
  chunkPage.value = next
  await loadChunks()
}

async function loadChunks() {
  const fileId = expandedId.value
  if (!fileId) return
  loadingChunks.value = true
  try {
    const data = await fetchBailianChunks(fileId, chunkPage.value, 20)
    // 翻页期间用户可能已经展开了别的，这时这一份的结果就作废了
    if (expandedId.value !== fileId) return
    chunks.value = data
    chunksError.value = null
    // 百炼标清楚了就直接带出来当默认选择；没标清楚则留空，逼用户显式选一个
    if (data.classificationSource === 'METADATA' && data.chunks?.length) {
      chosen.value = { ...chosen.value, [fileId]: data.chunks[0].suggestedClassification }
    } else if (!chosen.value[fileId]) {
      chosen.value = { ...chosen.value, [fileId]: '' }
    }
  } catch (err) {
    if (expandedId.value !== fileId) return
    // 列表页已经拿到了「已经标好分类」的信息时不该在这里清掉它，所以只清 chunks
    chunks.value = null
    chunksError.value = err.message ? rawMessage(err.message) : message('knowledge.bailian.chunks.failed')
  } finally {
    loadingChunks.value = false
  }
}

/* ------------------------------------------------------------------ 同步 */

const syncing = ref(false)
const syncMessage = ref(null)
/** 失败项存**一组**描述符，理由同 KnowledgeView 的 uploadError：拼成一句存下来，切语言就冻住了 */
const syncErrors = ref([])

const syncMessageText = computed(() => resolveMessage(syncMessage.value))
const syncErrorText = computed(() => {
  const separator = isEnglish() ? '; ' : '；'
  return syncErrors.value.map(resolveMessage).join(separator)
})

function pickSelected() {
  const picked = docs.value.filter((d) => d.selected)
  if (!picked.length) {
    syncErrors.value = [message('knowledge.bailian.noSelection')]
    return null
  }
  // 分类必须先齐了再发请求。服务端的表现是「逐份失败」，用户会看到一堆失败项
  // 却不知道要去哪改；在这里拦下来就能把问题指到具体哪几行
  const missing = picked.filter((d) => !chosen.value[d.fileId])
  if (missing.length) {
    syncErrors.value = [message('knowledge.bailian.needCategory', {
      names: missing.map((d) => d.name).join(isEnglish() ? ', ' : '、'),
    })]
    return null
  }
  // 同名冲突会覆盖本地那份手传的切片，是这批操作里唯一有破坏性的，
  // 所以单独问一次；其余状态直接往下走
  const conflicts = picked.filter((d) => d.syncState === 'NAME_CONFLICT')
  if (conflicts.length) {
    const names = conflicts.map((d) => d.name).join(isEnglish() ? ', ' : '、')
    const confirmKey = conflicts.length === 1
      ? 'knowledge.bailian.conflictConfirm'
      : 'knowledge.bailian.conflictConfirmBatch'
    if (!window.confirm(t(confirmKey, { name: names, names }))) return null
  }
  return picked
}

async function sync(items) {
  syncing.value = true
  syncMessage.value = null
  syncErrors.value = []
  try {
    const result = await syncBailianDocuments(items)
    syncMessage.value = message('knowledge.bailian.result.batch', {
      ok: result.succeeded,
      failed: result.failed,
    })
    const failures = (result.items || []).filter((i) => !i.ok)
    syncErrors.value = failures.map((i) =>
      message('knowledge.bailian.result.failureItem', { name: i.filename, message: i.message }),
    )
    emit('synced')
    await loadDocuments()
  } catch (err) {
    syncErrors.value = [err.message ? rawMessage(err.message) : message('knowledge.bailian.result.failed')]
  } finally {
    syncing.value = false
  }
}

async function syncSelected() {
  const picked = pickSelected()
  if (!picked) return
  await sync(picked.map(toItem))
}

async function syncOne() {
  const doc = expandedDoc.value
  if (!doc) return
  if (!chosen.value[doc.fileId]) {
    syncErrors.value = [message('knowledge.bailian.needCategory', { names: doc.name })]
    return
  }
  if (doc.syncState === 'NAME_CONFLICT') {
    if (!window.confirm(t('knowledge.bailian.conflictConfirm', { name: doc.name }))) return
  }
  await sync([toItem(doc)])
}

/**
 * 转成请求体。
 *
 * `gmtModified` 必须带上：服务端会把它存进切片元数据，日后拿它和百炼当前值比对，
 * 判断「那边改过没有」。不带的话这份文档会一直显示「百炼侧已更新」——本地没有基准可比，
 * 顶多多同步一次，不会出错，但没必要让它退化。
 */
function toItem(doc) {
  return {
    fileId: doc.fileId,
    name: doc.name,
    classification: chosen.value[doc.fileId],
    gmtModified: doc.gmtModified,
  }
}

onMounted(reload)
</script>

<template>
  <section class="card">
    <div class="card-head">
      <h2>{{ $t('knowledge.bailian.title') }}</h2>
      <button class="ghost" type="button" @click="reload">{{ $t('knowledge.bailian.reload') }}</button>
    </div>
    <p class="desc">{{ $t('knowledge.bailian.desc') }}</p>

    <p v-if="statusErrorText" class="err">{{ statusErrorText }}</p>

    <!-- 没配好时**不把卡片藏掉**：藏掉的话超管只会来问「功能呢」。
         这里要点名缺的是哪几个键，让他知道去哪儿补 -->
    <p v-else-if="status && !status.configured" class="warn">
      {{ $t('knowledge.bailian.notConfigured', { keys: status.missing.join('、') }) }}
    </p>

    <template v-else-if="status">
      <p class="desc connected">
        {{ $t('knowledge.bailian.connected', { name: status.indexName, indexId: status.indexId }) }}
      </p>

      <div class="toolbar">
        <label class="field">
          {{ $t('knowledge.bailian.filter') }}
          <select v-model="statusFilter" @change="changeFilter">
            <option value="FINISH">{{ $t('knowledge.bailian.filterFinish') }}</option>
            <option value="ALL">{{ $t('knowledge.bailian.filterAll') }}</option>
          </select>
        </label>
        <button class="btn subtle" type="button" @click="toggleAll">
          {{ $t('knowledge.bailian.selectAll') }}
        </button>
        <button
          class="btn primary"
          type="button"
          :disabled="syncing || !selectedCount"
          @click="syncSelected"
        >
          {{
            syncing
              ? $t('knowledge.bailian.syncing')
              : $t('knowledge.bailian.syncSelected', { n: selectedCount })
          }}
        </button>
      </div>

      <p v-if="docsErrorText" class="err">{{ docsErrorText }}</p>
      <p v-else-if="loadingDocs" class="desc">{{ $t('knowledge.bailian.loading') }}</p>
      <p v-else-if="!docs.length" class="desc">{{ $t('knowledge.bailian.empty') }}</p>

      <table v-else class="docs">
        <thead>
          <tr>
            <th class="pick-col"></th>
            <th>{{ $t('knowledge.bailian.columns.name') }}</th>
            <th>{{ $t('knowledge.bailian.columns.status') }}</th>
            <th class="num-col">{{ $t('knowledge.bailian.columns.size') }}</th>
            <th>{{ $t('knowledge.bailian.columns.gmtModified') }}</th>
            <th>{{ $t('knowledge.bailian.columns.local') }}</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in docs" :key="d.fileId" :class="{ conflict: d.syncState === 'NAME_CONFLICT' }">
            <td class="pick-col">
              <input v-model="d.selected" type="checkbox" />
            </td>
            <td class="source">{{ d.name }}</td>
            <td><span class="tag">{{ d.status }}</span></td>
            <td class="num-col">{{ formatSize(d.size) }}</td>
            <td class="time">{{ formatTime(d.gmtModified) }}</td>
            <td class="state" :class="d.syncState.toLowerCase()">{{ stateLabel(d) }}</td>
            <td>
              <button type="button" class="del" @click="expand(d)">
                {{ expandedId === d.fileId ? $t('knowledge.bailian.collapse') : $t('knowledge.bailian.expand') }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="totalPages > 1" class="pager">
        <button type="button" :disabled="page <= 1" @click="goto(page - 1)">
          {{ $t('knowledge.bailian.prev') }}
        </button>
        <span>{{ page }} / {{ totalPages }}</span>
        <button type="button" :disabled="page >= totalPages" @click="goto(page + 1)">
          {{ $t('knowledge.bailian.next') }}
        </button>
      </div>

      <!-- ===================== 切片预览 ===================== -->
      <div v-if="expandedDoc" class="preview">
        <p v-if="loadingChunks" class="desc">{{ $t('knowledge.bailian.chunks.loading') }}</p>
        <p v-else-if="chunksErrorText" class="err">{{ chunksErrorText }}</p>

        <template v-else-if="chunks">
          <h3 v-if="chunkHeading">
            {{
              $t('knowledge.bailian.chunks.heading', {
                name: chunkHeading.name,
                count: chunkHeading.count,
                from: chunkHeading.from,
                to: chunkHeading.to,
              })
            }}
          </h3>

          <!-- 分类的三种来源分开说清楚：百炼标的、百炼标了多个、百炼没标。
               后两种必须由人选一个，而且要说清「这是你选的」，别让人以为是百炼标的 -->
          <p v-if="chunks.classificationSource === 'METADATA'" class="ok">
            {{ $t('knowledge.bailian.chunks.fromMetadata', { category: chosen[expandedDoc.fileId] }) }}
          </p>
          <div v-else class="pick">
            <p class="warn">
              {{
                chunks.classificationSource === 'MIXED'
                  ? $t('knowledge.bailian.chunks.mixed')
                  : $t('knowledge.bailian.chunks.needPick')
              }}
            </p>
            <label class="field">
              {{ $t('knowledge.bailian.chunks.category') }}
              <select v-model="chosen[expandedDoc.fileId]">
                <option value=""></option>
                <option v-for="c in categories" :key="c" :value="c">{{ c }}</option>
              </select>
            </label>
          </div>

          <p v-if="!chunks.chunks?.length" class="warn">
            {{ $t('knowledge.bailian.chunks.empty', { status: expandedDoc.status }) }}
          </p>

          <ul v-else class="chunks">
            <li v-for="c in chunks.chunks" :key="c.index">
              <span class="idx">#{{ c.index }}</span>
              <span class="len">{{ $t('knowledge.bailian.chunks.chars', { n: c.length }) }}</span>
              <span class="excerpt">{{ c.text }}</span>
            </li>
          </ul>

          <div class="preview-foot">
            <div v-if="chunkTotalPages > 1" class="pager">
              <button type="button" :disabled="chunkPage <= 1" @click="gotoChunk(chunkPage - 1)">
                {{ $t('knowledge.bailian.prev') }}
              </button>
              <span>{{ chunkPage }} / {{ chunkTotalPages }}</span>
              <button
                type="button"
                :disabled="chunkPage >= chunkTotalPages"
                @click="gotoChunk(chunkPage + 1)"
              >
                {{ $t('knowledge.bailian.next') }}
              </button>
            </div>
            <button class="btn primary" type="button" :disabled="syncing" @click="syncOne">
              {{ $t('knowledge.bailian.syncOne') }}
            </button>
          </div>
        </template>
      </div>

      <p v-if="syncMessageText" class="ok">{{ syncMessageText }}</p>
      <p v-if="syncErrorText" class="err">{{ syncErrorText }}</p>
      <p v-if="syncing" class="warn">{{ $t('knowledge.bailian.timeoutHint') }}</p>
    </template>
  </section>
</template>

<style scoped>
/* 这些样式和 KnowledgeView.vue 里那份是同一套视觉语言，但**不能复用**：
   那边是 <style scoped>，作用域只在它自己的模板上，子组件里用不到。
   全局只有 base.css，而表格/分页这些东西本来就没进全局（见那边的注释）。 */

.card {
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #fff;
}
.card h2 {
  font-size: 14.5px;
  font-weight: 600;
  margin-bottom: 10px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.card-head h2 {
  margin-bottom: 0;
}

.ghost {
  padding: 5px 11px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  font-size: 12.5px;
  color: #46505f;
}
.ghost:hover {
  border-color: #10a37f;
  color: #10a37f;
}

.desc {
  color: #6b7381;
  font-size: 13px;
  line-height: 1.7;
}
.connected {
  margin-top: 6px;
  font-size: 12.5px;
  color: #8b94a3;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 12px 0;
  flex-wrap: wrap;
}

.field {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #5b6472;
  font-size: 13px;
}
.field select {
  padding: 6px 9px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  font: inherit;
  font-size: 13px;
  color: var(--ink);
}

.btn {
  padding: 7px 14px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #fff;
  color: #3d4653;
  font-size: 13px;
  cursor: pointer;
}
.btn:hover:not(:disabled) {
  border-color: #10a37f;
  color: #10a37f;
}
.btn.subtle {
  color: #6b7381;
}
.btn.primary {
  margin-left: auto;
  border-color: #10a37f;
  background: #10a37f;
  color: #fff;
}
.btn.primary:hover:not(:disabled) {
  background: #0d8f6f;
  border-color: #0d8f6f;
  color: #fff;
}
.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.docs {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.docs th,
.docs td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--line);
  text-align: left;
}
.docs th {
  color: #8b94a3;
  font-weight: 500;
  font-size: 12px;
}
.docs .num-col {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.docs .pick-col {
  width: 28px;
  padding-right: 0;
}
.docs .source {
  font-weight: 500;
  overflow-wrap: anywhere;
}
.docs .time {
  color: #8b94a3;
  font-size: 12px;
  white-space: nowrap;
}
/* 会覆盖本地文件的那一行，整行淡红 —— 勾选时不该看不出区别 */
.docs tr.conflict {
  background: #fffafa;
}

.tag {
  padding: 2px 9px;
  border-radius: 999px;
  background: #eef0f4;
  color: #5b6472;
  font-size: 12px;
}

.state {
  font-size: 12.5px;
  color: #6b7381;
}
.state.missing {
  color: #8a6220;
}
.state.synced {
  color: #0a7d55;
}
.state.stale {
  color: #14614a;
}
.state.name_conflict {
  color: #b0322f;
  font-weight: 500;
}

.del {
  padding: 3px 9px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: #fff;
  color: #8b94a3;
  font-size: 12px;
}
.del:hover {
  border-color: #10a37f;
  color: #10a37f;
}

.pager {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  color: #8b94a3;
  font-size: 12px;
}
.pager button {
  padding: 3px 9px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: #fff;
  font-size: 12px;
  color: #5b6472;
}
.pager button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.preview {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px dashed var(--line);
  border-radius: 10px;
  background: #fbfbfc;
}
.preview h3 {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}

.pick {
  margin: 8px 0;
}
.pick .warn {
  margin-bottom: 8px;
}

.chunks {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 7px;
  max-height: 420px;
  overflow: auto;
}
.chunks li {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  font-size: 12px;
  line-height: 1.6;
  color: #5b6472;
}
.idx {
  flex: none;
  min-width: 34px;
  color: #0a7d55;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.len {
  flex: none;
  min-width: 60px;
  color: #8b94a3;
}
.excerpt {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.preview-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}
.preview-foot .pager {
  margin-top: 0;
}
.preview-foot .btn.primary {
  margin-left: auto;
}

.ok {
  margin-top: 12px;
  color: #0a7d55;
  font-size: 13px;
}
.err {
  margin-top: 12px;
  color: #b0322f;
  font-size: 13px;
  line-height: 1.7;
}
.warn {
  margin-top: 12px;
  padding: 9px 12px;
  border: 1px solid #f0d8b0;
  border-radius: 9px;
  background: #fffaf1;
  color: #8a6220;
  font-size: 13px;
  line-height: 1.7;
}

@media (max-width: 640px) {
  .docs .time {
    display: none;
  }
}
</style>
