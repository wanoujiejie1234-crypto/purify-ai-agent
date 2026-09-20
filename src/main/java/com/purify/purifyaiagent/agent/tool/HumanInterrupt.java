package com.purify.purifyaiagent.agent.tool;

/**
 * 「本轮要先问用户一个问题」的标记，每次 run 新建一个。
 *
 * <p>它解决的是一个纯工程问题：工具（{@link AskHumanTool}）想把「我要问用户」这件事告诉循环，
 * 但工具的返回值是要回给<b>模型</b>看的字符串，塞不下这个意思——写进返回值里，模型会以为
 * 自己已经问到了答案。所以借用 {@code ToolContext}：run 开始前把这个对象放进工具上下文，
 * 工具往里置位，循环在一步结束时读它，读到就停下来。
 *
 * <p>这条通路和 {@code UserProfileTool} 取 userId 是同一套机制（工具上下文对模型不可见，
 * 模型既看不到也传不了），区别只在传的是「值」还是「一个可以写回的对象」。
 *
 * <p><b>两个来路，一个出口</b>：模型自己调 askHuman 会置位它；看门狗升级到「问用户」时
 * （见 {@code AskUserLoopHandler}）也会置位它。循环只需要看「有没有待答的问题」，
 * 不必区分是谁提出来的。
 *
 * <p>非线程安全，也不需要是：每个 run 一个实例，只在「工具执行 → 一步收尾」这条链路上传递。
 * 唯一要防的是可见性——工具执行和收尾判定之间隔着 Reactor 的线程切换，
 * 所以字段用 volatile。
 */
public final class HumanInterrupt {

    /** 放进 {@code ToolContext} 的键。公开出来供调用方复用，避免这个字符串散落在两处。 */
    public static final String KEY = "humanInterrupt";

    private volatile String question;

    /** 记下要问用户的问题。 */
    public void ask(String question) {
        this.question = question;
    }

    /** 有没有还没回答的问题。 */
    public boolean hasPending() {
        return this.question != null && !this.question.isBlank();
    }

    /** 待答的问题；没有则为 null。 */
    public String pendingQuestion() {
        return hasPending() ? this.question : null;
    }
}
