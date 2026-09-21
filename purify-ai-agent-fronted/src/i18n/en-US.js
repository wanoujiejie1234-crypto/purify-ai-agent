/**
 * English copy.
 *
 * 键必须和 `zh-CN.js` 一字不差地对齐 —— 这里缺一个键不会有任何构建期报错，
 * 表现是「切到英文之后某处突然变成中文」（vue-i18n 会回退到默认语言），
 * 而且只在那个页面被打开时才看得见。加键的时候两个文件一起加。
 *
 * 专有名词的处理：品牌名 `Purify AI` / `PurifyManus` 原样保留；
 * 「轻语」音译成 `Qingyu`（对英文用户来说，一个认不出的汉字比一个能读出来的名字糟得多）。
 */
export default {
  common: {
    loading: 'Loading…',
    refresh: 'Refresh',
    close: 'Close',
    save: 'Save',
    saving: 'Saving…',
    saved: 'Saved',
    delete: 'Delete',
    download: 'Download',
    cancel: 'Cancel',
  },

  menu: {
    login: 'Sign in',
    admin: 'Super admin',
    knowledge: 'Knowledge base',
    resources: 'Library',
    settings: 'Settings',
    logout: 'Sign out',
  },

  home: {
    navManus: 'PurifyManus',
    navKnowledge: 'Knowledge base',
    chip: 'Built on Spring AI · one talks, one gets things done',
    slogan: 'Look it up first, then answer',
    lede: 'Qingyu checks the knowledge base before answering a health question; PurifyManus researches before it acts. If they can’t find it, they say so — no guessing.',
    cta: 'Start chatting',
    ctaNote: 'Chat with Qingyu · a weight-loss advisor that checks the knowledge base first',
    entries: 'Other entry points',
    footChat: 'Chat',
    footKnowledge: 'Knowledge base',
    footDocs: 'Documents',
    footSearch: 'Search check',
    footAbout: 'About',
    footAboutText: 'Vue 3 + Vite on the front end, Spring Boot + Spring AI on the back, vector search on pgvector.',
    footDeploy: 'Self-hosted · single port 8080',
    cardSlim: 'A weight-loss advisor. It checks the knowledge base before answering, and says so when it finds nothing — no making things up.',
    cardSlimMeta: 'Knowledge retrieval · one question at a time',
    cardManus: 'It can research, call tools and write files, working a task through step by step. When it needs more from you, it stops and asks.',
    cardManusMeta: 'Tool calling · MCP · self-checking loop',
    cardKnowledge: 'Upload documents to index them, see how each one was split, and try a search yourself — this is what the chat queries.',
    cardKnowledgeMeta: 'Indexing · vector search · search check',
  },

  auth: {
    shellBack: 'Back to home',

    login: {
      title: 'Sign in',
      subtitle: 'Sign in to start chatting and see your own history.',
      username: 'Username',
      usernamePlaceholder: 'Enter your username',
      password: 'Password',
      passwordPlaceholder: 'Enter your password',
      forgot: 'Forgot password?',
      needUsername: 'Please enter your username',
      needPassword: 'Please enter your password',
      failed: 'Sign-in failed, please try again later.',
      submitting: 'Signing in…',
      submit: 'Sign in',
      noAccount: 'No account yet?',
      toRegister: 'Create one',
    },

    register: {
      title: 'Create an account',
      subtitle: 'You’ll need an email address that can receive mail.',
      email: 'Email',
      code: 'Verification code',
      codePlaceholder: '6 digits',
      username: 'Username',
      usernamePlaceholder: '3–20 characters',
      password: 'Password',
      passwordPlaceholder: 'At least 8 characters',
      confirm: 'Confirm password',
      confirmPlaceholder: 'Type it again',
      mismatchHint: 'The two don’t match',
      mismatch: 'The two passwords don’t match',
      passwordTooShort: 'Password must be at least 8 characters',
      failed: 'Sign-up failed, please try again later.',
      submitting: 'Creating…',
      submit: 'Create account & sign in',
      hasAccount: 'Already have an account?',
      toLogin: 'Sign in',
    },

    forgot: {
      title: 'Reset password',
      subtitle: 'We’ll send a code to the email you registered with.',
      done: 'Your password has been changed. Sign in with the new one.',
      toLogin: 'Sign in',
      email: 'Registered email',
      code: 'Verification code',
      codePlaceholder: '6 digits',
      newPassword: 'New password',
      newPasswordPlaceholder: 'At least 8 characters',
      confirm: 'Confirm new password',
      confirmPlaceholder: 'Type it again',
      mismatchHint: 'The two don’t match',
      mismatch: 'The two passwords don’t match',
      passwordTooShort: 'Password must be at least 8 characters',
      failed: 'Reset failed, please try again later.',
      submitting: 'Submitting…',
      submit: 'Reset password',
      remember: 'Remembered it?',
    },

    code: {
      resendIn: 'Resend in {n}s',
      sending: 'Sending…',
      send: 'Get code',
      needEmail: 'Please enter your email first',
      sent: 'Code sent — check your inbox.',
      failed: 'Couldn’t send, please try again later.',
    },
  },

  chat: {
    slim: {
      title: 'Qingyu',
      welcome: 'What’s on your mind?',
      intro: 'I’m Qingyu. Tell me your situation in one line — height, weight, how active you are day to day, what you’re aiming for — and I’ll lay out something concrete.',
      examples: [
        'I’m 175cm, 80kg, desk job, want to get down to 70kg — how should I plan it?',
        'Is walking 10,000 steps a day enough?',
        'What breakfast keeps you full longest when cutting fat?',
        'I keep craving sweets lately — any way to handle that?',
      ],
      placeholder: 'Tell me about your situation, or just ask…',
    },

    manus: {
      title: 'PurifyManus',
      welcome: 'Give me a task',
      intro: 'I can check the weather, research things, read and write files, and work out what to do next on my own. When I need more from you, I’ll stop and ask rather than guess.',
      examples: [
        'Is today good for a run in Hangzhou? If so, write it to a file for me',
        'How do I get from Hangzhou East Station to West Lake? Check the weather along the way too',
        'Find a few well-rated healthy food places nearby and put them in a list',
        'I want to plan this week’s workouts — ask me a few questions first',
      ],
      placeholder: 'Give me a task — the more specific the better…',
    },

    side: {
      backHome: 'Back to home',
      expand: 'Expand sidebar',
      collapse: 'Collapse sidebar',
      switchTo: 'Switch to {name}',
      switchLabel: 'Switch to',
      newChat: 'New chat',
      sessions: 'Conversations',
      noSessions: 'No conversations yet',
      rename: 'Rename',
      delete: 'Delete',
      deleteConfirm: 'Delete “{title}”? The whole conversation will be removed too.',
    },

    trace: {
      // `|` 分开的两档是 vue-i18n 的单复数：英文按 n 自动选，中文两档写一样
      steps: 'Trace · {n} step | Trace · {n} steps',
      summary: 'This turn took {n} steps (per-step detail isn’t stored — see the logs or the live run)',
      type: {
        step: 'step',
        toolCall: 'call',
        toolResult: 'result',
        loopSignal: 'self-check',
        retrieval: 'search',
      },
    },

    badge: {
      failed: 'Failed',
      stopped: 'Stopped',
      aborted: 'Halted',
      // 不写「已拦截」这种像报错的说法：用户看到的应该是顾问在关心他
      blocked: 'Turned into a safety note',
      waiting: 'Waiting for your answer',
    },

    hint: {
      waiting: 'It’s waiting on you — just reply in the same box.',
      aborted: 'The task was halted by the loop guard. Try breaking the request into smaller pieces.',
    },

    composer: {
      stop: 'Stop',
      send: 'Send',
      tip: 'Enter to send, Shift + Enter for a new line',
    },

    history: {
      gone: 'That conversation no longer exists, so a new one was started for you.',
    },

    fallbackAnswer: 'Generation failed.',
  },

  settings: {
    title: 'Settings',
    avatar: {
      title: 'Avatar',
      upload: 'Change avatar',
      uploading: 'Uploading…',
      note: 'png / jpg / webp / gif, up to 2MB.',
      failed: 'Couldn’t upload the avatar, please try again later.',
    },
    appearance: {
      title: 'Appearance',
      light: 'Light',
      dark: 'Dark',
      note: 'Dark mode currently applies to the chat page only. Home, knowledge base and sign-in pages stay light.',
    },
    language: {
      title: 'Language',
      note: 'Interface text and error messages both switch over.',
    },
    profile: {
      title: 'About me',
      needLogin: 'Sign in to fill this in — Qingyu uses it to tailor its advice.',
      intro: 'Fill it in once and every conversation can use it. You can also just say it in chat — both write to the same record.',
      age: 'Age',
      agePlaceholder: 'e.g. 30',
      height: 'Height (cm)',
      heightPlaceholder: 'e.g. 170',
      weight: 'Weight (kg)',
      weightPlaceholder: 'e.g. 71.5',
      bmi: 'BMI',
      goal: 'Goal',
      goalPlaceholder: 'e.g. get down to 65kg in three months',
      activity: 'Daily activity level',
      activityEmpty: 'Not set',
      diet: 'Diet preferences',
      dietPlaceholder: 'e.g. loves pasta / vegetarian',
      avoid: 'Avoid / allergies',
      avoidPlaceholder: 'e.g. seafood allergy / lactose intolerant',
      clearNote: 'Clear a field and save to delete it.',
      loadFailed: 'Couldn’t load your profile, please try again later.',
      saveFailed: 'Couldn’t save, please try again later.',
    },
  },

  resources: {
    title: 'Library',
    empty: 'Nothing here yet.',
    emptyHint: 'Have PurifyManus generate a PDF, download something or write a file — the output gets filed here.',
    source: 'From: {url}',
    deleteConfirm: 'Delete “{title}”? The file itself goes too.',
    loadFailed: 'Couldn’t load the library, please try again later.',
    downloadFailed: 'Download failed, please try again later.',
    deleteFailed: 'Delete failed, please try again later.',
  },

  knowledge: {
    title: 'Knowledge base',
    backHome: 'Back to home',
    overview: {
      title: 'Overview',
      chunks: 'Total chunks',
      documents: 'Documents',
      empty: 'The knowledge base is empty. Upload a few documents and the chat will start querying it.',
      loadFailed: 'Couldn’t load the overview',
    },
    upload: {
      title: 'Upload & index',
      desc: 'txt / md only. Re-uploading the same filename replaces its old chunks. Picking a whole folder imports in bulk.',
      category: 'Category',
      processing: 'Working…',
      pick: 'Choose files',
      pickDir: 'Import a folder',
      preview: 'Preview chunks',
      previewing: 'Previewing…',
      single: '“{source}” indexed: {count} chunks',
      batch: 'Import finished: {ok} succeeded, {failed} failed',
      failureItem: '{name}: {message}',
      failed: 'Upload failed',
    },
    preview: {
      heading: 'Preview: {source} → {count} chunks, {chars} characters',
      truncated: 'Only the first {count} chunks are listed.',
      chars: '{n} chars',
      failed: 'Preview failed',
    },
    docs: {
      title: 'Documents ({n})',
      prev: 'Previous',
      next: 'Next',
      loading: 'Loading…',
      empty: 'No documents yet.',
      source: 'Source',
      category: 'Category',
      chunks: 'Chunks',
      characters: 'Chars',
      uploadedAt: 'Indexed at',
      loadFailed: 'Couldn’t load the document list',
      deleteConfirm: 'Delete every chunk from “{source}”?',
      deleteFailed: 'Delete failed',
    },
    search: {
      title: 'Search check',
      desc: 'Run a real retrieval on one sentence and see whether it searches, what it finds, and the exact text that ends up in the prompt. Read-only — try anything.',
      placeholder: 'e.g. how many calories in a bowl of rice',
      running: 'Searching…',
      submit: 'Search',
      failed: 'Search failed',
      skipped: 'No search was run',
      skippedDetail: ' — the router decided this question has nothing to do with the knowledge base. That’s "never searched", not "searched and found nothing". To route questions like this through retrieval too, set purify.rag.router.query-all-when-unmatched to true.',
      retrieved: 'Searched',
      retrievedDetail: ': {count} hits in {elapsed}ms, category filter: {categories}',
      allCategories: 'whole library',
      rawSummary: 'Exact text that goes into the prompt',
      // Observability for the keyword arm. "Extracted search terms" is the valuable one:
      // the term-splitting is heuristic, and what it produced decides whether this arm hits anything
      keywordTerms: 'Extracted search terms: {terms}',
      keywordUnavailable: 'The keyword arm isn’t running: {reason}. This search was vector-only, so exact matches on proper nouns will be weaker.',
      rankVector: 'vector #{n}',
      rankKeyword: 'keyword #{n}',
    },

    // ------------------------------------------------------- Bailian sync
    // Moves chunks Bailian already produced into the local vector store.
    // The four states (not synced / synced / updated on Bailian / name clash) each get
    // their own sentence: what the user has to do about them is completely different.
    bailian: {
      title: 'Bailian sync',
      desc: 'Move chunks that Bailian already produced into the local vector store. Chunking stays Bailian’s, embedding happens locally, retrieval is unchanged. Re-syncing a document is safe — it replaces the local source of the same name.',
      reload: 'Reload',
      loadFailed: 'Couldn’t load the Bailian file list',
      notConfigured: 'Bailian sync isn’t configured yet; missing: {keys}. Add them to application-local.yml, then reload.',
      connected: 'Connected to: {name} · IndexId={indexId}',
      filter: 'Filter',
      filterFinish: 'Finished only',
      filterAll: 'All statuses',
      selectAll: 'Select unsynced',
      syncing: 'Syncing…',
      syncSelected: 'Sync selected ({n})',
      loading: 'Loading…',
      empty: 'Bailian has no documents matching this filter.',
      prev: 'Previous',
      next: 'Next',
      expand: 'Show chunks',
      collapse: 'Hide',
      columns: {
        name: 'File',
        status: 'Bailian status',
        size: 'Size',
        gmtModified: 'Updated on Bailian',
        local: 'Local',
      },
      state: {
        missing: 'Not synced',
        synced: 'Synced ({chunks} chunks)',
        stale: 'Updated on Bailian',
        // The only state where clicking does damage: syncing replaces the locally
        // uploaded document. It has to stand out, not blend into "not synced"
        conflict: '⚠ Same name (already local)',
      },
      chunks: {
        heading: 'Chunk preview: {name} — {count} chunks total, showing {from}-{to}',
        loading: 'Loading chunks…',
        failed: 'Couldn’t load the chunks',
        fromMetadata: 'Category from Bailian metadata: {category}',
        needPick: 'Bailian didn’t tag this document with a category. Pick one for the whole document:',
        mixed: 'This document’s chunks carry more than one category, so we can’t decide. Pick one for the whole document:',
        category: 'Category',
        empty: 'This document has no chunks on Bailian yet. Its current status is {status}; try again once it finishes parsing.',
        chars: '{n} chars',
      },
      syncOne: 'Sync just this one',
      noSelection: 'Please select at least one document to sync',
      needCategory: 'These still need a category: {names}. Expand one, pick a category, then sync.',
      conflictConfirm: 'A local document named “{name}” already exists and was uploaded here. Syncing replaces it with Bailian’s chunks. Continue?',
      conflictConfirmBatch: 'Some selected documents share a name with an existing local source: {names}. Syncing replaces those with Bailian’s chunks. Continue?',
      timeoutHint: 'Syncing — please don’t close the page. If it times out, reload to see how many made it through; re-syncing is safe.',
      result: {
        batch: 'Sync finished: {ok} succeeded, {failed} failed',
        failureItem: '{name}: {message}',
        failed: 'Sync failed',
      },
    },
  },

  error: {
    unauthorized: 'Your session has expired, please sign in again.',
    forbidden: 'This feature is only available to super admins.',
    timeout: 'The request timed out, please try again later.',
    offline: 'Can’t reach the backend (http://localhost:8080) — check that Spring Boot is running.',
    notFound: 'That resource doesn’t exist (404).',
    http: 'Request failed (HTTP {status})',
    code: 'Request failed ({code})',
    generic: 'Request failed, please try again later.',
    streamUnsupported: 'This browser can’t read a streamed response (response.body is empty).',
  },
}
