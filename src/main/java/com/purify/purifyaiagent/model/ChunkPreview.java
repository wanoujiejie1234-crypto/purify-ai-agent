package com.purify.purifyaiagent.model;

import java.util.List;

/**
 * 索引前的预览：这份文档会被切成什么样。
 *
 * <p><b>它解决的是一个很具体的浪费</b>：切片参数（{@code chunk.size}、
 * {@code min-chunk-size-chars}）调一次要重新打包、重启、上传、再等向量化跑完，
 * 才知道切得好不好。而切得不好的表现往往是「检索就是查不到」，
 * 那时候要查的方向就多了。先把切法预览出来，这一轮试错就不用真写库。
 *
 * <p>预览<b>不写库</b>，所以可以反复试：改一次参数、预览一次、看着行就再上传。
 *
 * @param source       文件名
 * @param classification 归档分类
 * @param chunkCount   会切成几片
 * @param characters   正文总字符数
 * @param chunks       每片的摘要，最多 {@code PREVIEW_LIMIT} 条
 * @param truncated    摘要是否被截断了。true 表示实际切片比 {@code chunks} 里列的多——
 *                     不标出来的话，用户会以为「这份文档只切出了 20 片」
 */
public record ChunkPreview(String source,
                           String classification,
                           int chunkCount,
                           long characters,
                           List<Chunk> chunks,
                           boolean truncated) {

    /**
     * 一片切片的摘要。
     *
     * @param index  序号，从 0 开始
     * @param length 这一片的字符数。明显偏短或偏长都能从这里看出来
     * @param excerpt 正文开头
     */
    public record Chunk(int index, int length, String excerpt) {
    }
}
