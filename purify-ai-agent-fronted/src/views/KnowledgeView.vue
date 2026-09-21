<script setup>
import { ref, computed, onMounted } from 'vue'
import {
  fetchKnowledgeStats,
  fetchKnowledgeDocuments,
  uploadDocument,
  uploadDocuments,
  previewDocument,
  deleteDocument,
  searchKnowledge,
} from '../api/http.js'
import { isEnglish, message, rawMessage, resolveMessage, t } from '../i18n/index.js'

/**
 * 知识库管理页 —— RAG 第一阶段（离线索引）的界面。
 *
 * 三块：
 *   1. 概览：切片总数、文档份数、分类分布
 *   2. 文档列表：逐份的切片数 / 分类 / 入库时间，可以删
 *   3. 检索自检：拿一句话真跑一次检索，看知识库到底能查出什么
 *
 * 第三块不是可有可无的。检索是「悄悄发生」的 —— 命中也好、没查也好，
 * 在聊天界面上看都是「模型开始回答了」。答得不对的时候，「知识库没召回到」和
 * 「压根没查」的排查方向完全不同，靠这个面板才能分开。
 */

/* ------------------------------------------------------------------ 分类 */

// 和后端 purify.rag.router.categories 里的 value 必须一字不差，
// 后端会拦下不认识的值。这里写死一份是有意的：改成从接口拉，就得先有接口，
// 而分类表本来就是配置里的静态数据，改它的时候两边一起改更省事。
//
// **这三个值不翻译，界面上也是原样显示。** 它们不是「中文文案」，是数据：
// 写进了 pgvector 的元数据、还被当作等值过滤条件用，翻掉之后已经入库的文档
// 一份都检索不到——而界面上看不出任何异常，这比报错难查得多。
// 所以下面 <option> 里没有第二个值可以给，不要在这里做「显示一份、发送一份」。
const CATEGORIES = ['食物热量', '运动热量', '药物']

const classification = ref(CATEGORIES[0])

/* ------------------------------------------------------------------ 概览 */

const stats = ref(null)
/** 各处的报错都存描述符不存句子，见 i18n/index.js 的 message()：存句子的话切语言不会跟着变 */
const statsError = ref(null)

const statsErrorText = computed(() => resolveMessage(statsError.value))

async function loadStats() {
  try {
    stats.value = await fetchKnowledgeStats()
    statsError.value = null
  } catch (err) {
    statsError.value = err.message ? rawMessage(err.message) : message('knowledge.overview.loadFailed')
  }
}

const distribution = computed(() => {
  const map = stats.value?.chunksByClassification || {}
  return Object.entries(map).map(([name, count]) => ({ name, count }))
})

/* -------------------------------------------------------------- 文档列表 */

const docs = ref([])
const page = ref(1)
const size = 10
const total = ref(0)
const docsError = ref(null)
const loadingDocs = ref(false)

const docsErrorText = computed(() => resolveMessage(docsError.value))

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

async function loadDocuments() {
  loadingDocs.value = true
  try {
    const data = await fetchKnowledgeDocuments(page.value, size)
    docs.value = data?.items || []
    total.value = data?.total || 0
    docsError.value = null
  } catch (err) {
    docsError.value = err.message ? rawMessage(err.message) : message('knowledge.docs.loadFailed')
  } finally {
    loadingDocs.value = false
  }
}

function goto(next) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
  loadDocuments()
}

async function refreshAll() {
  await Promise.all([loadStats(), loadDocuments()])
}

async function remove(source) {
  if (!window.confirm(t('knowledge.docs.deleteConfirm', { source }))) return
  try {
    await deleteDocument(source)
    // 删完可能这一页就空了，往回退一页 —— 否则用户会看到一个空列表，
    // 以为整个知识库都被删了
    if (docs.value.length === 1 && page.value > 1) page.value -= 1
    await refreshAll()
  } catch (err) {
    docsError.value = err.message ? rawMessage(err.message) : message('knowledge.docs.deleteFailed')
  }
}

/* ------------------------------------------------------------------ 上传 */

const uploading = ref(false)
/**
 * 这两条都是**拼接出来的**结果文案（带文件名、切片数），所以它们存的是
 * 「键 + 参数」而不是句子——切语言时按新语言重拼一遍。
 * 直接存拼好的字符串的话，用户切完语言这里会是一句中英混排：
 * 数字是新的、模板是旧的。
 */
const uploadMessage = ref(null)
/**
 * 报错存的是**一组**描述符而不是一句拼好的话。
 *
 * 批量导入失败时会一次列出好几条原因，拼成一句存下来的话，切语言之后
 * 那一句里每个模板都已经冻在旧语言里了。存列表、渲染时逐条翻再拼起来，
 * 才能整句跟着语言走。分隔符也跟着语言走（中文用「；」，英文用「; 」）。
 */
const uploadError = ref([])

const uploadMessageText = computed(() => resolveMessage(uploadMessage.value))
const uploadErrorText = computed(() => {
  const separator = isEnglish() ? '; ' : '；'
  return uploadError.value.map(resolveMessage).join(separator)
})

async function onFiles(event) {
  const files = Array.from(event.target.files || [])
  if (!files.length) return
  // input 用完就重置，否则连续选同一个文件不会再触发 change
  event.target.value = ''

  uploading.value = true
  uploadMessage.value = null
  uploadError.value = []
  try {
    if (files.length === 1) {
      const result = await uploadDocument(files[0], classification.value)
      uploadMessage.value = message('knowledge.upload.single', {
        source: result.source,
        count: result.chunkCount,
      })
    } else {
      const result = await uploadDocuments(files, classification.value)
      uploadMessage.value = message('knowledge.upload.batch', {
        ok: result.succeeded,
        failed: result.failed,
      })
      // 有失败就把原因摆出来，不要只报一个数字 ——
      // 「3 份失败」对排查毫无帮助，而失败原因往往一眼就能看懂（格式不对、编码不对）。
      // 这里存的是**一组**描述符，所以单独包一层：resolveMessage 只认单个
      const failures = (result.items || []).filter((i) => !i.ok)
      // i.message 是后端按 Accept-Language 翻好的一句结论（「不是 UTF-8」之类），
      // 前端手里没有它的键，所以这里用的是 message() 里 text 那一支
      uploadError.value = failures.map((i) =>
        message('knowledge.upload.failureItem', { name: i.filename, message: i.message }),
      )
    }
    page.value = 1
    await refreshAll()
  } catch (err) {
    // 整批失败（网络、超时）：只有一句话
    uploadError.value = [err.message ? rawMessage(err.message) : message('knowledge.upload.failed')]
  } finally {
    uploading.value = false
  }
}

/* ------------------------------------------------------------------ 预览 */

const previewing = ref(false)
const preview = ref(null)
const previewError = ref(null)

const previewErrorText = computed(() => resolveMessage(previewError.value))

async function onPreviewFile(event) {
  const file = (event.target.files || [])[0]
  event.target.value = ''
  if (!file) return

  previewing.value = true
  preview.value = null
  previewError.value = null
  try {
    preview.value = await previewDocument(file, classification.value)
  } catch (err) {
    previewError.value = err.message ? rawMessage(err.message) : message('knowledge.preview.failed')
  } finally {
    previewing.value = false
  }
}

/* -------------------------------------------------------------- 检索自检 */

const query = ref('')
const searching = ref(false)
const searchResult = ref(null)
const searchError = ref(null)

const searchErrorText = computed(() => resolveMessage(searchError.value))

/** 「命中 N 条，耗时 Xms，分类过滤：…」那一整句，按当前语言拼。 */
const searchVerdict = computed(() => {
  const result = searchResult.value
  if (!result) return null
  const categories = result.categories?.length
    ? result.categories.join(isEnglish() ? ', ' : '、')
    : t('knowledge.search.allCategories')
  return {
    count: result.count,
    elapsed: result.elapsedMs,
    categories,
  }
})

async function runSearch() {
  const q = query.value.trim()
  if (!q || searching.value) return
  searching.value = true
  searchError.value = null
  try {
    searchResult.value = await searchKnowledge(q)
  } catch (err) {
    searchResult.value = null
    searchError.value = err.message ? rawMessage(err.message) : message('knowledge.search.failed')
  } finally {
    searching.value = false
  }
}

onMounted(refreshAll)
</script>

<template>
  <div class="page">
    <header class="bar">
      <RouterLink to="/" class="back" :title="$t('knowledge.backHome')">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M15 18l-6-6 6-6" />
        </svg>
      </RouterLink>
      <h1>{{ $t('knowledge.title') }}</h1>
      <button class="ghost" type="button" @click="refreshAll">{{ $t('common.refresh') }}</button>
    </header>

    <main class="body">
      <!-- ======================= 概览 ======================= -->
      <section class="card">
        <h2>{{ $t('knowledge.overview.title') }}</h2>
        <p v-if="statsErrorText" class="err">{{ statsErrorText }}</p>
        <div v-else-if="stats" class="metrics">
          <div class="metric">
            <span class="num">{{ stats.totalChunks }}</span>
            <span class="label">{{ $t('knowledge.overview.chunks') }}</span>
          </div>
          <div class="metric">
            <span class="num">{{ stats.documentCount }}</span>
            <span class="label">{{ $t('knowledge.overview.documents') }}</span>
          </div>
        </div>
        <div v-if="distribution.length" class="dist">
          <span v-for="d in distribution" :key="d.name" class="tag">
            {{ d.name }} <b>{{ d.count }}</b>
          </span>
        </div>
        <!-- 切片总数为 0 是最值得点名的一种状态：检索查不到东西时，
             第一个要排除的就是「库里根本没有数据」 -->
        <p v-if="stats && !stats.totalChunks" class="warn">
          {{ $t('knowledge.overview.empty') }}
        </p>
      </section>

      <!-- ======================= 上传 ======================= -->
      <section class="card">
        <h2>{{ $t('knowledge.upload.title') }}</h2>
        <p class="desc">
          {{ $t('knowledge.upload.desc') }}
        </p>

        <div class="row">
          <label class="field">
            {{ $t('knowledge.upload.category') }}
            <!-- 这一列**不翻译**：它的值要和后端 purify.rag.router.categories 一字不差，
                 翻了已入库的文档全都检索不到。理由写在上面 CATEGORIES 那里 -->
            <select v-model="classification">
              <option v-for="c in CATEGORIES" :key="c" :value="c">{{ c }}</option>
            </select>
          </label>

          <label class="btn" :class="{ disabled: uploading }">
            {{ uploading ? $t('knowledge.upload.processing') : $t('knowledge.upload.pick') }}
            <input type="file" accept=".txt,.md,.markdown" multiple :disabled="uploading" @change="onFiles" />
          </label>

          <!-- 选目录要单独一个 input：webkitdirectory 一旦加上，这个框就只能选目录、
               不能再多选散文件，两者是互斥的两种选择方式，硬塞进一个框会顾此失彼。
               非 Chrome/Edge 浏览器会忽略这个属性，退化成普通多选，不影响可用性 -->
          <label class="btn subtle" :class="{ disabled: uploading }">
            {{ uploading ? $t('knowledge.upload.processing') : $t('knowledge.upload.pickDir') }}
            <input
              type="file"
              accept=".txt,.md,.markdown"
              webkitdirectory
              multiple
              :disabled="uploading"
              @change="onFiles"
            />
          </label>

          <label class="btn subtle" :class="{ disabled: previewing }">
            {{ previewing ? $t('knowledge.upload.previewing') : $t('knowledge.upload.preview') }}
            <input type="file" accept=".txt,.md,.markdown" :disabled="previewing" @change="onPreviewFile" />
          </label>
        </div>

        <p v-if="uploadMessageText" class="ok">{{ uploadMessageText }}</p>
        <p v-if="uploadErrorText" class="err">{{ uploadErrorText }}</p>
        <p v-if="previewErrorText" class="err">{{ previewErrorText }}</p>

        <!-- 预览结果 -->
        <div v-if="preview" class="preview">
          <h3>
            {{ $t('knowledge.preview.heading', {
              source: preview.source,
              count: preview.chunkCount,
              chars: preview.characters,
            }) }}
          </h3>
          <p v-if="preview.truncated" class="desc">
            {{ $t('knowledge.preview.truncated', { count: preview.chunks.length }) }}
          </p>
          <ul class="chunks">
            <li v-for="c in preview.chunks" :key="c.index">
              <span class="idx">#{{ c.index }}</span>
              <span class="len">{{ $t('knowledge.preview.chars', { n: c.length }) }}</span>
              <span class="excerpt">{{ c.excerpt }}</span>
            </li>
          </ul>
        </div>
      </section>

      <!-- ======================= 文档列表 ======================= -->
      <section class="card">
        <div class="card-head">
          <h2>{{ $t('knowledge.docs.title', { n: total }) }}</h2>
          <div class="pager" v-if="totalPages > 1">
            <button type="button" :disabled="page <= 1" @click="goto(page - 1)">{{ $t('knowledge.docs.prev') }}</button>
            <span>{{ page }} / {{ totalPages }}</span>
            <button type="button" :disabled="page >= totalPages" @click="goto(page + 1)">{{ $t('knowledge.docs.next') }}</button>
          </div>
        </div>

        <p v-if="docsErrorText" class="err">{{ docsErrorText }}</p>
        <p v-else-if="loadingDocs" class="desc">{{ $t('knowledge.docs.loading') }}</p>
        <p v-else-if="!docs.length" class="desc">{{ $t('knowledge.docs.empty') }}</p>

        <table v-else class="docs">
          <thead>
            <tr>
              <th>{{ $t('knowledge.docs.source') }}</th>
              <th>{{ $t('knowledge.docs.category') }}</th>
              <th class="num-col">{{ $t('knowledge.docs.chunks') }}</th>
              <th class="num-col">{{ $t('knowledge.docs.characters') }}</th>
              <th>{{ $t('knowledge.docs.uploadedAt') }}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in docs" :key="d.source">
              <td class="source">{{ d.source }}</td>
              <td><span class="tag">{{ d.classification }}</span></td>
              <td class="num-col">{{ d.chunks }}</td>
              <td class="num-col">{{ d.characters }}</td>
              <td class="time">{{ (d.uploadedAt || '').replace('T', ' ').slice(0, 19) }}</td>
              <td>
                <button type="button" class="del" @click="remove(d.source)">{{ $t('common.delete') }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      <!-- ====================== 检索自检 ====================== -->
      <section class="card">
        <h2>{{ $t('knowledge.search.title') }}</h2>
        <p class="desc">
          {{ $t('knowledge.search.desc') }}
        </p>

        <form class="search" @submit.prevent="runSearch">
          <input v-model="query" type="text" :placeholder="$t('knowledge.search.placeholder')" />
          <button type="submit" :disabled="searching || !query.trim()">
            {{ searching ? $t('knowledge.search.running') : $t('knowledge.search.submit') }}
          </button>
        </form>

        <p v-if="searchErrorText" class="err">{{ searchErrorText }}</p>

        <template v-if="searchResult">
          <div class="verdict" :class="{ skip: !searchResult.retrieved }">
            <template v-if="!searchResult.retrieved">
              <b>{{ $t('knowledge.search.skipped') }}</b>{{ $t('knowledge.search.skippedDetail') }}
            </template>
            <template v-else>
              <b>{{ $t('knowledge.search.retrieved') }}</b>
              {{ $t('knowledge.search.retrievedDetail', searchVerdict) }}
            </template>
          </div>

          <ul v-if="searchResult.chunks?.length" class="chunks">
            <li v-for="c in searchResult.chunks" :key="c.index">
              <span class="idx">[{{ c.index }}]</span>
              <span class="len">{{ c.docName }}<template v-if="c.score != null"> · {{ c.score.toFixed(3) }}</template></span>
              <span class="excerpt">{{ c.excerpt }}</span>
            </li>
          </ul>

          <details v-if="searchResult.referenceText" class="raw">
            <summary>{{ $t('knowledge.search.rawSummary') }}</summary>
            <pre>{{ searchResult.referenceText }}</pre>
          </details>
        </template>
      </section>
    </main>
  </div>
</template>

<style scoped>
.page {
  min-height: 100dvh;
  background: var(--page);
}

.bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 24px;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--line);
  position: sticky;
  top: 0;
  z-index: 5;
}
.bar h1 {
  font-size: 15px;
  font-weight: 600;
}
.back {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  margin-left: -6px;
  border-radius: 9px;
  color: #5b6472;
}
.back:hover {
  background: #eef0f4;
  color: var(--ink);
}
.back svg {
  width: 18px;
  height: 18px;
}

.ghost {
  margin-left: auto;
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

.body {
  max-width: 900px;
  margin: 0 auto;
  padding: 22px 24px 48px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

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

.desc {
  color: #6b7381;
  font-size: 13px;
  line-height: 1.7;
}

.metrics {
  display: flex;
  gap: 26px;
}
.metric {
  display: flex;
  flex-direction: column;
}
.num {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
}
.label {
  color: #8b94a3;
  font-size: 12px;
}

.dist {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 14px;
}
.tag {
  padding: 2px 9px;
  border-radius: 999px;
  background: #eef0f4;
  color: #5b6472;
  font-size: 12px;
}
.tag b {
  color: var(--ink);
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
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

/* 文件选择：原生 input 长得没法看，把 label 做成按钮、把 input 藏起来 */
.btn {
  position: relative;
  display: inline-block;
  padding: 7px 14px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #fff;
  color: #3d4653;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.18s ease, color 0.18s ease, background 0.18s ease;
}
.btn:hover {
  border-color: #10a37f;
  color: #10a37f;
  background: #f0faf7;
}
.btn.subtle {
  color: #6b7381;
}
.btn input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}
.btn.disabled {
  opacity: 0.55;
  pointer-events: none;
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

.chunks {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 7px;
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
  min-width: 90px;
  color: #8b94a3;
}
.excerpt {
  overflow-wrap: anywhere;
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
.docs .source {
  font-weight: 500;
  overflow-wrap: anywhere;
}
.docs .time {
  color: #8b94a3;
  font-size: 12px;
  white-space: nowrap;
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
  border-color: #b0322f;
  color: #b0322f;
}

.pager {
  display: flex;
  align-items: center;
  gap: 8px;
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

.search {
  display: flex;
  gap: 9px;
  margin-top: 12px;
}
.search input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid #dfe2e8;
  border-radius: 9px;
  font: inherit;
  font-size: 13.5px;
  outline: none;
}
.search input:focus {
  border-color: #10a37f;
  box-shadow: 0 0 0 3px #10a37f1f;
}
.search button {
  flex: none;
  padding: 8px 18px;
  border: none;
  border-radius: 9px;
  background: #10a37f;
  color: #fff;
  font-size: 13.5px;
}
.search button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.verdict {
  margin-top: 14px;
  padding: 10px 13px;
  border: 1px solid #bfe5d8;
  border-radius: 10px;
  background: #f2fbf8;
  color: #14614a;
  font-size: 13px;
  line-height: 1.7;
}
.verdict.skip {
  border-color: #f0d8b0;
  background: #fffaf1;
  color: #8a6220;
}
.verdict code {
  padding: 1px 5px;
  border-radius: 5px;
  background: rgba(0, 0, 0, 0.06);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.raw {
  margin-top: 12px;
}
.raw summary {
  color: #6b7381;
  font-size: 13px;
  cursor: pointer;
}
.raw pre {
  margin: 9px 0 0;
  padding: 12px 14px;
  max-height: 320px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #f7f8fa;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 640px) {
  .body {
    padding: 16px 12px 40px;
  }
  .docs .time {
    display: none;
  }
}
</style>
