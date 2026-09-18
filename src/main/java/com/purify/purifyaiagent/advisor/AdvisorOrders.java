package com.purify.purifyaiagent.advisor;

/**
 * 自定义 Advisor 的执行顺序常量。
 *
 * <p>Advisor 链按 order 升序排列，形成「洋葱模型」：
 * before 阶段从小到大依次进入，after 阶段从大到小依次返回。
 * 也就是说 order 越小，越靠近链条外层，越早拿到请求、越晚拿到响应。
 *
 * <p>内置的 {@code MessageChatMemoryAdvisor} 的 order 是
 * {@code Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER}（Integer.MIN_VALUE 附近），
 * 比下面所有常量都小，因此会话记忆总是最先注入历史消息，
 * 后续 Advisor 拿到的就是「带上下文」的完整 Prompt。
 */
public final class AdvisorOrders {

    private AdvisorOrders() {
    }

    /** 敏感词校验：最靠外，命中就拦截，避免发起无意义的模型调用。 */
    public static final int SENSITIVE_WORD = 0;

    /**
     * 知识库检索（RAG）。
     *
     * <p>排在敏感词之后：被拦下的请求不该再去查一次知识库；
     * 排在 Re-Reading 之前：检索拿到的是用户原话，而不是被追加了指令的改写版。
     */
    public static final int KNOWLEDGE_BASE_RETRIEVAL = 5;

    /** Re-Reading 提示词改写：在日志之前，保证日志记录的是最终发给模型的内容。 */
    public static final int RE_READING = 10;

    /** 请求 / 响应日志。 */
    public static final int LOGGING = 20;
}
