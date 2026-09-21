package com.purify.purifyaiagent.model;

/**
 * 本地向量库里某一份来源的摘要：它在本地有没有、有多少片、以及来源侧留下的同步标记。
 *
 * <p>给知识库同步的「未同步 / 已同步 / 来源侧已更新 / 同名冲突」四态判定用。
 * 和 {@link KnowledgeDocumentItem} 的区别是：那个是给管理页的文档列表看的，
 * 只回答「本地有什么」；这个要回答「本地这份和来源侧那份是不是同一份、是不是最新的」，
 * 所以多带两个来源侧的标记。两者没有合并成一条 SQL 返回同一个类型，是因为
 * 文档列表接口并不需要那两个标记——塞进去只会让它多出两个对使用者无意义的字段。
 *
 * @param source      来源标识，通常是文件名。也是幂等锚点
 * @param chunks      这份来源在本地有多少片
 * @param uploadedAt  入库时刻（<b>本地</b>时间，仅用于展示）
 * @param fileId      来源侧的文档标识；本地手动上传的文档没有这个键，所以是 {@code null}
 * @param gmtModified 来源侧的修改时间，<b>原始值、不做任何换算</b>，可能是 {@code null}
 *
 * <h2>为什么 gmtModified 存原始值、不和 uploadedAt 比</h2>
 *
 * <p>判断「来源侧那份是不是比本地这份新」，只能拿<b>来源侧自己的</b>两个时间戳比：
 * 同步那一刻记下的这个值，和来源侧当前的值。绝不能拿 {@code uploadedAt}（本地
 * {@code Instant.now()}）去比——那是两台机器的时钟，不可比。真要比的话，
 * 表现会是「刚同步完就显示『来源侧已更新』」（本地时钟慢）或者永远不显示
 * （本地时钟快），而两种表现都不会报错，只会让人以为同步功能坏了。
 *
 * <p>所以这个字段是「来源侧时间戳的一个快照」，它的值本身没有意义，
 * 有意义的是它和来源侧当前值是否相等。
 */
public record SyncedSource(String source,
                           long chunks,
                           String uploadedAt,
                           String fileId,
                           Long gmtModified) {
}
