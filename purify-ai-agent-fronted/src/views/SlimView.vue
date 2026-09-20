<script setup>
import ChatRoom from '../components/ChatRoom.vue'
import { SLIM } from '../chatConfig.js'
</script>

<template>
  <!-- 轻语这条链路和智能体共用聊天室，只是换接口、换主题色、不展示工具过程 -->
  <ChatRoom v-bind="SLIM" />
</template>
