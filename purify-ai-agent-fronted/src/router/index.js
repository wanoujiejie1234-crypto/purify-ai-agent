import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import * as auth from '../auth.js'

/**
 * 路由表。
 *
 * 主页是公开的（未登录也能看），三个功能页都要登录：没有登录态就用不了，
 * 这是需求定下来的规则，所以守卫把整页挡在门外，而不是「进去了但发不了消息」——
 * 后者会让人以为功能坏了。
 *
 * 知识库多一道 `requiresAdmin`：只有超级用户能看到。
 */
const routes = [
  { path: '/', name: 'home', component: HomeView },

  // 认证页面。同样异步加载：未登录的人第一眼看到的是主页，
  // 不该为了一个可能用不上的登录页把它的代码也塞进首屏
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { guestOnly: true } },
  { path: '/register', name: 'register', component: () => import('../views/RegisterView.vue'), meta: { guestOnly: true } },
  { path: '/forgot', name: 'forgot', component: () => import('../views/ForgotPasswordView.vue'), meta: { guestOnly: true } },

  // 两个聊天室都拆成异步 chunk：主页是纯静态的入口，不该被聊天页的代码拖慢首屏
  { path: '/slim', name: 'slim', component: () => import('../views/SlimView.vue'), meta: { requiresAuth: true } },
  { path: '/manus', name: 'manus', component: () => import('../views/ManusView.vue'), meta: { requiresAuth: true } },

  // 知识库管理。和聊天页一样异步加载，而且只有超级用户进得来
  {
    path: '/knowledge',
    name: 'knowledge',
    component: () => import('../views/KnowledgeView.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
  },

  // 兜底：手输错地址时别给一个白屏
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 守卫。
 *
 * 它读的是 `auth.js` 里那份内存状态，**不发网络请求**——登录态在应用启动时
 * （`main.js`）已经从 localStorage 恢复过了，令牌是否真的有效由后端在第一次
 * 请求时判定。守卫里再问一次服务端会让每次导航都卡一下，而它并不能更早发现问题。
 */
router.beforeEach((to) => {
  // 已登录的人不该再看到登录页/注册页：他大概是从某个旧书签点进来的，
  // 让他填一遍表单再被告知「你已经登录了」很别扭
  if (to.meta.guestOnly && auth.isLoggedIn()) {
    return { path: '/' }
  }

  if (to.meta.requiresAuth && !auth.isLoggedIn()) {
    // 带上原地址，登录成功后跳回去。没有它的话，用户点某个链接被拦下、
    // 登录完回到首页，还得自己再找一遍刚才想去的地方
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // 注意这里**只是界面上的拦**。真正的权限控制在后端的 @RequireAdmin——
  // 用户手工敲 /knowledge 进来，页面会渲染，但每个接口都返回 403，
  // 所以看到的是一堆「需要超级管理员权限」的提示，而不是知识库内容
  if (to.meta.requiresAdmin && !auth.isAdmin()) {
    return { path: '/' }
  }

  return true
})

export default router
