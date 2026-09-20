<script setup>
import AppIcon from '../components/AppIcon.vue'
import { SLIM, MANUS } from '../chatConfig.js'

/**
 * 主页是纯静态的应用入口，不请求任何后端接口 —— 后端没启动时也要能正常打开。
 *
 * 版式对齐 DeepSeek 官网：顶栏（标 + 次级链接）→ 居中标语 + 一颗主按钮
 * → 次级入口卡 → 分栏页脚。主色取官网那支电光蓝。
 *
 * 标题是**纯色**不是渐变。这一点是有意的：渐变标题（尤其是青绿→紫那一挂）
 * 是最快让人看出「AI 生成的落地页」的一招，也是这次要拆掉的东西。
 */

/**
 * 次级入口，占官网「API 开放平台」那一块的位置。
 *
 * 每张卡只在自己的图标底上用它那条链路的主题色（--ca）。
 * 那个色块的作用是「提醒你这条链路在聊天页里是什么颜色」，不是拿来做整页配色的 ——
 * 页面的主色调始终是蓝的。
 */
const entries = [
  {
    to: '/manus',
    name: MANUS.title,
    icon: 'agent',
    accent: MANUS.accent,
    desc: '能自己查资料、调工具、写文件，一步步把任务做完。信息不够时会停下来问你。',
    meta: '工具调用 · MCP · 循环自检',
  },
  {
    to: '/knowledge',
    name: '知识库',
    icon: 'book',
    accent: SLIM.accent,
    desc: '上传文档建索引，看每份文档切成了几片，再用一句话试着检索一次 —— 对话里查的就是它。',
    meta: '文档索引 · 向量检索 · 检索自检',
  },
]

/**
 * 光标走到哪，那一片点阵就亮成蓝色。
 *
 * 背景层是 position: fixed，坐标系本身就是视口，所以直接取 clientX/clientY，
 * 不用再减 getBoundingClientRect —— 页面滚动时这样反而更准。
 *
 * 只写 CSS 变量，实际渲染交给 mask 上的 radial-gradient：变的只是合成层的遮罩位置，
 * 不触发重排，鼠标扫过去才跟得住。
 */
function trackPointer(e) {
  const root = e.currentTarget
  root.style.setProperty('--mx', `${e.clientX}px`)
  root.style.setProperty('--my', `${e.clientY}px`)
}
</script>

<template>
  <div class="home" @mousemove="trackPointer">
    <!-- 背景层：顶部一层淡蓝 + 点阵网格，光标附近那圈点会亮起来。纯装饰，对读屏隐藏 -->
    <div class="bg" aria-hidden="true">
      <span class="wash"></span>
      <span class="dots">
        <span class="dots-hi"></span>
      </span>
    </div>

    <header class="nav">
      <RouterLink to="/" class="brand">
        <span class="mark"><AppIcon name="drop" /></span>
        <span class="brand-name">Purify AI</span>
      </RouterLink>

      <nav class="nav-side">
        <RouterLink to="/manus">PurifyManus</RouterLink>
        <RouterLink to="/knowledge">知识库</RouterLink>
      </nav>
    </header>

    <main class="hero">
      <p class="chip enter" :style="{ '--d': '0ms' }">
        <span class="pip" aria-hidden="true"></span>
        Spring AI 驱动 · 一条会聊天，一条会干活
      </p>

      <h1 class="slogan enter" :style="{ '--d': '80ms' }">先查清楚，再回答</h1>

      <p class="lede enter" :style="{ '--d': '160ms' }">
        轻语答健康问题前会先翻一遍知识库，PurifyManus 动手前会先查资料。翻不到就说翻不到，不编。
      </p>

      <!-- 主按钮。官网那颗「开始对话」的位置，落在轻语上 ——
           一问一答，是三条链路里最好上手的那个 -->
      <RouterLink to="/slim" class="cta enter" :style="{ '--d': '240ms' }">
        开始对话
        <span class="arrow" aria-hidden="true">→</span>
      </RouterLink>

      <p class="cta-note enter" :style="{ '--d': '300ms' }">
        与轻语对话 · 健康瘦身顾问，回答前先查知识库
      </p>
    </main>

    <section class="entries" aria-label="其他入口">
      <!-- 整张卡片是 RouterLink，渲染成 <a>，所以键盘 Enter 天然可用，
           不需要额外补 tabindex 和 keydown -->
      <RouterLink
        v-for="(e, i) in entries"
        :key="e.to"
        class="entry enter"
        :to="e.to"
        :style="{ '--ca': e.accent, '--ca-soft': e.accent + '1f', '--d': `${360 + i * 90}ms` }"
      >
        <span class="entry-icon"><AppIcon :name="e.icon" /></span>
        <h2 class="entry-title">{{ e.name }}</h2>
        <p class="entry-desc">{{ e.desc }}</p>
        <p class="entry-meta">
          <span>{{ e.meta }}</span>
          <span class="arrow" aria-hidden="true">→</span>
        </p>
      </RouterLink>
    </section>

    <footer class="foot">
      <div class="foot-inner">
        <div class="foot-cols">
          <div class="col">
            <h3>对话链路</h3>
            <RouterLink to="/slim">轻语</RouterLink>
            <RouterLink to="/manus">PurifyManus</RouterLink>
          </div>

          <div class="col">
            <h3>知识库</h3>
            <RouterLink to="/knowledge">文档管理</RouterLink>
            <RouterLink to="/knowledge">检索自检</RouterLink>
          </div>

          <div class="col">
            <h3>关于</h3>
            <p>前端 Vue 3 + Vite，后端 Spring Boot + Spring AI，向量检索走 pgvector。</p>
          </div>
        </div>

        <div class="foot-bar">
          <span>© 2026 Purify AI</span>
          <span>本地部署 · 单端口 8080</span>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.home {
  /* 变量集中在这里。--blue / --ink 这几个值抄的是官网那套品牌色：
     主色 #4D6BFE，深一档 #4166D5，深色底 #0A0F1E（这里拿它当正文色） */
  --blue: #4d6bfe;
  --blue-deep: #4166d5;
  --ink: #0a0f1e;
  --muted: #6b7488;
  --line: #e6e9f2;

  /* 光标高亮层的中心。默认落在视口外，等于这一层不存在 ——
     纯键盘 / 触屏用户看到的就只是一张安静的点阵 */
  --mx: -999px;
  --my: -999px;

  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
  /* 底色比纯白低一档：卡片的白要有地方「浮」起来 */
  background: #fbfcfe;
  color: var(--ink);
}

/* ---------------------------------------------------------------- 背景层 */

.bg {
  /* fixed 而不是 absolute：点阵在内容滚动时留在原地，
     页面「长」出来的感觉才对，也不会在页脚附近堆出一片歪掉的网格 */
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}

/* 顶部那层蓝。只做一层静态渐变 ——
   官网背后那套 WebGL 流体对一个入口页来说太重了，收益配不上代价 */
.wash {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse 68% 44% at 50% -8%, rgba(77, 107, 254, 0.13), transparent 70%),
    radial-gradient(ellipse 38% 30% at 84% 6%, rgba(77, 107, 254, 0.07), transparent 70%);
}

.dots {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(circle, rgba(10, 15, 30, 0.09) 1px, transparent 1.2px);
  /* 30px 一格：官网是 90px，那个间距在整屏宽的页面上才成立，
     这块内容宽度不到 1000px，走 90 会稀得看不出是网格 */
  background-size: 30px 30px;
  /* 往下渐隐。一路铺到页脚会像一张没画完的草稿纸 */
  -webkit-mask-image: linear-gradient(#000 0%, #000 56%, transparent 92%);
  mask-image: linear-gradient(#000 0%, #000 56%, transparent 92%);
}

/*
 * 光标附近的那层蓝点。
 *
 * 嵌套在 .dots 里面而不是并列，是为了让两边的遮罩相乘：
 * 父层的「向下渐隐」和本层的「光标径向」各管各的，不用去写 mask-composite。
 * 网格参数必须和父层一致，否则两层点会错开半个格子，看起来像重影。
 */
.dots-hi {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(circle, rgba(77, 107, 254, 0.8) 1.4px, transparent 1.6px);
  background-size: 30px 30px;
  -webkit-mask-image: radial-gradient(150px circle at var(--mx) var(--my), #000 0%, transparent 100%);
  mask-image: radial-gradient(150px circle at var(--mx) var(--my), #000 0%, transparent 100%);
}

/* ------------------------------------------------------------------ 顶栏 */

.nav {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 20px clamp(20px, 5vw, 52px);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--ink);
  text-decoration: none;
}

.mark {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 9px;
  background: var(--blue);
  color: #fff;
}
.mark svg {
  width: 17px;
  height: 17px;
}

.brand-name {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.nav-side {
  display: flex;
  align-items: center;
  gap: 22px;
  /* margin-left: auto 把次级链接推到右边，和官网一样 */
  margin-left: auto;
}

.nav-side a {
  color: #4a5468;
  font-size: 13.5px;
  text-decoration: none;
  transition: color 0.18s ease;
}
.nav-side a:hover {
  color: var(--blue);
}

/* ------------------------------------------------------------------ Hero */

.hero {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: clamp(44px, 9vh, 104px) clamp(20px, 5vw, 52px) clamp(36px, 5vh, 60px);
  text-align: center;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 14px 5px 11px;
  border: 1px solid var(--line);
  border-radius: 999px;
  /* 半透明白 + 毛玻璃：底下那层点阵能透出来一点，标签才不像贴上去的 */
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  color: #4a5468;
  font-size: 12.5px;
  letter-spacing: 0.01em;
}

.pip {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--blue);
}

.slogan {
  margin-top: 24px;
  font-size: clamp(34px, 6.2vw, 62px);
  font-weight: 700;
  letter-spacing: -0.035em;
  line-height: 1.12;
  color: var(--ink);
}

.lede {
  max-width: 32em;
  margin-top: 18px;
  color: var(--muted);
  font-size: clamp(14.5px, 1.55vw, 16.5px);
  line-height: 1.78;
}

.cta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: clamp(28px, 4.5vh, 40px);
  padding: 13px 34px;
  border-radius: 12px;
  background: var(--blue);
  color: #fff;
  font-size: 15px;
  font-weight: 500;
  text-decoration: none;
  /* 阴影用主色本身而不是黑色：蓝底在白底上要「浮」起来，
     靠同色系的投影比压一层灰自然 */
  box-shadow: 0 10px 24px -12px rgba(77, 107, 254, 0.85);
  transition: background 0.2s ease, transform 0.2s ease, box-shadow 0.2s ease;
}
.cta:hover,
.cta:focus-visible {
  background: var(--blue-deep);
  transform: translateY(-1px);
  box-shadow: 0 14px 28px -12px rgba(77, 107, 254, 0.9);
}
.cta:focus-visible {
  outline: 2px solid var(--blue-deep);
  outline-offset: 3px;
}

.arrow {
  transition: transform 0.28s cubic-bezier(0.16, 0.84, 0.44, 1);
}
.cta:hover .arrow,
.cta:focus-visible .arrow,
.entry:hover .arrow,
.entry:focus-visible .arrow {
  transform: translateX(4px);
}

.cta-note {
  margin-top: 14px;
  color: #8b93a6;
  font-size: 12.5px;
}

/* -------------------------------------------------------------- 次级入口 */

.entries {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
  width: 100%;
  /* 和页脚用同一个宽度和边距，两处左边缘才对得齐 */
  max-width: 920px;
  margin: 0 auto;
  padding-inline: clamp(20px, 5vw, 52px);
}

.entry {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 22px 22px 18px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  color: inherit;
  text-decoration: none;
  transition: border-color 0.22s ease, background 0.22s ease, transform 0.22s ease,
    box-shadow 0.22s ease;
}

.entry:hover,
.entry:focus-visible {
  border-color: rgba(77, 107, 254, 0.42);
  background: #fff;
  transform: translateY(-2px);
  /* 位移压到 2px：官网的卡片几乎不动，靠描边变色来给反馈，
     动太多就又是那种「飘」的落地页了 */
  box-shadow: 0 16px 36px -20px rgba(10, 15, 30, 0.3);
}
.entry:focus-visible {
  outline: none;
}

.entry-icon {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 11px;
  background: var(--ca-soft);
  color: var(--ca);
}
.entry-icon svg {
  width: 20px;
  height: 20px;
}

.entry-title {
  margin-top: 15px;
  font-size: 16.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.entry-desc {
  margin: 7px 0 16px;
  color: var(--muted);
  font-size: 13.5px;
  line-height: 1.75;
}

.entry-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  /* margin-top: auto 把这一行压到卡片底部，两张卡高度一致时也对齐。
     desc 上那个 margin-bottom 保证内容多的那张卡也还剩得下 16px 间距 */
  margin-top: auto;
  padding-top: 15px;
  border-top: 1px solid var(--line);
  color: #929aad;
  font-size: 11.5px;
  letter-spacing: 0.02em;
}

.entry-meta .arrow {
  flex: none;
  color: var(--blue);
  font-size: 13px;
}

/* ------------------------------------------------------------------ 页脚 */

.foot {
  position: relative;
  z-index: 1;
  /* auto 让页脚在长屏上落到最底；内容撑满时它退化成 0，
     间距全部由 padding-top 兜住，两种情况都不会贴住上面 */
  margin-top: auto;
  padding-top: clamp(44px, 8vh, 88px);
  border-top: 1px solid var(--line);
}

.foot-inner {
  width: 100%;
  max-width: 920px;
  margin: 0 auto;
  padding-inline: clamp(20px, 5vw, 52px);
}

.foot-cols {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 30px;
  padding-bottom: clamp(30px, 5vh, 44px);
}

.col h3 {
  margin: 0 0 12px;
  color: var(--ink);
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.col a {
  display: block;
  margin-bottom: 9px;
  color: var(--muted);
  font-size: 13px;
  text-decoration: none;
  transition: color 0.18s ease;
}
.col a:hover {
  color: var(--blue);
}

.col p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.75;
}

.foot-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  padding: 16px 0 22px;
  border-top: 1px solid var(--line);
  color: #929aad;
  font-size: 12px;
}

/* -------------------------------------------------------------- 入场动画 */

/*
 * 用 backwards 而不是 forwards：
 * forwards 会让动画结束时的 transform 一直生效，把 :hover 的位移顶掉；
 * backwards 只在 delay 期间保持起始态，动画一结束就交还给正常样式。
 */
.enter {
  animation-name: rise;
  animation-duration: 0.7s;
  animation-timing-function: cubic-bezier(0.16, 0.84, 0.44, 1);
  animation-fill-mode: backwards;
  animation-delay: var(--d, 0ms);
}

@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* ---------------------------------------------------------------- 响应式 */

@media (max-width: 768px) {
  .entries {
    grid-template-columns: minmax(0, 1fr);
  }
  .foot-cols {
    grid-template-columns: minmax(0, 1fr);
    gap: 24px;
  }
  /* 窄屏放不下两个次级链接，顶栏只留品牌标 —— 它们页脚里都有 */
  .nav-side {
    display: none;
  }
}

/* 触屏没有光标，高亮层永远等不到 --mx/--my，索性不渲染 */
@media (hover: none) {
  .dots-hi {
    display: none;
  }
}
</style>
