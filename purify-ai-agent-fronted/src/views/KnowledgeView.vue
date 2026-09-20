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
// 而分类表本来就是配置里的静态数据，改它的时候两边一起改更省事
const CATEGORIES = ['食物热量', '运动热量', '药物']

const classification = ref(CATEGORIES[0])

/* ------------------------------------------------------------------ 概览 */

const stats = ref(null)
const statsError = ref('')

async function loadStats() {
  try {
    stats.value = await fetchKnowledgeStats()
    statsError.value = ''
  } catch (err) {
    statsError.value = err.message || '读取概览失败'
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
const docsError = ref('')
const loadingDocs = ref(false)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

async function loadDocuments() {
  loadingDocs.value = true
  try {
    const data = await fetchKnowledgeDocuments(page.value, size)
    docs.value = data?.items || []
    total.value = data?.total || 0
    docsError.value = ''
  } catch (err) {
    docsError.value = err.message || '读取文档列表失败'
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
  if (!window.confirm(`删除「${source}」的全部切片？`)) return
  try {
    await deleteDocument(source)
    // 删完可能这一页就空了，往回退一页 —— 否则用户会看到一个空列表，
    // 以为整个知识库都被删了
    if (docs.value.length === 1 && page.value > 1) page.value -= 1
    await refreshAll()
  } catch (err) {
    docsError.value = err.message || '删除失败'
  }
}

/* ------------------------------------------------------------------ 上传 */

const uploading = ref(false)
const uploadMessage = ref('')
const uploadError = ref('')

async function onFiles(event) {
  const files = Array.from(event.target.files || [])
  if (!files.length) return
  // input 用完就重置，否则连续选同一个文件不会再触发 change
  event.target.value = ''

  uploading.value = true
  uploadMessage.value = ''
  uploadError.value = ''
  try {
    if (files.length === 1) {
      const result = await uploadDocument(files[0], classification.value)
      uploadMessage.value = `「${result.source}」已入库：${result.chunkCount} 个切片`
    } else {
      const result = await uploadDocuments(files, classification.value)
      uploadMessage.value = `导入完成：成功 ${result.succeeded} 份，失败 ${result.failed} 份`
      // 有失败就把原因摆出来，不要只报一个数字 ——
      // 「3 份失败」对排查毫无帮助，而失败原因往往一眼就能看懂（格式不对、编码不对）
      const failures = (result.items || []).filter((i) => !i.ok)
      if (failures.length) {
        uploadError.value = failures.map((i) => `${i.filename}：${i.message}`).join('；')
      }
    }
    page.value = 1
    await refreshAll()
  } catch (err) {
    uploadError.value = err.message || '上传失败'
  } finally {
    uploading.value = false
  }
}

/* ------------------------------------------------------------------ 预览 */

const previewing = ref(false)
const preview = ref(null)
const previewError = ref('')

async function onPreviewFile(event) {
  const file = (event.target.files || [])[0]
  event.target.value = ''
  if (!file) return

  previewing.value = true
  preview.value = null
  previewError.value = ''
  try {
    preview.value = await previewDocument(file, classification.value)
  } catch (err) {
    previewError.value = err.message || '预览失败'
  } finally {
    previewing.value = false
  }
}

/* -------------------------------------------------------------- 检索自检 */

const query = ref('')
const searching = ref(false)
const searchResult = ref(null)
const searchError = ref('')

async function runSearch() {
  const q = query.value.trim()
  if (!q || searching.value) return
  searching.value = true
  searchError.value = ''
  try {
    searchResult.value = await searchKnowledge(q)
  } catch (err) {
    searchResult.value = null
    searchError.value = err.message || '检索失败'
  } finally {
    searching.value = false
  }
}

onMounted(refreshAll)
</script>

<template>
  <div class="page">
    <header class="bar">
      <RouterLink to="/" class="back" title="返回主页">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M15 18l-6-6 6-6" />
        </svg>
      </RouterLink>
      <h1>知识库</h1>
      <button class="ghost" type="button" @click="refreshAll">刷新</button>
    </header>

    <main class="body">
      <!-- ======================= 概览 ======================= -->
      <section class="card">
        <h2>概览</h2>
        <p v-if="statsError" class="err">{{ statsError }}</p>
        <div v-else-if="stats" class="metrics">
          <div class="metric">
            <span class="num">{{ stats.totalChunks }}</span>
            <span class="label">切片总数</span>
          </div>
          <div class="metric">
            <span class="num">{{ stats.documentCount }}</span>
            <span class="label">文档份数</span>
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
          知识库还是空的。上传几份文档之后，对话才会开始查它。
        </p>
      </section>

      <!-- ======================= 上传 ======================= -->
      <section class="card">
        <h2>上传建索引</h2>
        <p class="desc">
          支持 txt / md。同一文件名重复上传会覆盖旧切片。选一整个目录可以批量导入。
        </p>

        <div class="row">
          <label class="field">
            分类
            <select v-model="classification">
              <option v-for="c in CATEGORIES" :key="c" :value="c">{{ c }}</option>
            </select>
          </label>

          <label class="btn" :class="{ disabled: uploading }">
            {{ uploading ? '处理中…' : '选择文件' }}
            <input type="file" accept=".txt,.md,.markdown" multiple :disabled="uploading" @change="onFiles" />
          </label>

          <!-- 选目录要单独一个 input：webkitdirectory 一旦加上，这个框就只能选目录、
               不能再多选散文件，两者是互斥的两种选择方式，硬塞进一个框会顾此失彼。
               非 Chrome/Edge 浏览器会忽略这个属性，退化成普通多选，不影响可用性 -->
          <label class="btn subtle" :class="{ disabled: uploading }">
            {{ uploading ? '处理中…' : '导入整个目录' }}
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
            {{ previewing ? '预览中…' : '预览切片' }}
            <input type="file" accept=".txt,.md,.markdown" :disabled="previewing" @change="onPreviewFile" />
          </label>
        </div>

        <p v-if="uploadMessage" class="ok">{{ uploadMessage }}</p>
        <p v-if="uploadError" class="err">{{ uploadError }}</p>
        <p v-if="previewError" class="err">{{ previewError }}</p>

        <!-- 预览结果 -->
        <div v-if="preview" class="preview">
          <h3>
            预览：{{ preview.source }} → 会切成 {{ preview.chunkCount }} 片，共
            {{ preview.characters }} 字符
          </h3>
          <p v-if="preview.truncated" class="desc">下面只列了前 {{ preview.chunks.length }} 片。</p>
          <ul class="chunks">
            <li v-for="c in preview.chunks" :key="c.index">
              <span class="idx">#{{ c.index }}</span>
              <span class="len">{{ c.length }} 字</span>
              <span class="excerpt">{{ c.excerpt }}</span>
            </li>
          </ul>
        </div>
      </section>

      <!-- ======================= 文档列表 ======================= -->
      <section class="card">
        <div class="card-head">
          <h2>文档（{{ total }}）</h2>
          <div class="pager" v-if="totalPages > 1">
            <button type="button" :disabled="page <= 1" @click="goto(page - 1)">上一页</button>
            <span>{{ page }} / {{ totalPages }}</span>
            <button type="button" :disabled="page >= totalPages" @click="goto(page + 1)">下一页</button>
          </div>
        </div>

        <p v-if="docsError" class="err">{{ docsError }}</p>
        <p v-else-if="loadingDocs" class="desc">读取中…</p>
        <p v-else-if="!docs.length" class="desc">还没有文档。</p>

        <table v-else class="docs">
          <thead>
            <tr>
              <th>来源</th>
              <th>分类</th>
              <th class="num-col">切片</th>
              <th class="num-col">字符</th>
              <th>入库时间</th>
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
                <button type="button" class="del" @click="remove(d.source)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      <!-- ====================== 检索自检 ====================== -->
      <section class="card">
        <h2>检索自检</h2>
        <p class="desc">
          拿一句话真跑一次检索，看看会不会查、查出什么、以及最终会拼进 Prompt 的原文。
          这个操作只读，可以随便试。
        </p>

        <form class="search" @submit.prevent="runSearch">
          <input v-model="query" type="text" placeholder="例如：一碗米饭的热量是多少千卡" />
          <button type="submit" :disabled="searching || !query.trim()">
            {{ searching ? '检索中…' : '检索' }}
          </button>
        </form>

        <p v-if="searchError" class="err">{{ searchError }}</p>

        <template v-if="searchResult">
          <div class="verdict" :class="{ skip: !searchResult.retrieved }">
            <template v-if="!searchResult.retrieved">
              <b>没有发起检索</b> —— 路由判定这句话与知识库无关。这是「压根没查」，
              不是「查了没命中」。想让这类问题也走检索，把
              <code>purify.rag.router.query-all-when-unmatched</code> 改成 true。
            </template>
            <template v-else>
              <b>已检索</b>：命中 {{ searchResult.count }} 条，耗时 {{ searchResult.elapsedMs }}ms，
              分类过滤：{{ searchResult.categories?.length ? searchResult.categories.join('、') : '全库' }}
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
            <summary>会拼进 Prompt 的原文</summary>
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
