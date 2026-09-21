/**
 * 检查中英两个语言包的键是否一一对应。
 *
 * <p>跑法：`npm run check:i18n`。
 *
 * <h2>为什么需要它</h2>
 *
 * vue-i18n 找不到键时**不会报错**：它会退回 `fallbackLocale`（这里配的是中文），
 * 只在控制台打一句警告。于是「英文包里漏了一条」的表现是
 * 「界面全英文，某处突然冒出一句中文」，而且只有真的走到那个分支才看得见
 * ——恰恰是错误提示、空状态这类不常出现的分支最容易漏。
 *
 * 后端有 `MessageBundleParityTest` 盯着同一件事，这边用这个脚本对上。
 * 前端没有测试框架（项目里一个都没有），所以不为了这一件事引入一整套 vitest，
 * 一个 node 脚本够用：两个语言包都是纯 ES 模块，`import` 进来直接比即可。
 *
 * <h2>它检查什么</h2>
 *
 * 1. **键集合完全一致**（递归比到叶子）；
 * 2. **数组的长度一致**（示例问题那种：中文写 4 条、英文写 3 条，
 *    键路径全都在，但第 4 条在英文下会是 undefined）；
 * 3. **占位符一致**（`{n}` / `{title}` 这类）。vue-i18n 对缺失的具名参数
 *    不会抛异常，只会把占位符原样留在文案里——用户看到的是「命中 {count} 条」。
 */

import zhCN from '../src/i18n/zh-CN.js'
import enUS from '../src/i18n/en-US.js'

/** 递归收集所有叶子节点的路径，数组按 `路径[下标]` 展开。 */
function collectKeys(value, prefix = '') {
  const keys = []
  if (Array.isArray(value)) {
    keys.push(`${prefix}#length=${value.length}`)
    value.forEach((item, index) => keys.push(...collectKeys(item, `${prefix}[${index}]`)))
  } else if (value !== null && typeof value === 'object') {
    for (const [name, child] of Object.entries(value)) {
      keys.push(...collectKeys(child, prefix ? `${prefix}.${name}` : name))
    }
  } else {
    keys.push(prefix)
  }
  return keys
}

/** 一条文案里的具名占位符，比如 {n} {title}。 */
function placeholdersOf(text) {
  if (typeof text !== 'string') return new Set()
  return new Set([...text.matchAll(/\{([A-Za-z_][A-Za-z0-9_]*)}/g)].map((m) => m[1]))
}

/** 递归拿到「路径 → 文案」，只收字符串叶子。 */
function collectTexts(value, prefix = '', out = new Map()) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => collectTexts(item, `${prefix}[${index}]`, out))
  } else if (value !== null && typeof value === 'object') {
    for (const [name, child] of Object.entries(value)) {
      collectTexts(child, prefix ? `${prefix}.${name}` : name, out)
    }
  } else if (typeof value === 'string') {
    out.set(prefix, value)
  }
  return out
}

const problems = []

const zhKeys = new Set(collectKeys(zhCN))
const enKeys = new Set(collectKeys(enUS))

for (const key of zhKeys) {
  if (!enKeys.has(key)) {
    // 数组长度那一条的报错要说得更直白些，否则读到 `chat.slim.examples#length=4` 会懵
    problems.push(
      key.includes('#length=')
        ? `数组长度不一致：${key.replace('#length=', ' 中文有 ')} 条（英文那边数量对不上）`
        : `英文包里缺键：${key}`,
    )
  }
}
for (const key of enKeys) {
  if (!zhKeys.has(key)) problems.push(`中文包里缺键：${key}`)
}

const zhTexts = collectTexts(zhCN)
const enTexts = collectTexts(enUS)
for (const [key, text] of zhTexts) {
  if (!enTexts.has(key)) continue
  const zhPlaceholders = [...placeholdersOf(text)].sort().join(',')
  const enPlaceholders = [...placeholdersOf(enTexts.get(key))].sort().join(',')
  if (zhPlaceholders !== enPlaceholders) {
    problems.push(`占位符不一致：${key} —— 中文 {${zhPlaceholders}} vs 英文 {${enPlaceholders}}`)
  }
}

if (problems.length) {
  console.error(`语言包对不上，共 ${problems.length} 处：\n`)
  for (const problem of problems) console.error(`  - ${problem}`)
  console.error('\n两个语言包（src/i18n/zh-CN.js 和 en-US.js）要一起改。')
  process.exit(1)
}

console.log(`语言包一致：${zhKeys.size} 条键，中英齐平。`)
