package com.purify.purifyaiagent.model;

import java.util.Map;

/**
 * 本地向量库的概览。
 *
 * <p>两个分组计数用来回答「索引到底建对没有」：切片数不对劲时，
 * 看是哪个分类、哪份文档出的问题。
 *
 * @param totalChunks          切片总数
 * @param documentCount        文档份数（按 {@code source} 去重后的个数）
 * @param chunksBySource       每个来源各有多少切片；按切片数从多到少排
 * @param chunksByClassification 每个分类各有多少切片
 */
public record KnowledgeBaseStats(long totalChunks,
                                 long documentCount,
                                 Map<String, Long> chunksBySource,
                                 Map<String, Long> chunksByClassification) {
}
