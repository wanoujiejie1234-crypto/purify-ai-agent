package com.purify.purifyaiagent.model;

import java.util.Map;

/**
 * 百炼侧的一条切片 —— 给超级用户<b>审切片</b>用的。
 *
 * <p><b>{@code text} 不做截断。</b>这个接口存在的意义就是让人看清「百炼到底把它切成了什么样」，
 * 截断了就审不了。这和 {@code /api/rag/search} 那边的取向正好相反——
 * 那个截到 200 字，因为它要回答的是「命中了什么」，不是「切得对不对」。
 * 代价是响应可能很大，所以默认每页只取 20 条，而且是由「展开某一行」按需触发的。
 *
 * @param index     本条在整份文档里的序号，从 0 开始，与本地写入时的 {@code chunk_index} 同口径
 * @param chunkId   百炼侧的切片 ID（在 metadata 的 {@code _id} 里）。只用于展示和排障
 * @param length    {@code text} 的字符数。给个「这片多大」的直观数字，前端不必自己再算
 * @param text      切片正文，原样
 * @param metadata  百炼返回的切片元数据，<b>原样透出</b>。这是让超管自己判断
 *                  「百炼到底给我标了什么」的地方，所以不做筛选也不做改名
 * @param suggestedClassification 从 {@code metadata} 里按配置的 filter-key 取出来的分类，
 *                  且已校验过它确实在配置的分类表里；取不到或不认识时是 {@code null}
 */
public record BailianChunkItem(int index,
                               String chunkId,
                               int length,
                               String text,
                               Map<String, Object> metadata,
                               String suggestedClassification) {
}
