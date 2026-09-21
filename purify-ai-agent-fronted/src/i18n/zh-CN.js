/**
 * 中文文案。默认语言，也是缺键时的回退语言。
 *
 * 分组的划分跟着**页面/组件**走，不跟着词性走：找一个句子的改法时，
 * 人想的是「它在哪个页面上」，而不是「它是一个名词还是动词」。
 *
 * 这里**不翻**三类东西，它们是数据不是展示（后端也有一份同样的清单）：
 * 知识库分类（食物热量/运动热量/药物，要原样发给后端做等值过滤）、
 * 用户活动水平（SEDENTARY 那些枚举标签，后端按标签匹配）、
 * 以及品牌名（Purify AI / PurifyManus）。
 * 把分类翻掉的表现特别隐蔽：界面看着没问题，但已入库的文档一份都检索不到。
 */
export default {
  common: {
    loading: '加载中…',
    refresh: '刷新',
    close: '关闭',
    save: '保存',
    saving: '保存中…',
    saved: '已保存',
    delete: '删除',
    download: '下载',
    cancel: '取消',
  },

  /* ------------------------------------------------------------ 用户菜单 */

  menu: {
    login: '登录',
    admin: '超级管理员',
    knowledge: '知识库',
    resources: '资料库',
    settings: '设置',
    logout: '退出登录',
  },

  /* ---------------------------------------------------------------- 首页 */

  home: {
    navManus: 'PurifyManus',
    navKnowledge: '知识库',
    chip: 'Spring AI 驱动 · 一条会聊天，一条会干活',
    slogan: '先查清楚，再回答',
    lede: '轻语答健康问题前会先翻一遍知识库，PurifyManus 动手前会先查资料。翻不到就说翻不到，不编。',
    cta: '开始对话',
    ctaNote: '与轻语对话 · 健康瘦身顾问，回答前先查知识库',
    entries: '其他入口',
    footChat: '对话链路',
    footKnowledge: '知识库',
    footDocs: '文档管理',
    footSearch: '检索自检',
    footAbout: '关于',
    footAboutText: '前端 Vue 3 + Vite，后端 Spring Boot + Spring AI，向量检索走 pgvector。',
    footDeploy: '本地部署 · 单端口 8080',
    cardSlim: '健康瘦身顾问。回答之前先翻一遍知识库，翻不到就说翻不到，不编。',
    cardSlimMeta: '知识库检索 · 一问一答',
    cardManus: '能自己查资料、调工具、写文件，一步步把任务做完。信息不够时会停下来问你。',
    cardManusMeta: '工具调用 · MCP · 循环自检',
    cardKnowledge: '上传文档建索引，看每份文档切成了几片，再用一句话试着检索一次 —— 对话里查的就是它。',
    cardKnowledgeMeta: '文档索引 · 向量检索 · 检索自检',
  },

  /* ------------------------------------------------- 登录 / 注册 / 找回 */

  auth: {
    shellBack: '返回首页',

    login: {
      title: '登录',
      subtitle: '登录后才能开始对话、查看自己的历史记录。',
      username: '用户名',
      usernamePlaceholder: '请输入用户名',
      password: '密码',
      passwordPlaceholder: '请输入密码',
      forgot: '忘记密码？',
      needUsername: '请输入用户名',
      needPassword: '请输入密码',
      failed: '登录失败，请稍后重试。',
      submitting: '登录中…',
      submit: '登录',
      noAccount: '还没有账号？',
      toRegister: '去注册',
    },

    register: {
      title: '创建账号',
      subtitle: '需要一个能收信的邮箱来完成验证。',
      email: '邮箱',
      code: '验证码',
      codePlaceholder: '6 位数字',
      username: '用户名',
      usernamePlaceholder: '3-20 个字符',
      password: '密码',
      passwordPlaceholder: '至少 8 位',
      confirm: '确认密码',
      confirmPlaceholder: '再输一遍',
      mismatchHint: '两次输入不一致',
      mismatch: '两次输入的密码不一样',
      passwordTooShort: '密码至少 8 位',
      failed: '注册失败，请稍后重试。',
      submitting: '注册中…',
      submit: '注册并登录',
      hasAccount: '已经有账号了？',
      toLogin: '去登录',
    },

    forgot: {
      title: '找回密码',
      subtitle: '验证码会发到你注册时用的邮箱。',
      done: '密码已经改好了。用新密码登录即可。',
      toLogin: '去登录',
      email: '注册邮箱',
      code: '验证码',
      codePlaceholder: '6 位数字',
      newPassword: '新密码',
      newPasswordPlaceholder: '至少 8 位',
      confirm: '确认新密码',
      confirmPlaceholder: '再输一遍',
      mismatchHint: '两次输入不一致',
      mismatch: '两次输入的密码不一样',
      passwordTooShort: '密码至少 8 位',
      failed: '重置失败，请稍后重试。',
      submitting: '提交中…',
      submit: '重置密码',
      remember: '想起来了？',
    },

    // 「填邮箱 → 发验证码 → 倒计时」这段交互的共用文案
    code: {
      resendIn: '{n}s 后重发',
      sending: '发送中…',
      send: '获取验证码',
      needEmail: '请先填写邮箱',
      sent: '验证码已发送，请查收邮箱。',
      failed: '发送失败，请稍后重试。',
    },
  },

  /* -------------------------------------------------------------- 对话页 */

  chat: {
    // 品牌名。英文用罗马字（Qingyu）而不是留中文：对英文用户来说
    // 一个认不出的方块字比一个能读出来的名字糟得多。
    // PurifyManus 本来就是拉丁字母，两边一样
    slim: {
      title: '轻语',
      welcome: '今天想聊点什么？',
      intro: '我是轻语。先用一句话告诉我你的情况——身高体重、日常活动量、想达到什么目标，我再说具体怎么安排。',
      examples: [
        '我 175、80 公斤，久坐办公，想减到 70 公斤，该怎么安排？',
        '每天走一万步够吗？',
        '减脂期早餐吃什么比较扛饿？',
        '最近总想吃甜的，有什么办法吗？',
      ],
      placeholder: '说说你的情况或直接提问…',
    },

    manus: {
      title: 'PurifyManus',
      welcome: '把任务交给我',
      intro: '我能查天气、搜资料、读写文件，也能自己判断下一步该做什么。信息不够时我会停下来问你，不会瞎猜。',
      examples: [
        '杭州今天适合跑步吗？适合的话帮我写成文件存下来',
        '帮我查一下从杭州东站到西湖怎么走，顺路看看沿途天气',
        '帮我找几家附近评分高的轻食店，整理成一份清单',
        '我想安排这周的运动计划，你先问我几个问题',
      ],
      placeholder: '给我一个任务，越具体越好…',
    },

    side: {
      backHome: '返回主页',
      expand: '展开侧边栏',
      collapse: '收起侧边栏',
      switchTo: '切换到 {name}',
      switchLabel: '切换到',
      newChat: '新会话',
      sessions: '会话列表',
      noSessions: '还没有历史会话',
      rename: '重命名',
      delete: '删除',
      deleteConfirm: '删除会话「{title}」？这段对话的记录会一起删掉。',
    },

    trace: {
      // 英文单复数由 vue-i18n 按 n 自动选，中文两档一样
      steps: '过程 {n} 步',
      summary: '这一轮共走了 {n} 步（过程明细未入库，看日志或当次对话可见）',
      type: {
        step: '步骤',
        toolCall: '调用',
        toolResult: '返回',
        loopSignal: '自检',
        retrieval: '检索',
      },
    },

    badge: {
      failed: '生成失败',
      stopped: '已停止',
      aborted: '已中止',
      // 不写「已拦截」这种像报错的说法：用户看到的应该是顾问在关心他
      blocked: '已转为安全提示',
      waiting: '等待你的回答',
    },

    hint: {
      waiting: '它在等你回答，直接接着说就行。',
      aborted: '任务被中止了（触发循环保护）。可以把要求拆小一点再试。',
    },

    composer: {
      stop: '停止',
      send: '发送',
      tip: 'Enter 发送，Shift + Enter 换行',
    },

    history: {
      gone: '这个会话已经不存在了，已为你开了个新会话。',
    },

    fallbackAnswer: '生成失败。',
  },

  /* -------------------------------------------------------------- 设置 */

  settings: {
    title: '设置',
    avatar: {
      title: '头像',
      upload: '更换头像',
      uploading: '上传中…',
      note: '支持 png / jpg / webp / gif，不超过 2MB。',
      failed: '头像上传失败，请稍后重试。',
    },
    appearance: {
      title: '外观',
      light: '白天',
      dark: '黑夜',
      note: '深色目前只作用于对话页。首页、知识库和登录页仍是浅色。',
    },
    language: {
      title: '语言',
      note: '界面文案和错误提示都会跟着切换。',
    },
    profile: {
      title: '我的情况',
      needLogin: '登录后可以在这里填写，轻语会照着它给建议。',
      intro: '填一次就会记住，之后每个会话都能用。也可以直接在对话里说，两边是同一份数据。',
      age: '年龄',
      agePlaceholder: '例如 30',
      height: '身高（cm）',
      heightPlaceholder: '例如 170',
      weight: '体重（kg）',
      weightPlaceholder: '例如 71.5',
      bmi: 'BMI',
      goal: '减重目标',
      goalPlaceholder: '例如 三个月减到 65 公斤',
      activity: '日常活动水平',
      activityEmpty: '未填写',
      diet: '饮食偏好',
      dietPlaceholder: '例如 爱吃面食 / 素食',
      avoid: '忌口或过敏',
      avoidPlaceholder: '例如 海鲜过敏 / 乳糖不耐受',
      clearNote: '清空某一项再保存就会把它删掉。',
      loadFailed: '画像加载失败，请稍后重试。',
      saveFailed: '保存失败，请稍后重试。',
    },
  },

  /* ------------------------------------------------------------ 资料库 */

  resources: {
    title: '资料库',
    empty: '还没有内容。',
    emptyHint: '让 PurifyManus 生成 PDF、下载资源或写文件，产出会归档到这里。',
    source: '来源：{url}',
    deleteConfirm: '删除「{title}」？文件本身也会一起删掉。',
    loadFailed: '资料库加载失败，请稍后重试。',
    downloadFailed: '下载失败，请稍后重试。',
    deleteFailed: '删除失败，请稍后重试。',
  },

  /* ------------------------------------------------------------ 知识库 */

  knowledge: {
    title: '知识库',
    backHome: '返回主页',
    overview: {
      title: '概览',
      chunks: '切片总数',
      documents: '文档份数',
      empty: '知识库还是空的。上传几份文档之后，对话才会开始查它。',
      loadFailed: '读取概览失败',
    },
    upload: {
      title: '上传建索引',
      desc: '支持 txt / md。同一文件名重复上传会覆盖旧切片。选一整个目录可以批量导入。',
      category: '分类',
      processing: '处理中…',
      pick: '选择文件',
      pickDir: '导入整个目录',
      preview: '预览切片',
      previewing: '预览中…',
      // {source} 和 {count} 是后端返回的文件名和切片数
      single: '「{source}」已入库：{count} 个切片',
      batch: '导入完成：成功 {ok} 份，失败 {failed} 份',
      // 有失败就把原因摆出来，不要只报一个数字
      // ——「3 份失败」对排查毫无帮助，而失败原因往往一眼就能看懂
      failureItem: '{name}：{message}',
      failed: '上传失败',
    },
    preview: {
      heading: '预览：{source} → 会切成 {count} 片，共 {chars} 字符',
      truncated: '下面只列了前 {count} 片。',
      chars: '{n} 字',
      failed: '预览失败',
    },
    docs: {
      title: '文档（{n}）',
      prev: '上一页',
      next: '下一页',
      loading: '读取中…',
      empty: '还没有文档。',
      source: '来源',
      category: '分类',
      chunks: '切片',
      characters: '字符',
      uploadedAt: '入库时间',
      loadFailed: '读取文档列表失败',
      deleteConfirm: '删除「{source}」的全部切片？',
      deleteFailed: '删除失败',
    },
    search: {
      title: '检索自检',
      desc: '拿一句话真跑一次检索，看看会不会查、查出什么、以及最终会拼进 Prompt 的原文。这个操作只读，可以随便试。',
      placeholder: '例如：一碗米饭的热量是多少千卡',
      running: '检索中…',
      submit: '检索',
      failed: '检索失败',
      // 这两句要把「压根没查」和「查了没命中」分开说清楚 ——
      // 答得不对时，这两种情况的排查方向完全不同
      skipped: '没有发起检索',
      skippedDetail: ' —— 路由判定这句话与知识库无关。这是「压根没查」，不是「查了没命中」。想让这类问题也走检索，把 purify.rag.router.query-all-when-unmatched 改成 true。',
      retrieved: '已检索',
      retrievedDetail: '：命中 {count} 条，耗时 {elapsed}ms，分类过滤：{categories}',
      allCategories: '全库',
      rawSummary: '会拼进 Prompt 的原文',
      // 关键词那一路的观测。「提取到的检索词」是这里最值钱的一行：
      // 拆词规则是启发式的，而它把问题拆成了什么，直接决定这一路能不能命中
      keywordTerms: '提取到的检索词：{terms}',
      keywordUnavailable: '关键词那一路没在工作：{reason}。这次检索是纯向量的，专有名词的精确匹配会差一些。',
      rankVector: '向量 #{n}',
      rankKeyword: '关键词 #{n}',
    },

    // ---------------------------------------------------------- 百炼同步
    // 把百炼云知识库里已经切好的切片搬到本地向量库。
    // 注意「未同步 / 已同步 / 百炼侧已更新 / 同名冲突」四态各有一句：
    // 它们对用户要做的动作完全不同，压成一句「状态」就没法用了。
    bailian: {
      title: '百炼同步',
      desc: '把百炼云知识库里已经切好的切片搬到本地向量库。切片用百炼的，向量化在本地做，检索链路不变。同步过的文档可以重复同步，会覆盖本地同名的来源。',
      reload: '重新拉取',
      loadFailed: '读取百炼文件清单失败',
      notConfigured: '百炼同步还没配置好，缺这几项：{keys}。补在 application-local.yml 里，然后重新拉取。',
      connected: '当前对接：{name} · IndexId={indexId}',
      filter: '筛选',
      filterFinish: '只看已完成',
      filterAll: '全部状态',
      selectAll: '全选未同步',
      syncing: '同步中…',
      syncSelected: '同步选中 ({n})',
      loading: '读取中…',
      empty: '百炼那边没有符合条件的文档。',
      prev: '上一页',
      next: '下一页',
      expand: '展开切片',
      collapse: '收起',
      columns: {
        name: '文件名',
        status: '百炼状态',
        size: '大小',
        gmtModified: '百炼更新时间',
        local: '本地',
      },
      state: {
        missing: '未同步',
        synced: '已同步（{chunks} 片）',
        stale: '百炼侧已更新',
        // 唯一一个「点了会造成破坏」的状态：同步会覆盖掉本地那份手传的。
        // 所以它要能被一眼认出来，而不是混在「未同步」里
        conflict: '⚠ 同名（本地已有）',
      },
      chunks: {
        heading: '切片预览：{name} —— 共 {count} 片，本页 {from}-{to}',
        loading: '读取切片中…',
        failed: '读取切片失败',
        fromMetadata: '分类来自百炼元数据：{category}',
        needPick: '百炼没给这份文档标分类，请选一个用于整份文档：',
        mixed: '这份文档的切片标了多个分类，无法自动确定，请选一个用于整份文档：',
        category: '分类',
        empty: '百炼侧这份文档还没有切片，它当前的状态是 {status}，等它解析完再同步。',
        chars: '{n} 字',
      },
      syncOne: '只同步这一份',
      noSelection: '请至少勾选一份要同步的文档',
      needCategory: '这几份还没选分类：{names}。展开一份、选好分类再同步。',
      conflictConfirm: '本地已有一份同名文档「{name}」，它是本地上传的。继续会用百炼的切片覆盖它，确定吗？',
      conflictConfirmBatch: '选中的文档里有几份与本地已有来源同名：{names}。继续会用百炼的切片覆盖它们，确定吗？',
      timeoutHint: '同步进行中，请不要关闭页面。超时的话重新拉取看看已经同步了多少份——重复同步是安全的。',
      result: {
        batch: '同步完成：成功 {ok} 份，失败 {failed} 份',
        failureItem: '{name}：{message}',
        failed: '同步失败',
      },
    },
  },

  /* -------------------------------------------------------------- 报错 */

  error: {
    // 这两条和后端的 UNAUTHORIZED / FORBIDDEN 是同一件事的前端副本：
    // 后端会先说一遍，这里是「连后端都没连上」时的兜底
    unauthorized: '登录已失效，请重新登录。',
    forbidden: '这个功能只对超级管理员开放。',
    timeout: '请求超时，请稍后重试。',
    // 开发时最常见的一种：后端没起来。写清楚是哪个地址，比 "Network Error" 有用一百倍
    offline: '连不上后端服务（http://localhost:8080），请确认 Spring Boot 已经启动。',
    notFound: '请求的资源不存在（404）。',
    http: '请求失败（HTTP {status}）',
    code: '请求失败（{code}）',
    generic: '请求失败，请稍后重试。',
    streamUnsupported: '当前浏览器不支持流式读取（response.body 为空）。',
  },
}
