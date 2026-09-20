package com.purify.purifyaiagent.model;

import java.util.List;

/**
 * 文档列表的一页。
 *
 * <p>带上 {@code total} 而不是只回一个数组：管理页要显示「第几页 / 共几页」，
 * 而这个数只有数据库知道。让前端自己数当前页有几条是数不出总数的。
 *
 * @param total 文档总份数（不受分页影响）
 * @param page  当前页码，从 1 开始
 * @param size  每页条数
 * @param items 这一页的文档
 */
public record KnowledgeDocumentPage(long total, int page, int size, List<KnowledgeDocumentItem> items) {
}
