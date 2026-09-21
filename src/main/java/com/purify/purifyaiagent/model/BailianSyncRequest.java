package com.purify.purifyaiagent.model;

import java.util.List;

/**
 * 同步请求：把哪几份百炼文档搬进本地向量库。
 *
 * <p><b>为什么必须由前端把 {@code name} 一起传上来</b>，而不是服务端自己按
 * {@code fileId} 去查一次：本地 {@code source} 用的就是文件名，
 * 而百炼的文件清单接口是按页给的——服务端为了拿一个名字再翻一遍清单，
 * 是纯粹多出来的一次远程调用。代价是这个字段可能被传错，但传错的后果只是
 * 「本地这份文档叫了另一个名字」，不影响检索（检索按分类过滤，不按文件名）。
 *
 * @param items 要同步的文档，逐份独立处理
 */
public record BailianSyncRequest(List<Item> items) {

    /**
     * @param fileId         百炼侧的文档标识，用它拉切片
     * @param name           文件名，落库后成为本地 {@code source}
     * @param classification <b>兜底分类</b>：服务端先看百炼切片的元数据里有没有标分类，
     *                       只有标不出来（或标了多个）时才用它。两者都没有则这份失败——
     *                       不猜，因为分类值错了会让这些切片在带分类过滤的提问下
     *                       永远检索不到，而且不报任何错
     * @param gmtModified    列表页上看到的百炼修改时间，会被原样存进切片元数据，
     *                       用于日后判断「百炼那边后来改过没有」。
     *                       <b>必须由前端带上来</b>：服务端为了拿这个值再翻一遍文件清单
     *                       是白费一次远程调用，而真正的权威值本来就该在同步那一刻取。
     *                       <b>传 null 的后果是这份文档以后永远显示「百炼侧已更新」</b>——
     *                       本地没有基准可比。这是个安全的退化（顶多多同步一次），
     *                       所以这里不把它当必填
     */
    public record Item(String fileId, String name, String classification, Long gmtModified) {
    }
}
