import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import * as auth from './auth.js'
import { fetchMe } from './api/auth.js'
import './styles/base.css'

/**
 * 启动顺序：先恢复登录态，再挂载。
 *
 * **必须是这个顺序。** 路由守卫读的是 `auth.js` 里那份内存状态，
 * 如果先挂载再恢复，第一跳导航发生时状态还是「未登录」，
 * 于是直接访问 `/slim` 的用户会被弹到登录页——而他其实是登录着的，
 * 只是状态还没读回来。刷新一下才正常，这种 bug 很难复现也很难解释。
 */
auth.restore()

createApp(App).use(router).mount('#app')

/**
 * 挂载之后再去服务端问一次「这个令牌还有效吗」。
 *
 * `restore()` 只能判过期时间，证明不了令牌有效：签名对不对只有服务端知道，
 * 账号可能已经被删、密钥可能已经换过。主动问一次，既能把失效的令牌清掉，
 * 也能顺便刷新昵称、角色这些会变的信息。
 *
 * **必须先判 `auth.isLoggedIn()`，不能无条件调。** 未登录的访客访问首页时，
 * 这个请求会拿到 401，而 http.js 的拦截器看到 401 就清令牌跳登录页——
 * 于是「打开首页」变成了「被弹到登录页」，而首页本来是公开的。
 * 有令牌才问，这个问题就不存在。
 *
 * **不 await、失败也不管**：它是后台校验，不该让首屏等一次网络往返。
 * 令牌真失效时会被拦截器处理掉（跳登录页并带上当前地址），用户不亏。
 */
if (auth.isLoggedIn()) {
  fetchMe().catch(() => {})
}
