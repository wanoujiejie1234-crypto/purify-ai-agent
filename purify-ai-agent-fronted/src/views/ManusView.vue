<script setup>
import ChatRoom from '../components/ChatRoom.vue'
import { MANUS } from '../chatConfig.js'
</script>

<template>
  <!-- agent 打开后会多出工具调用过程折叠区，以及 WAITING_FOR_USER / ABORTED 的视觉区分 -->
  <ChatRoom v-bind="MANUS" />
</template>
