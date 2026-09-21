package com.purify.purifyaiagent.model;

import java.util.List;

/**
 * 百炼文件清单的一页。形状对齐 {@link KnowledgeDocumentPage}，前端的分页组件可以复用。
 *
 * <p>{@code total} 是<b>百炼侧</b>的总数，不是本地已有的份数——
 * 「百炼一共 42 份，本地有 30 份」这两个数要能分开看，否则分页会算错。
 *
 * @param total 百炼侧符合条件的文档总数
 * @param page  当前页码，从 1 开始
 * @param size  每页几条
 * @param items 本页的文档
 */
public record BailianDocumentPage(long total, int page, int size, List<BailianDocumentItem> items) {
}
