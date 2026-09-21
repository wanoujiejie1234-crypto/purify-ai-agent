package com.purify.purifyaiagent.model;

import java.util.List;

/**
 * 某一份百炼文档的切片预览 —— 一页。
 *
 * @param fileId   百炼侧的文档标识
 * @param pageNum  当前页，从 1 开始
 * @param pageSize 每页几条
 * @param total    这份文档在百炼侧一共有多少片
 * @param chunks   本页的切片
 * @param classificationSource 本页看到的分类标注情况，见 {@link ClassificationSource}
 */
public record BailianChunkPage(String fileId,
                               int pageNum,
                               int pageSize,
                               long total,
                               List<BailianChunkItem> chunks,
                               ClassificationSource classificationSource) {

    /**
     * 本页切片的分类标注情况 —— 决定前端该「直接显示一个分类」还是「要求超管选一个」。
     *
     * <p><b>注意这是「本页观察」，不是「整份文档的结论」。</b>要知道整份文档是否混标，
     * 得把所有页都拉下来，而预览接口是逐页按需触发的，那样等于每次预览都把整份文档
     * 拉一遍。真正的整份判定发生在同步那一刻（那时本来就要拉全量），
     * 由 {@code BailianKbSyncService} 负责。这个字段只用来让界面不至于显示一个
     * 看起来确定、其实可能不全面的分类。
     */
    public enum ClassificationSource {

        /** 本页的切片都标了同一个、且配置里认识的分类。可以自动带出。 */
        METADATA,

        /** 本页的切片标了<b>不止一个</b>分类。这时不能替用户挑，要让他自己选。 */
        MIXED,

        /** 本页的切片都没标分类（或标的值不在配置的分类表里）。必须由超管选。 */
        NONE
    }
}
