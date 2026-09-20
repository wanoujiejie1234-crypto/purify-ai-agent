import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'

const routes = [
  { path: '/', name: 'home', component: HomeView },
  // 两个聊天室都拆成异步 chunk：主页是纯静态的入口，不该被聊天页的代码拖慢首屏
  { path: '/slim', name: 'slim', component: () => import('../views/SlimView.vue') },
  { path: '/manus', name: 'manus', component: () => import('../views/ManusView.vue') },
  // 知识库管理。和聊天页一样异步加载：它是一个独立的工具页，
  // 不该让只想聊天的人把它那份代码也下载下去
  { path: '/knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue') },
  // 兜底：手输错地址时别给一个白屏
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export default createRouter({
  history: createWebHistory(),
  routes,
})
