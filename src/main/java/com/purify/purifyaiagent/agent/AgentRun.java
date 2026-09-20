package com.purify.purifyaiagent.agent;

import com.purify.purifyaiagent.agent.loop.LoopSignal;
import com.purify.purifyaiagent.agent.loop.LoopType;
import com.purify.purifyaiagent.agent.tool.HumanInterrupt;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次 run 的<b>全部可变状态</b>：跑到第几步、说过什么、被拦过几次、要不要等用户回答。
 *
 * <p>把它单拎出来，是为了让智能体本身<b>无状态</b>。智能体是单例 Bean，多个会话会并发进来；
 * 只要有一点「当前跑到第几步」之类的字段留在 Bean 上，两个会话就会串味——
 * 而这种 bug 只在并发时才出现，本地怎么点都点不出来。所以规矩定死：
 * <b>Bean 上只放配置和依赖，凡是会变的都放这里</b>，每次 run 新建一份。
 *
 * <p>它不做线程安全，因为一条 run 的事件流是串行的（Reactor 保证前一个算子完成才走下一个）。
 * 需要跨线程可见的只有 {@link HumanInterrupt}，那里单独用 volatile 兜住了。
 */
public final class AgentRun {

    private final String chatId;

    /**
     * 发起这次 run 的用户 id（十进制字符串），由 HTTP 层从令牌里解出来。
     *
     * <p><b>为什么不复用 {@link #chatId}</b>：会话和人是两个维度。同一个用户可以有多个会话
     * （每个都有自己的 chatId），而用户画像是<b>按人</b>存的——拿 chatId 当用户标识的话，
     * 用户在「轻语」里说的身高体重，换个会话就查不到了。
     *
     * <p><b>为什么放在这里而不是 ThreadLocal 里</b>：run 的事件流跑在 Reactor 的调度线程上
     * （见 {@code BaseAgent#runStream} 的 {@code subscribeOn}），请求线程上的 ThreadLocal
     * 在那里不保证可见；而失败的表现是「画像悄悄存到了别人名下」。
     * 这个类的类注释已经把规矩定死了：凡是会变的、跟着一次 run 走的状态都放这里。
     */
    private final String userId;

    private final List<Message> messages;
    private final List<AgentStep> steps = new ArrayList<>();
    private final List<String> hints = new ArrayList<>();
    private final Set<LoopType> loopTypes = new LinkedHashSet<>();
    private final HumanInterrupt interrupt = new HumanInterrupt();

    /**
     * 这一轮开场预检索到的参考资料，已经渲染成一段文本。空串表示没有（没查、没召回、或检索失败）。
     *
     * <p><b>它不是消息。</b>不能塞进 {@link #messages()}：那个列表每一步之后都会被
     * {@code BaseAgent#advance} 整份覆盖进 {@link AgentMemory}，塞进去的后果是这段材料
     * 跟着记忆留到之后每一轮——用户问第三句的时候模型还在看第一句的切片，
     * 而记忆窗口也会被这种大块文本迅速吃满。
     *
     * <p>它只进 Prompt，而 Prompt 是用完就丢的：{@code ToolCallAgent} 从工具执行结果里
     * 取消息时按「指令条数」切片，注入 Prompt 的内容天然被排除在外。
     */
    private String referenceContext = "";

    private AgentState state = AgentState.RUNNING;
    private String output = "";
    private int loopHits = 0;
    private boolean streaming = false;

    private AgentRun(String chatId, String userId, List<Message> history, String input) {
        this.chatId = chatId;
        this.userId = userId;
        this.messages = new ArrayList<>(history);
        // 用户这句话先落进工作列表：万一下一步就崩了，记忆里也还留着这次提问
        this.messages.add(new UserMessage(input));
    }

    /**
     * 开一次 run。
     *
     * @param userId  发起这次 run 的用户 id，见 {@link #userId} 字段的说明。
     *                为 null 或空白是允许的（工具层会退化成「认不出是谁」），
     *                但 HTTP 入口在鉴权那一步就已经挡住了这种情况
     * @param history 这个会话已有的消息（来自 {@code AgentMemory}），会被拷进工作列表；
     *                外面那份不受影响——run 跑到一半失败时，记忆里保留的还是上一次完整的状态
     */
    public static AgentRun start(String chatId, String userId, String input, List<Message> history) {
        return new AgentRun(chatId, userId, history, input);
    }

    public String chatId() {
        return chatId;
    }

    /**
     * 发起这次 run 的用户 id。
     *
     * <p>注意它和 {@link #chatId()} <b>都是 String、且都是不透明的标识符</b>——
     * 两个参数写反了能编译通过，表现却是「画像存到了会话 id 上」，
     * 而且不会有任何报错。所有调用点要保持 {@code (chatId, userId, ...)} 这个顺序。
     */
    public String userId() {
        return userId;
    }

    /** 正在拼装的工作消息列表（不含系统提示词）。每一步结束后由 {@code BaseAgent} 交给记忆保存。 */
    public List<Message> messages() {
        return messages;
    }

    public List<AgentStep> steps() {
        return steps;
    }

    /** 看门狗追加的提示，会被拼进下一次调用的系统提示词。不落记忆，见 {@code HintLoopHandler}。 */
    public List<String> hints() {
        return hints;
    }

    /** 开场预检索到的参考资料，只进 Prompt、不进 {@link #messages()}，理由见字段注释。 */
    public String referenceContext() {
        return referenceContext;
    }

    public void setReferenceContext(String referenceContext) {
        this.referenceContext = referenceContext == null ? "" : referenceContext;
    }

    public HumanInterrupt interrupt() {
        return interrupt;
    }

    public AgentState state() {
        return state;
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    public String output() {
        return output;
    }

    /** 待用户回答的问题；没有则为 null。 */
    public String question() {
        return interrupt.pendingQuestion();
    }

    public int stepCount() {
        return steps.size();
    }

    /** 下一步的序号（从 1 开始）。用于事件和记录，保证两处报的是同一个数。 */
    public int nextStepIndex() {
        return steps.size() + 1;
    }

    public int loopHits() {
        return loopHits;
    }

    public List<LoopType> loopTypes() {
        return List.copyOf(loopTypes);
    }

    /** 这次 run 是不是走的流式模型调用。两条路都用流式会有另一套风险，见 {@code ToolCallAgent#think}。 */
    public boolean streaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    /** 把这一步新产生的消息（模型的话 + 工具的回答）追加进工作列表。 */
    public void appendMessages(List<Message> newMessages) {
        this.messages.addAll(newMessages);
    }

    public void record(AgentStep step) {
        this.steps.add(step);
    }

    public void addHint(String hint) {
        this.hints.add(hint);
    }

    public void recordLoopHit(LoopSignal signal) {
        this.loopHits++;
        this.loopTypes.add(signal.type());
    }

    /** 最近一步模型说的话；一步都没走时是空串。给「问用户」和「中止」的文案用来交代上下文。 */
    public String lastAssistantText() {
        for (int i = steps.size() - 1; i >= 0; i--) {
            String text = steps.get(i).text();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    /**
     * 收尾。只接受第一次生效——终态一旦定下就不该被改写：
     * 循环的收尾路径有好几条（模型答完、看门狗叫停、步数超预算），
     * 后到的那些大多是在前一条已经结束之后才跑到的，让它们覆盖只会把真实原因盖掉。
     */
    public void finish(AgentState state, String output) {
        if (this.state.isTerminal()) {
            return;
        }
        this.state = state;
        this.output = output == null ? "" : output;
    }

    public AgentResult toResult() {
        return new AgentResult(chatId, state, output, question(), steps.size(), loopHits, loopTypes());
    }
}
