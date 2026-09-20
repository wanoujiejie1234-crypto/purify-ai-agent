import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址集中在这里改：只需要动下面这一处，前端代码里一律用相对路径 /api/...
const BACKEND = 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  build: {
    // 产物直接落到 Spring Boot 的静态资源目录，由后端当普通静态文件托管。
    // 这样部署时只有 8080 一个端口、也不存在跨域 —— 前端代码里的相对路径 /api/...
    // 在开发（Vite 代理）和生产（同源）两种情形下都成立，不需要按环境切换 baseURL。
    outDir: '../src/main/resources/static',
    // 每次构建先清空：不清的话，删掉的路由对应的旧 chunk 会一直留在那里，
    // 后端照样会把它发出去，看起来像「改了没生效」
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: BACKEND,
        changeOrigin: true,
        // 为什么必须走代理而不是让浏览器直连 8080：
        // 1. 绕开跨域 —— 代理之后前后端在浏览器眼里是同源的；
        // 2. 能读到 X-Chat-Id 这个自定义响应头 —— 跨域时浏览器默认不允许 JS 读它。
        //
        // 关于 SSE：http-proxy 对 text/event-stream 是直通的，Vite 的 dev server
        // 默认也不做 gzip 压缩，所以后端吐一个字就会立刻转发到浏览器，不会有攒包。
        // 如果哪天发现回答是「憋一大段突然全出来」，优先怀疑这里被加了压缩中间件。
        ws: false,
      },
    },
  },
})
