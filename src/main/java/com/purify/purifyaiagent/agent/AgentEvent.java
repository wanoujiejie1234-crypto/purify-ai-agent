package com.purify.purifyaiagent.agent;

/**
 * 智能体跑动过程中往外抛的事件，流式接口（SSE）就是把它一条条发出去。
 *
 * <p>为什么不是「直接把最终文本流出去」：这个智能体的中间过程本身就是它最有价值的部分——
 * 调了哪个工具、工具返回了什么、是不是被看门狗拦下过。只流最终答案的话，
 * 用户看到的和普通对话没有区别，循环检测这套机制等于白做了。
 *
 * <p>阻塞式接口不用这个类型，它直接返回 {@link AgentResult}：那边没有「中间过程」要展示，
 * 事件流攒完就是那个结果。
 *
 * @param type  事件类型，SSE 里同时用作事件名（{@code event: TOOL_CALL}）
 * @param text  事件正文，含义随类型而定，见下面各工厂方法
 * @param state 只有终态事件（{@link Type#FINAL} / {@link Type#QUESTION} / {@link Type#ERROR}）
 *              才非 null，告诉调用方这次 run 是以什么状态收的尾；中间事件一律为 null
 */
public record AgentEvent(Type type, String text, AgentState state) {

    public enum Type {

        /** 新的一步开始，text 是步序号。 */
        STEP,

        /** 模型输出的文本片段。流式下是逐段到达的，阻塞式下整段一次到达。 */
        TEXT,

        /** 模型要求调用某个工具，text 形如 {@code maps_weather({"city":"杭州"})}。 */
        TOOL_CALL,

        /** 某个工具返回了，text 是工具的返回内容（可能被截断）。 */
        TOOL_RESULT,

        /** 看门狗发声：命中了循环判据或者步数超预算，text 是处置说明。 */
        LOOP_SIGNAL,

        /**
         * 开场预检索的结果：跳过了、命中几条、还是失败。text 是一句摘要。
         *
         * <p>为什么要有这个类型：检索是「悄悄发生」的——命中也好、没查也好，
         * 用户的观感都是「模型直接开始回答了」。答得不对或者答得很慢的时候，
         * 分不清是知识库没召回到、压根没查、还是查了没被采纳。这条事件把前两种可能直接摆出来。
         *
         * <p><b>跳过也要发</b>，那正是「看不到检索在工作」的最坏情况。
         * 正文里只放条数和文档名，不放切片原文——SSE 是逐条推送的，塞原文会把流刷爆。
         */
        RETRIEVAL,

        /** 需要用户回答，text 是问题。这次 run 就此暂停。 */
        QUESTION,

        /** 正常跑完，text 是最终答复。 */
        FINAL,

        /** 出错退出，text 是给用户看的失败说明。 */
        ERROR
    }

    public static AgentEvent step(int index) {
        return new AgentEvent(Type.STEP, "第 " + index + " 步", null);
    }

    public static AgentEvent text(String delta) {
        return new AgentEvent(Type.TEXT, delta, null);
    }

    public static AgentEvent toolCall(String name, String arguments) {
        return new AgentEvent(Type.TOOL_CALL, name + "(" + arguments + ")", null);
    }

    public static AgentEvent toolResult(String name, String result) {
        return new AgentEvent(Type.TOOL_RESULT, name + " → " + result, null);
    }

    public static AgentEvent loopSignal(String message) {
        return new AgentEvent(Type.LOOP_SIGNAL, message, null);
    }

    /**
     * 开场预检索的结果。
     *
     * <p>state 为 null，因此 {@code PurifyManus#chatStream} 的数步逻辑不会把它算成一步，
     * {@code chat_record.steps} 的口径不受影响。
     */
    public static AgentEvent retrieval(String summary) {
        return new AgentEvent(Type.RETRIEVAL, summary, null);
    }

    public static AgentEvent question(String question) {
        return new AgentEvent(Type.QUESTION, question, AgentState.WAITING_FOR_USER);
    }

    public static AgentEvent finished(String output) {
        return new AgentEvent(Type.FINAL, output, AgentState.FINISHED);
    }

    public static AgentEvent aborted(String reason) {
        return new AgentEvent(Type.FINAL, reason, AgentState.ABORTED);
    }

    /**
     * 命中敏感词，被按策略拦下。text 是那段引导话术。
     *
     * <p>走的是 {@link Type#FINAL} 而不是 {@link Type#ERROR}：话术本身就是要展示给用户的内容，
     * 标成 ERROR 会让界面画一个红色「生成失败」，而用户看到的是「服务坏了」——
     * 和「顾问主动拒绝回答」完全不是一回事。状态单开一个 {@link AgentState#BLOCKED}，
     * 界面仍然能把它和正常答完区分开。
     */
    public static AgentEvent blocked(String reply) {
        return new AgentEvent(Type.FINAL, reply, AgentState.BLOCKED);
    }

    public static AgentEvent error(String message) {
        return new AgentEvent(Type.ERROR, message, AgentState.ERROR);
    }

    /** 按 run 的终态挑一个终态事件。循环收尾时只看状态，不关心它是怎么走到这一步的。 */
    public static AgentEvent terminal(AgentState state, String output, String question) {
        return switch (state) {
            case WAITING_FOR_USER -> question(question);
            case FINISHED -> finished(output);
            case ABORTED -> aborted(output);
            case BLOCKED -> blocked(output);
            case ERROR -> error(output);
            // RUNNING 不是终态，走到这里说明调用方在循环中途问「结束了没」，那是它的 bug
            case RUNNING -> throw new IllegalStateException("run 还在跑，没有终态事件");
        };
    }
}
