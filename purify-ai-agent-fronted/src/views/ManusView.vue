<script setup>
import ChatRoom from '../components/ChatRoom.vue'
import { MANUS } from '../chatConfig.js'
</script>

<template>
  <!-- agent 打开后会多出工具调用过程折叠区，以及 WAITING_FOR_USER / ABORTED 的视觉区分。
       标题、欢迎语、示例问题、输入框提示这些文案**不在 props 里**：
       它们由 ChatRoom 按 link 去语言包里取，见那边的 title/welcome/examples。
       放在这里的话就没有响应式可言了——一个普通对象是切语言时不会重算的 -->
  <ChatRoom :link="MANUS.link" :accent="MANUS.accent" :agent="MANUS.agent" />
</template>
