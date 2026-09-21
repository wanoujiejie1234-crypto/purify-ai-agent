import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import * as auth from './auth.js'
import { fetchMe } from './api/auth.js'
import { i18n, restoreLocale } from './i18n/index.js'
import { restoreTheme, enterChatPage } from './theme.js'
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

/**
 * 主题也要在挂载之前恢复，理由和上面那条一样是「时序」：
 * 放到组件里读的话，深色用户每次刷新都会先看到一帧浅色再跳成深色。
 */
restoreTheme()

/**
 * 语言同理，而且比主题更需要抢在前面：
 * 恢复到挂载之后的话，英文用户每次刷新都会先看到一帧中文，
 * 然后整页文字当着面换一遍——比颜色闪一下显眼得多。
 *
 * 放在 `app.use(i18n)` 之前调没有关系：`restoreLocale` 改的是
 * `i18n.global.locale`，插件装上去读的就是那个已经改好的值。
 */
restoreLocale()

/**
 * 直接落在对话页上的那次刷新，还要再往前抢一帧。
 *
 * 深色只在对话页生效，而 `ChatRoom` 是异步 chunk——挂载它要等 chunk 下载完，
 * 在那之前 `<html>` 上还没有 `theme-dark`，body 是浅色的。用户看到的就是
 * 「刷新 → 闪一下白 → 变深」。ChatRoom 自己的 setup 里虽然也调了
 * `enterChatPage()`，但那时候首帧已经画完了。
 *
 * 这里按路由判断一次（路由表里 `meta.chatTheme` 标了哪几个页面吃深色），
 * 把那一帧也盖住。ChatRoom 里那次调用保留着：它管的是**从别的页面点进来**
 * 的情况，那时候这儿还没跑过。
 *
 * 还要带上登录判断：未登录时对话页会被路由守卫弹到登录页，而那次跳转发生在
 * 挂载之后——不加这一条的话，`theme-dark` 会挂在一个从来没出现过对话页的会话上，
 * 登录页背后那层 body 就变成深色了。
 */
if (auth.isLoggedIn() && router.resolve(window.location.pathname).meta?.chatTheme) {
  enterChatPage()
}

createApp(App).use(i18n).use(router).mount('#app')

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
