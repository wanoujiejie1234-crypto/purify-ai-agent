<script setup>
/**
 * 内联 SVG 图标。
 * 不引图标库：整个项目就四个图标，为它多装一个依赖、多打一份包不划算，
 * 而且内联 SVG 能直接吃 currentColor，跟着主题色走。
 */
defineProps({
  name: { type: String, required: true },
})
</script>

<template>
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <!-- 品牌标：一滴水，取「净化」的字面意思。只用在主页顶栏 -->
    <template v-if="name === 'drop'">
      <path d="M12 2.69 17.66 8.35a8 8 0 1 1-11.31 0Z" />
    </template>

    <!-- 轻语：叶子，取「温和、慢慢长起来」的意思 -->
    <template v-else-if="name === 'leaf'">
      <path d="M11 20A7 7 0 0 1 9.8 6.1C15.5 5 17 4.48 19 2c1 2 2 4.18 2 8 0 5.5-4.78 10-10 10Z" />
      <path d="M2 21c0-3 1.85-5.36 5.08-6C9.5 14.52 12 13 13 12" />
    </template>

    <!-- 知识库：一摞书，取「离线攒下来的资料」的意思 -->
    <template v-else-if="name === 'book'">
      <path d="M4 4.5A2.5 2.5 0 0 1 6.5 2H20v16H6.5A2.5 2.5 0 0 0 4 20.5Z" />
      <path d="M4 20.5A2.5 2.5 0 0 1 6.5 18H20v4H6.5A2.5 2.5 0 0 1 4 19.5Z" />
      <path d="M9 7h7M9 11h5" />
    </template>

    <!-- 智能体：节点网络，取「自己拆步骤、再串起来」的意思 -->
    <template v-else>
      <circle cx="12" cy="5" r="2.4" />
      <circle cx="5" cy="18.5" r="2.4" />
      <circle cx="19" cy="18.5" r="2.4" />
      <path d="M10.5 7.1 6.4 16.2M13.5 7.1l4.1 9.1M7.4 18.5h9.2" />
    </template>
  </svg>
</template>
