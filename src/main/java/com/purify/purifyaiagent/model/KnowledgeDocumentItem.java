package com.purify.purifyaiagent.model;

/**
 * 知识库里的一份文档（按 {@code source} 聚合出来的一行）。
 *
 * <p>{@code KnowledgeBaseStats} 给的是「有多少」，这个是「有哪些」——管理页要列出
 * 每一份文档、能单独删掉某一份，光有聚合计数做不到。
 *
 * @param source         来源标识，就是上传时的文件名。也是删除时的入参（幂等锚点）
 * @param chunks         这份文档产生了多少切片
 * @param characters     这份文档的正文总字符数，用来判断切片有没有明显丢内容
 * @param classification 归档分类。理论上同一份文档只有一个值，
 *                       取不到时是 {@code (未标注)}——那说明写入端没打上这个字段
 * @param uploadedAt     最近一次入库时间（ISO-8601 字符串，直接来自元数据，不做解析）
 */
public record KnowledgeDocumentItem(String source,
                                    long chunks,
                                    long characters,
                                    String classification,
                                    String uploadedAt) {
}
