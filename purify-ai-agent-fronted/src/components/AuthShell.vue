<script setup>
import AppIcon from './AppIcon.vue'

/**
 * 登录 / 注册 / 找回密码三个页面共用的外壳：品牌标 + 一张卡片 + 底部链接区。
 *
 * 抽出来的理由不是省代码，是**保证三个页面长得一样**。分开写的话，
 * 改一处间距另外两处不会跟着变，而用户是在这三个页面之间来回跳的
 * （登录 → 忘了密码 → 回来登录 → 去注册），一点点不一致都会被看出来。
 *
 * 配色只用 `--brand` 那一套（电光蓝纯色），和首页保持一致：柔和的径向底、
 * 白卡片、没有渐变标题、没有渐变按钮。
 */
defineProps({
  /** 卡片标题，比如「登录」「创建账号」。 */
  title: { type: String, required: true },
  /** 标题下面那句说明，交代这个页面是干什么的。 */
  subtitle: { type: String, default: '' },
})
</script>

<template>
  <div class="auth">
    <!-- 背景层：顶部一层淡蓝 + 点阵，和首页同一套语言。
         纯装饰，对读屏隐藏 -->
    <div class="bg" aria-hidden="true">
      <span class="wash"></span>
      <span class="dots"></span>
    </div>

    <header class="top">
      <RouterLink to="/" class="brand">
        <span class="mark"><AppIcon name="drop" /></span>
        <span class="brand-name">Purify AI</span>
      </RouterLink>
      <RouterLink to="/" class="back">返回首页</RouterLink>
    </header>

    <main class="panel">
      <div class="card">
        <h1 class="title">{{ title }}</h1>
        <p v-if="subtitle" class="subtitle">{{ subtitle }}</p>

        <!-- 表单由各个页面自己提供：字段和步骤都不一样，硬抽成 props 只会更难读 -->
        <slot />

        <p v-if="$slots.footer" class="footer">
          <slot name="footer" />
        </p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.auth {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
  background: #fbfcfe;
  color: var(--ink);
}

/* ---------------------------------------------------------------- 背景层 */

.bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}

.wash {
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse 62% 40% at 50% -6%, rgba(77, 107, 254, 0.13), transparent 70%);
}

.dots {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(circle, rgba(10, 15, 30, 0.08) 1px, transparent 1.2px);
  background-size: 30px 30px;
  /* 只在上半部分铺一点：整页铺满会让卡片周围太吵 */
  -webkit-mask-image: linear-gradient(#000 0%, #000 42%, transparent 78%);
  mask-image: linear-gradient(#000 0%, #000 42%, transparent 78%);
}

/* ------------------------------------------------------------------ 顶栏 */

.top {
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
  background: var(--brand);
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

.back {
  margin-left: auto;
  color: var(--muted);
  font-size: 13px;
  text-decoration: none;
  transition: color 0.18s ease;
}
.back:hover {
  color: var(--brand);
}

/* ------------------------------------------------------------------ 卡片 */

.panel {
  position: relative;
  z-index: 1;
  display: flex;
  flex: 1;
  /* 不写 align-items: center，改用 justify-content: flex-start + 上边距：
     居中会让「注册」那张比「登录」高的卡片在不同页面跳到不同的垂直位置，
     三个页面来回切换时标题会上下乱动 */
  justify-content: center;
  padding: clamp(12px, 4vh, 48px) clamp(20px, 5vw, 52px) 56px;
}

.card {
  width: 100%;
  max-width: 400px;
  height: fit-content;
  padding: 30px 30px 26px;
  border: 1px solid var(--line);
  border-radius: 18px;
  /* 半透明白 + 毛玻璃：底下那层点阵能透出来一点，卡片才不像贴上去的 */
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  box-shadow: 0 20px 44px -28px rgba(10, 15, 30, 0.28);
}

.title {
  font-size: 21px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.subtitle {
  margin-top: 8px;
  color: var(--muted);
  font-size: 13.5px;
  line-height: 1.7;
}

.footer {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: 13px;
  text-align: center;
}

/* 三个页面的底部链接样式一致（「还没有账号？去注册」那种）。
   用 :deep 是因为链接是各个页面写进 slot 的，不在这张卡片的 scope 里 */
.footer :deep(a) {
  color: var(--brand);
  font-weight: 500;
  text-decoration: none;
}
.footer :deep(a:hover) {
  text-decoration: underline;
}

@media (max-width: 480px) {
  .card {
    padding: 24px 20px 20px;
    border-radius: 16px;
  }
}
</style>
