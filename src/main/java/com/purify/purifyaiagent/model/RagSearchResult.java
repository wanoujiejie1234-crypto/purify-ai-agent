package com.purify.purifyaiagent.model;

import java.util.List;

/**
 * 一次检索自检的结果，给 {@code GET /api/rag/search} 用。
 *
 * <p>它把「检索这一步到底发生了什么」整个摊开，回答的是排查时最朴素的那个问题：
 * <b>我这句话，知识库究竟查出了什么？</b>
 *
 * <p>{@code referenceText} 是这个接口存在的主要理由：它是<b>会原样拼进 Prompt 的那段文本</b>。
 * 有了它就不用猜「模型到底读到了什么」——召回不对、格式串了、混进了不相关的切片，
 * 看一眼就清楚。
 *
 * @param question      送检的问题（原样回显，方便对照）
 * @param retrieved     路由判定「要不要查」。<b>false 时下面三样必然为空</b>，
 *                      这对应的是「压根没查」而不是「查了没命中」——两者必须能分开，
 *                      它们的排查方向完全不同
 * @param categories    路由命中的分类；为空表示不带过滤查全库
 * @param count         最终召回条数
 * @param elapsedMs     整个检索的耗时，含向量化与重排两次远程调用
 * @param chunks        逐条切片，供人核对召回是否对路
 * @param referenceText 会拼进 Prompt 的那段文本；没召回时为 null
 */
public record RagSearchResult(String question,
                              boolean retrieved,
                              List<String> categories,
                              int count,
                              long elapsedMs,
                              List<Chunk> chunks,
                              String referenceText) {

    /**
     * 一条召回切片。
     *
     * @param index   编号，对应提示词里的 {@code [1]}、{@code [2]}
     * @param docName 来自哪份文档
     * @param title   切片标题
     * @param excerpt 正文开头，截断过——自检是给人看的，不需要把整片贴出来
     * @param score   重排分数；没开重排、或走百炼链路时是 null
     */
    public record Chunk(int index, String docName, String title, String excerpt, Double score) {
    }
}
