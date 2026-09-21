package com.purify.purifyaiagent.rag.pgvector;

/**
 * 关键词那一路的工作状态 —— 一个应用生命周期内不变的事实，不是每次检索的结果。
 *
 * <p>它存在的理由只有一条：<b>让「这一路有没有在工作」永远看得见。</b>
 * 这个方案最危险的失败方式不是报错，而是安静地退化——SQL 只引用 PostgreSQL
 * 核心操作符，pg_bigm 没装时查询照样成功返回，只是退化成全表扫描。没有这个状态，
 * 界面上和日志里都会是一片正常。
 *
 * @param enabled   配置上开没开（{@code purify.rag.pgvector.keyword.enabled}）
 * @param available 实际能不能用。{@code enabled} 为 true 但探测不到 pg_bigm、
 *                  或者配了 {@code require-extension=true} 时，这里仍是 false
 * @param reason    一句话说明为什么不可用；可用时是 null。这句话会直接进日志和
 *                  {@code /api/rag/search} 的返回体，所以要写得能照着做
 */
public record KeywordArmStatus(boolean enabled, boolean available, String reason) {

    /** 可用，且原因是「一切正常」。 */
    public static KeywordArmStatus ready() {
        return new KeywordArmStatus(true, true, null);
    }

    /** 配置上就关着。 */
    public static KeywordArmStatus disabled() {
        return new KeywordArmStatus(false, false, "purify.rag.pgvector.keyword.enabled=false");
    }
}
