# Purify AI · 前端

「轻语」「PurifyManus」两条对话链路 + 知识库管理页。Vue 3 + Vite。

## 启动

```bash
npm install
npm run dev          # http://localhost:5173
```

后端要先在 `http://localhost:8080` 跑起来（**没有 context-path**）。前端一律请求相对路径 `/api/...`，由 Vite 代理转发。

## 构建与部署

```bash
npm run build
```

产物**直接输出到 `../src/main/resources/static/`**（见 `vite.config.js` 的 `build.outDir`），
由 Spring Boot 当静态资源托管。所以生产部署只有 **8080 一个端口**，也不存在跨域：

```
cd purify-ai-agent-fronted && npm run build
cd .. && ./mvnw spring-boot:run        # 打开 http://localhost:8080
```

前端源码里**不写死任何主机名**，相对路径 `/api/...` 在开发（Vite 代理）和生产（同源）
两种情形下都成立，不需要按环境切 `baseURL`。

> 静态产物不入库（见根目录 `.gitignore`）。克隆下来之后要先 `npm run build` 一次，
> 否则 8080 上打开的是空白页。

`npm run preview` 不带代理，一般用不上 —— 要预览构建结果，直接跑 Spring Boot 更接近真实。

## 怎么改后端地址（仅开发）

只改一处 —— `vite.config.js` 顶部的常量：

```js
const BACKEND = 'http://localhost:8080'
```

代理存在的理由有两个：

1. 绕开跨域。代理之后前后端在浏览器眼里是同源的。
2. 能读到 `X-Chat-Id` 这个**自定义响应头**。跨域时浏览器默认不允许 JS 读取它。

> 如果后端换了端口，改完要重启 `npm run dev`（Vite 的代理配置不支持热更新）。

## 目录结构

```
purify-ai-agent-fronted/
├── index.html
├── vite.config.js              # 构建配置（产物落点）+ /api 代理
├── package.json
└── src/
    ├── main.js                 # 应用入口
    ├── App.vue                 # 根组件，只放 <RouterView />
    ├── router/index.js         # 路由：/ 、/slim 、/manus 、/knowledge
    ├── chatConfig.js           # 两条链路的配置：接口名、主题色、文案
    ├── user.js                 # 用户标识（localStorage 里存一个 UUID）
    ├── markdown.js             # markdown 渲染 + 消毒 + 代码高亮
    ├── api/
    │   ├── http.js             # Axios 实例 —— 普通 JSON 请求（历史、会话、知识库）
    │   └── sse.js              # fetch + ReadableStream —— 流式对话
    ├── components/
    │   ├── ChatRoom.vue        # 聊天室（含会话侧边栏），两条链路共用
    │   └── AppIcon.vue         # 内联 SVG 图标
    ├── views/
    │   ├── HomeView.vue        # 主页（纯静态，不请求后端）
    │   ├── SlimView.vue        # 轻语，薄薄一层包装
    │   ├── ManusView.vue       # 智能体，薄薄一层包装
    │   └── KnowledgeView.vue   # 知识库：上传 / 列表 / 预览 / 检索自检
    └── styles/base.css         # 全局变量、重置、减弱动效
```

两条链路共用同一个 `ChatRoom.vue`，靠 `chatConfig.js` 里的 props 区分，而不是复制两份 —— 复制出来的两份迟早会走样。

## 几个关键实现点

### 为什么流式对话用 `fetch` 而不是 Axios

Axios 在浏览器里是 XHR 的封装，只能等响应体全部到齐后一次性交付，读不到中间过程；而 SSE 的价值恰恰是「模型生成一个字，界面就出一个字」。（Axios 的 `responseType: 'stream'` 只在 Node 端有效，浏览器端会被忽略。）

分工是明确的：**普通请求用 Axios，流式对话用 `fetch` + `response.body.getReader()`**。

### 为什么不能用 `EventSource`

它只支持 GET，而这两个接口是 POST。更严重的是它在连接断开时会**自动重连** —— 重连等于把同一句话再发一遍，对智能体就是一次完整的任务重跑，白烧 token 还可能重复执行工具。

### 收尾事件是「覆盖」不是「追加」

`FINAL` / `QUESTION` / `ERROR` 里的 `text` 是权威的完整内容，到达时**直接覆盖**已累积的文本。智能体跑多步时，中途那些 `TEXT` 只是过程不是最终答案，写成追加会让最后一句话被拼两遍。

### `TextDecoder` 必须带 `{ stream: true }`

中文在 UTF-8 里占 3 个字节，网络分块完全可能正好切在字符中间。逐块独立解码的话，每个分块边界都会吐出一个乱码字符。

### markdown 渲染：`v-html` 只在两道处理之后才允许

早先这里写的是「一律不用 `v-html`」。那条约束没有被放弃，而是换了实现方式，见 `markdown.js`：

1. **markdown-it 关掉 `html` 选项** —— 模型输出和工具返回值都是不可信内容，允许内联 HTML 等于把「模型写什么、页面就执行什么」这条路打开。关掉之后，产出的标签只会是渲染器自己生成的。
2. **再过一遍 DOMPurify** —— 第 1 步的安全依赖「markdown-it 不会生成危险标签」这个假设，而假设依赖上游版本的行为。第二层是兜底，代价是几毫秒。

用户自己发的消息仍然按纯文本显示（`white-space: pre-wrap`）：那是用户自己打的字，渲染成 markdown 只会让一句带星号的话突然变成斜体，反而失真。

### 会话列表与用户标识

- **会话 id** 存在 `sessionStorage`：新开标签页就该是一个新会话。
- **用户标识** 存在 `localStorage`：它代表「这个人」，换了就等于所有历史会话从侧边栏消失，不该因为开个新标签页就发生。

`X-User-Id` 由 `http.js` 的请求拦截器和 `sse.js` 各带一次 —— 后者绕过了 axios，拦截器管不到它。少带一处不会报任何错，表现只是「会话列表少了几条」。

> 这个值前端能随便伪造，所以它做到的是「同一个浏览器的会话归到一起」，**不是真正的隔离**。接上登录之后，把它换成登录态里的用户 ID 即可，后端表结构不用动。

### 过程区里的「检索」那一条

`RETRIEVAL` 事件是开场预检索的结果，**跳过也会发**。「没查知识库」和「查了没命中」的排查方向完全不同，混在一起就分不出来了。

## 主页的视觉

版式对齐 [DeepSeek 官网](https://www.deepseek.com)：顶栏（品牌标 + 次级链接）→ 居中标语 + 一颗主按钮
→ 次级入口卡 → 分栏页脚。配色取官网那套品牌色：主色 `#4D6BFE`，深一档 `#4166D5`，
正文色用它的深色底 `#0A0F1E`。

几处刻意的选择：

- **标题是纯色，不是渐变。** 渐变标题（尤其青绿 → 紫那一挂）是最快让人看出「AI 生成的落地页」的一招。
  主按钮的投影也用主色本身（`rgba(77,107,254,…)`）而不是黑色 —— 蓝底要在白底上浮起来，同色系投影比压一层灰自然。
- **点阵网格**：30px 一格（官网是 90px，但那是整屏宽的页面；这块内容不到 1000px 宽，走 90 会稀得看不出是网格），
  向下渐隐。光标附近的那一圈点会亮成蓝色：`mousemove` 只写 `--mx/--my` 两个 CSS 变量，
  实际渲染交给 `mask` 上的 `radial-gradient` —— 变的只是合成层的遮罩位置，不触发重排。
  高亮层**嵌套**在底色层里而不是并列，这样父层的「向下渐隐」和本层的「光标径向」两个遮罩会自动相乘，
  不用去写 `mask-composite`。两层的 `background-size` 必须一致，否则点会错开半个格子、看起来像重影。
- **卡片 hover 只上移 2px**：官网的卡片几乎不动，靠描边变蓝给反馈。位移一大就又是那种「飘」的落地页了。
- **每张入口卡只在图标底上用它那条链路的主题色**（`SLIM.accent` / `MANUS.accent`），页面主色调始终是蓝的。
  那个色块的作用是「提醒你这条链路在聊天页里是什么颜色」，不是拿来做整页配色。

入场动画用 `animation-fill-mode: backwards` 而不是 `forwards` —— `forwards` 会让动画结束时的 `transform` 一直生效，把 `:hover` 的位移顶掉。

`prefers-reduced-motion: reduce` 时全部动画关闭直接显示（见 `styles/base.css`）。

> 主页和三个应用页现在都是浅色底，连成一片。`index.html` 的 `color-scheme` 声明为 `light`，
> 让滚动条、下拉框这些原生控件跟着走，浅色控件不会突然落在一片深色上。
>
> 改主页时**没有动** `chatConfig.js` 里的 `MANUS.accent`（`#6366f1`），
> 所以 PurifyManus 的聊天页气泡仍是靛紫。主页和聊天页在这个色上是有意不一致的 ——
> 要统一的话，改那一处即可，聊天页会跟着变。

## 接口契约

| 用途 | 方法 | 路径 |
|---|---|---|
| 轻语发消息 | POST | `/api/slim/chat` |
| 智能体发消息 | POST | `/api/manus/chat` |
| 轻语历史 | GET | `/api/slim/history?chatId=xxx` |
| 智能体历史 | GET | `/api/manus/history?chatId=xxx` |
| 会话列表 | GET | `/api/sessions?entry=slim\|manus` |
| 会话改名 | PUT | `/api/sessions/{id}?title=xxx` |
| 会话删除 | DELETE | `/api/sessions/{id}` |
| 知识库概览 | GET | `/api/knowledge/stats` |
| 文档列表 | GET | `/api/knowledge/documents?page=&size=` |
| 上传建索引 | POST | `/api/knowledge/documents`（multipart：`file` + `classification`） |
| 批量导入 | POST | `/api/knowledge/documents/batch`（multipart：`files` + `classification`） |
| 索引前预览 | POST | `/api/knowledge/preview`（multipart：`file` + `classification`） |
| 删除文档 | DELETE | `/api/knowledge/documents?source=xxx` |
| 检索自检 | GET | `/api/rag/search?q=xxx` |

发消息请求体 `{ chatId, message }`，响应 `text/event-stream`，响应头带 `X-Chat-Id`。
除发消息外，所有请求都带 `X-User-Id`。

事件类型：`STEP` / `TEXT` / `TOOL_CALL` / `TOOL_RESULT` / `LOOP_SIGNAL` / `RETRIEVAL`（过程），
`FINAL` / `QUESTION` / `ERROR`（收尾，`state` 分别为 `FINISHED` / `WAITING_FOR_USER` / `ERROR`，被中止时 `FINAL` 的 `state` 是 `ABORTED`）。

历史记录是 JSON 数组、按时间正序。`state` 只有智能体链路有值，轻语是 `null`；
`steps` 是**一个整数**（这一轮走了几步），过程的明细不在 `chat_record` 里，只有当次对话的 SSE 事件里有。
会话不存在返回 `[]`，属正常结果。
