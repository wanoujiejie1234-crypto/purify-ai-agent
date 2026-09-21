import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'

/**
 * 把模型输出的 markdown 渲染成 HTML。
 *
 * 这个文件存在的唯一理由是 XSS，所以两件事必须一起做，缺一不可：
 *
 * 1. markdown-it 关掉 html 选项 —— 模型输出和工具返回值都是**不可信内容**，
 *    允许内联 HTML 等于把「模型写什么，页面就执行什么」这条路打开。
 *    它的输出因此是「只会生成我们自己写的标签」的。
 * 2. 仍然过一遍 DOMPurify —— 第一层是从源头挡住，这一层是兜底。
 *    上一层的假设是「markdown-it 不会生成危险标签」，而这个假设依赖它的版本行为；
 *    真出了绕过（链接协议、SVG、属性注入之类）时，第二层还能拦住。
 *    代价是每次渲染多几毫秒，换来的是「即使上游出问题也不会变成 XSS」。
 *
 * 原先 ChatRoom.vue 里的注释写着「绝不能用 v-html」。这条约束没有被放弃，
 * 而是换了个方式满足：只有在「先关 HTML、再消毒」之后，v-html 才是安全的。
 *
 * 代码高亮用 highlight.js 的 common 子集而不是全量包：
 * 全量包带两百多种语言、体积约 1MB，而这里要显示的基本只有 Java / JS / JSON / SQL /
 * shell 和几段配置，common 已经覆盖。
 */

const md = new MarkdownIt({
  // 见文件头第 1 条。这一行是整个渲染链路安全的前提
  html: false,
  // 单个换行不当段落处理：模型的输出里列表项和短句经常一行一个，
  // 按 CommonMark 的默认行为会被并成一段，读起来更糟
  breaks: true,
  linkify: true,
  highlight(code, language) {
    if (language && hljs.getLanguage(language)) {
      try {
        return hljs.highlight(code, { language, ignoreIllegals: true }).value
      } catch {
        // 高亮失败不该把渲染搞崩，退回纯文本（下面那行）
      }
    }
    // 没标语言、或者语言不认识时自动识别一次
    try {
      return hljs.highlightAuto(code).value
    } catch {
      return ''
    }
  },
})

// 让所有链接在新标签页打开，并切断 opener 引用：
// 不这么做的话，markdown 里写一个链接就能把当前页导航走，
// 而带 target=_blank 又不加 rel 的话，目标页可以通过 window.opener 操作本页
const defaultLinkOpen =
  md.renderer.rules.link_open ||
  ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

const PURIFY_CONFIG = {
  // 只允许这些标签。markdown-it 在 html:false 下本来就只产出这些，
  // 这一层是给「上游哪天被改坏」准备的
  ALLOWED_TAGS: [
    'p', 'br', 'hr', 'strong', 'em', 'del', 's', 'blockquote',
    'ul', 'ol', 'li', 'code', 'pre', 'span',
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'table', 'thead', 'tbody', 'tr', 'th', 'td',
    'a', 'img',
  ],
  ALLOWED_ATTR: ['href', 'title', 'target', 'rel', 'class', 'src', 'alt'],
  // 明确禁掉这些协议。DOMPurify 默认已经挡了 javascript:，写出来是为了让它显式可见
  ALLOWED_URI_REGEXP: /^(?:https?|mailto|tel):/i,
}

/**
 * 把「整个代码段就是一个裸 URL」的写法拆回纯文本。
 *
 * 模型经常把下载链接写成 `https://…`（用反引号包起来），大概是把 URL 当成「技术性内容」
 * 处理了。但反引号在 markdown 里是行内代码：渲染出来是 <code>，等宽字体加底灰，
 * 看着像一段代码，而且**点不动**——用户看到的现象就是「链接没有给出来」。
 *
 * 提示词里已经明确要求过不要包反引号（见 purify-manus-system.st 的「链接怎么给」），
 * 但提示词是软的。这一层是兜底，和本文件顶上那两道 XSS 防线同一个思路：
 * 能挡住的地方先挡，挡不住的地方留一层兜底。
 *
 * 只在「单反引号、且内容整体就是一个 http(s) 地址」时才拆，误伤面很小：
 * 真要展示一段 URL 代码的人会写成三反引号的代码块——那种形式的反引号前面是换行，
 * 匹配不上这条规则。
 */
function unwrapUrlCodeSpans(text) {
  return text.replace(/`(https?:\/\/[^\s`<>]+)`/g, '$1')
}

/**
 * 渲染成可以直接放进 v-html 的 HTML 字符串。
 *
 * @param {string} text 模型的原始输出（markdown 源码）
 */
export function renderMarkdown(text) {
  if (!text) return ''
  return DOMPurify.sanitize(md.render(unwrapUrlCodeSpans(text)), PURIFY_CONFIG)
}

/**
 * 渲染成纯文本，用于侧边栏标题/摘要这类不需要格式的地方。
 */
export function toPlainText(text) {
  if (!text) return ''
  return text.replace(/[#*`>_~\-]/g, '').replace(/\s+/g, ' ').trim()
}
