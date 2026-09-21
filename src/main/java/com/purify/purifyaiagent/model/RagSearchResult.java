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
 * @param keyword       关键词那一路的状态与本次拆出的检索词；走百炼链路时是 null
 */
public record RagSearchResult(String question,
                              boolean retrieved,
                              List<String> categories,
                              int count,
                              long elapsedMs,
                              List<Chunk> chunks,
                              String referenceText,
                              KeywordArm keyword) {

    /**
     * 一条召回切片。
     *
     * @param index       编号，对应提示词里的 {@code [1]}、{@code [2]}
     * @param docName     来自哪份文档
     * @param title       切片标题
     * @param excerpt     正文开头，截断过——自检是给人看的，不需要把整片贴出来
     * @param score       重排分数；没开重排、或走百炼链路时是 null
     * @param rrfScore    融合分数；没走融合（百炼链路、或关键词路没开工）时是 null
     * @param rrfArms     这条被哪几路召回了，逗号分隔，如 {@code "vector,keyword"}
     * @param vectorRank  在向量那一路排第几（从 1 起）；没被它召回时是 null
     * @param keywordRank 在关键词那一路排第几；没被它召回时是 null。
     *                    <b>两个 rank 一起看，就能回答「这条为什么排在这儿」</b>——
     *                    两路都命中却排在只被一路命中的后面，那才是融合出了问题
     */
    public record Chunk(int index,
                        String docName,
                        String title,
                        String excerpt,
                        Double score,
                        Double rrfScore,
                        String rrfArms,
                        Integer vectorRank,
                        Integer keywordRank) {
    }

    /**
     * 关键词那一路的状态，外加<b>这一次</b>从问题里拆出来的检索词。
     *
     * <p>{@code terms} 是这里最值钱的字段：拆词规则是纯启发式的（没有词典），
     * 而「它把问题拆成了什么」直接决定这一路能不能命中、命中的是不是想要的。
     * 有了它，「`一天吃几个鸡蛋比较好` 为什么只提了 `鸡蛋`」五秒就能答完，
     * 不必去断点或加日志。
     *
     * @param enabled   配置上开没开
     * @param available 实际能不能用。false 表示这一路已被停用、本次检索是纯向量的
     * @param reason    为什么不可用；可用时是 null
     * @param terms     本次拆出的检索词；这一路不可用时是空列表
     */
    public record KeywordArm(boolean enabled, boolean available, String reason, List<String> terms) {
    }
}
