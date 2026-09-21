package com.purify.purifyaiagent.agent;

import com.purify.purifyaiagent.agent.loop.LoopGuard;
import com.purify.purifyaiagent.agent.tool.HumanInterrupt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 会用工具的智能体：把 OpenManus 里 {@code ToolCallAgent} 的 think / act 两步搬了过来。
 *
 * <p><b>think</b>：把「系统提示词 + 到目前为止的消息」发给模型，模型回一段话，
 * 可能还附带「我要调这些工具」。<b>act</b>：有工具调用就执行掉，把结果拼回消息里；
 * 没有工具调用，就说明模型认为可以回答了，这次 run 到此结束。
 *
 * <p><b>工具循环由本类自己驱动，而不是交给模型框架。</b>这样做的前提是把
 * {@code internalToolExecutionEnabled} 关掉——否则 {@code DashScopeChatModel} 会在
 * {@code call()} 里自己把工具执行完再返回（见其 {@code internalCall}），
 * 我们只看得到一个最终答复，中间调了什么、调了几次、是不是在原地打转全都看不见，
 * 循环检测也就无从谈起。关掉之后模型只负责「说想调什么」，执行走
 * {@link ToolCallingManager#executeToolCalls}。
 *
 * <p>关掉内置循环并不会让模型的其它参数失效：运行时 options 会和 {@code application.yml} 里的
 * 默认值合并，且运行时优先（见 DashScope 的 {@code buildRequestPrompt}），
 * 所以模型名、temperature 仍然来自配置，这里不用也不该重复配一遍。
 *
 * <p><b>工具清单是注入进来的 {@link ToolCallback} 数组</b>，本类不知道有哪些工具、
 * 更不知道它们从哪来——本地工具和高德 MCP 的工具在它眼里完全一样。所以加工具、加 MCP server
 * 都不需要动这个文件。
 */
@Slf4j
public class ToolCallAgent extends BaseAgent {

    /** 日志和事件里工具参数/返回值的最长长度，防止一次抓网页把日志刷爆。 */
    private static final int MAX_ECHO_LENGTH = 300;

    private final ChatModel chatModel;
    private final ToolCallback[] toolCallbacks;
    private final ToolCallingManager toolCallingManager;
    private final Function<AgentRun, String> systemPrompt;

    /**
     * @param systemPrompt 每次调用时现取系统提示词。收 {@link AgentRun} 而不是不收，
     *                     是因为提示词有两处必须「现算」：一是有「今天几号」这类
     *                     每次都该重算的内容，二是<b>它得跟着这次 run 的语言走</b>——
     *                     中英文各一份模板（{@code purify-manus-system[-en].st}）。
     *                     原先这里是个 {@code Supplier}，就是卡在拿不到 run 上。
     */
    public ToolCallAgent(String name,
                         int maxSteps,
                         LoopGuard loopGuard,
                         AgentMemory memory,
                         ChatModel chatModel,
                         ToolCallback[] toolCallbacks,
                         ToolCallingManager toolCallingManager,
                         Function<AgentRun, String> systemPrompt) {
        super(name, maxSteps, loopGuard, memory);
        this.chatModel = chatModel;
        this.toolCallbacks = toolCallbacks.clone();
        this.toolCallingManager = toolCallingManager;
        this.systemPrompt = systemPrompt;
    }

    /** 挂载的工具名。给「我到底能用哪些工具」这类排查用——MCP 工具的名字和配置里不一样，光看配置对不上。 */
    public List<String> toolNames() {
        return List.of(this.toolCallbacks).stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    @Override
    protected Flux<AgentEvent> step(AgentRun run) {
        Prompt prompt = buildPrompt(run);
        ModelTurn turn = new ModelTurn();
        // think 和 act 串起来，而不是在 think 的回调里调 act：
        // 回调是在流的执行过程中触发的，在里面再拼接一段流容易把「上一步的收尾」和
        // 「下一步的开始」搅在一起。concatWith 的语义是「前面推完了再订阅后面」，边界清楚
        return think(prompt, run, turn)
                .concatWith(Flux.defer(() -> act(run, prompt, turn)));
    }

    /**
     * 把这一轮的 Prompt 发给模型，把它说的话变成事件流出来。
     *
     * <p><b>两条路：流式走 {@code stream()}，阻塞式走 {@code call()}。</b>
     * 不是偷懒图省事——流式响应里 tool_calls 是跟着分片到达的，得自己按 id 归并
     * （见 {@link ModelTurn}），多了一层可能出错的地方。REST 那条路是这个智能体的主入口，
     * 让它走「一次调用拿到完整响应」这条更成熟的链路更稳；想看模型逐字输出的走 SSE。
     */
    private Flux<AgentEvent> think(Prompt prompt, AgentRun run, ModelTurn turn) {
        Flux<ChatResponse> responses = run.streaming()
                ? chatModel.stream(prompt)
                // 这里的 defer 不能省：call() 是阻塞调用，写成 Flux.just(call(prompt)) 的话它会在
                // 「拼这一步」的时候就被执行——异常会从流的构造过程中冒出去，下面的 onErrorResume
                // 根本接不住，最后以异常的形式穿到调用方那里，而不是一个 ERROR 状态。
                // 包一层 defer，调用就挪到了订阅时，和流式那条路的行为才一致
                : Flux.defer(() -> Flux.just(chatModel.call(prompt)));

        return responses
                .doOnNext(turn::accumulate)
                .map(ToolCallAgent::textOf)
                .filter(StringUtils::hasLength)
                .map(AgentEvent::text)
                // 模型接口出错（超时、限流、key 失效）时，不往上抛：往上抛会让整条事件流以异常收场，
                // 流式那条路的调用方就得自己接住，还得自己判断「这次 run 到底怎么了」。
                // 在这里转成 ERROR 终态，两条路的行为就一致了
                .onErrorResume(error -> {
                    log.error("[{}] 模型调用失败", getName(), error);
                    // 给用户看的话从 run 上取（这里在 Reactor 线程上，读不到请求语言）
                    run.finish(AgentState.ERROR,
                            run.i18n().get("agent.modelFailed", rootMessage(error)));
                    // 这里不再单独发一个 ERROR 事件：run 已经是终态，循环收尾时会把状态和说明
                    // 统一转成一个终态事件。两处都发的话，用户会连着收到两条一模一样的报错
                    return Flux.empty();
                });
    }

    /**
     * 把模型要求的工具执行掉，并把「要调什么 / 拿到了什么」变成事件。
     *
     * <p>没有工具调用时视为任务完成——这是 ReAct 的约定：模型不再需要外部信息，
     * 它这一轮说的话就是最终答复。
     */
    private Flux<AgentEvent> act(AgentRun run, Prompt prompt, ModelTurn turn) {
        if (run.isTerminal()) {
            // think 阶段已经失败了。这里必须挡住，否则会把一次失败的 run 记成「正常答完」
            return Flux.empty();
        }

        List<AssistantMessage.ToolCall> toolCalls = turn.toolCalls();
        String text = turn.text();

        if (toolCalls.isEmpty()) {
            run.appendMessages(List.of(new AssistantMessage(text)));
            run.record(new AgentStep(run.nextStepIndex(), text, List.of(), List.of()));
            run.finish(AgentState.FINISHED, text);
            return Flux.empty();
        }

        // 把「模型说要调工具」这件事还原成一个 ChatResponse：ToolCallingManager 的入参就是它，
        // 而我们是绕过框架的自动循环自己调的，所以这一步得自己拼
        AssistantMessage assistantMessage = new AssistantMessage(text, Map.of(), toolCalls);
        ChatResponse response = ChatResponse.builder()
                .generations(List.of(new Generation(assistantMessage)))
                .build();

        ToolExecutionResult executionResult;
        try {
            executionResult = toolCallingManager.executeToolCalls(prompt, response);
        }
        catch (RuntimeException exception) {
            // 工具名对不上（模型幻觉出一个不存在的工具）、参数不是合法 JSON 都会在这里炸
            log.error("[{}] 工具执行失败", getName(), exception);
            // 同上：交给循环收尾时统一转成终态事件，不在这里重复发一条。
            // 文案同样从 run 上取
            run.finish(AgentState.ERROR,
                    run.i18n().get("agent.toolFailed", rootMessage(exception)));
            return Flux.empty();
        }

        // conversationHistory = 原来 prompt 里的全部消息 + 本轮 assistant + 本轮的 tool 应答。
        // 只取后两条：前面那些我们本来就有，整段塞回工作列表等于把历史翻倍。
        //
        // 这里按「指令条数」切片，同时还有一个承重的作用：buildPrompt 往 Prompt 里加的
        // 东西（系统提示词、开场预检索的参考资料）全都不是消息，切片正好把它们排除掉，
        // 于是注入的内容「用完就丢」，不会顺着 run.messages() 被存进记忆。
        // 往 Prompt 里加东西不用改这里，但**不能**改成往 run.messages() 里加——
        // 那样下面的下标就会错位，工具应答会被当成历史消息重新塞回去一遍
        List<Message> history = executionResult.conversationHistory();
        run.appendMessages(List.copyOf(history.subList(prompt.getInstructions().size(), history.size())));

        List<String> toolResults = toolResults(history);
        run.record(new AgentStep(run.nextStepIndex(), text, toolCalls, toolResults));

        // 事件的顺序就是实际的行动顺序：先说要调什么，再说拿回了什么。
        // 拆成一条条而不是合成一条，用户才能看出「哪次调用花了多久、返回了什么」
        List<AgentEvent> events = new ArrayList<>();
        for (AssistantMessage.ToolCall call : toolCalls) {
            events.add(AgentEvent.toolCall(call.name(), abbreviate(call.arguments())));
        }
        for (int i = 0; i < toolCalls.size(); i++) {
            String result = i < toolResults.size() ? toolResults.get(i) : "";
            events.add(AgentEvent.toolResult(toolCalls.get(i).name(), abbreviate(result)));
        }
        return Flux.fromIterable(events);
    }

    /**
     * 拼这一轮要发给模型的东西：系统提示词 + 到目前为止的全部消息 + 本轮的运行参数。
     *
     * <p>系统提示词每次都重新拼：看门狗追加的提示只对「下一次调用」有效，
     * 不进消息列表、也不落记忆——它是元指令（关于怎么做），混进对话历史里会污染记忆，
     * 用户回头翻历史会看到一句莫名其妙的话。这一点和 OpenManus 把提示追加进 system prompt 一致。
     */
    private Prompt buildPrompt(AgentRun run) {
        List<Message> instructions = new ArrayList<>();
        instructions.add(new SystemMessage(systemPrompt(run)));
        // 开场预检索到的材料只进 Prompt、不进消息列表（理由见 AgentRun#referenceContext）。
        // 位置固定在系统提示词之后，而不是插在最后一条用户消息之前：本方法每一步都会被调用，
        // 而消息列表的尾部在第一步之后会变成「assistant 说要调工具 + tool 的回答」，
        // 插在用户消息附近会让材料的位置随步骤漂移；固定在开头则每一步都在同一个位置。
        // 材料模板里会复述一遍问题（{query}），所以位置靠前不产生歧义
        if (StringUtils.hasText(run.referenceContext())) {
            instructions.add(new UserMessage(run.referenceContext()));
        }
        instructions.addAll(run.messages());
        return new Prompt(instructions, optionsFor(run));
    }

    private String systemPrompt(AgentRun run) {
        // 每次调用都现渲染一次：语言和日期都可能和上一步不同（语言在一次 run 内不会变，
        // 但日期跨零点会），而提示词本来就是要跟着这两样走的
        String base = this.systemPrompt.apply(run);
        return run.hints().isEmpty() ? base : base + String.join("", run.hints());
    }

    /**
     * 本轮的运行参数。
     *
     * <p>用与厂商无关的 {@link ToolCallingChatOptions} 而不是 {@code DashScopeChatOptions}：
     * 这个类关心的是「工具怎么调」，不该被绑在某一家模型上。具体模型会把这份运行时参数
     * 复制成自己的 options，再和配置里的默认值合并。
     */
    private ChatOptions optionsFor(AgentRun run) {
        return ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(toolCallbacks))
                // 关掉框架内置的工具循环，见类注释——不关的话本类拿不到工具调用，整套循环检测都失效
                .internalToolExecutionEnabled(false)
                .toolContext(toolContext(run))
                .build();
    }

    /**
     * 传给工具的运行上下文：模型看不到、也传不了的东西。
     *
     * <p>默认只放本次 run 的 {@link HumanInterrupt}（那个「先问用户」的开关）。
     * 子类可以在此基础上补充自己的东西——比如用户身份，工具要靠它才知道现在说话的是谁。
     * 用 Map 而不是加构造参数，是因为「一个 run 里有哪些上下文」是子类的事，
     * 基类不该为了未来的需要先摆一堆字段。
     */
    protected Map<String, Object> toolContext(AgentRun run) {
        return new HashMap<>(Map.of(HumanInterrupt.KEY, run.interrupt()));
    }

    /** 从执行结果里把每个工具的返回内容捞出来。结构对不上时返回空列表，不让它影响主流程。 */
    private static List<String> toolResults(List<Message> history) {
        if (history.isEmpty() || !(history.get(history.size() - 1) instanceof ToolResponseMessage toolResponse)) {
            return List.of();
        }
        return toolResponse.getResponses().stream()
                .map(ToolResponseMessage.ToolResponse::responseData)
                .toList();
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_ECHO_LENGTH ? text : text.substring(0, MAX_ECHO_LENGTH) + "…";
    }

    /**
     * 一轮模型响应在拼装过程中的样子。
     *
     * <p>为什么不能直接用最后一片响应：流式下每片只带一部分内容——文本是逐段来的，
     * tool_calls 也可能分片到达，任何单独一片都不是完整的答案。所以这里一边把文本拼起来，
     * 一边按 id 归并工具调用。
     *
     * <p>归并规则是「后来的补齐先前的」：同名调用取非空的工具名，参数取更长的那一份。
     * 这样「一整片带全部参数」和「参数分几片到达」两种情况的处理是一致的——
     * 前者归并结果等于最后一片，后者能把碎片拼成完整的 JSON。
     */
    private static final class ModelTurn {

        private final Map<String, AssistantMessage.ToolCall> toolCalls = new LinkedHashMap<>();
        private final StringBuilder text = new StringBuilder();

        void accumulate(ChatResponse response) {
            if (response == null || response.getResult() == null) {
                return;
            }
            AssistantMessage output = response.getResult().getOutput();
            if (output == null) {
                return;
            }
            if (output.getText() != null) {
                this.text.append(output.getText());
            }
            for (AssistantMessage.ToolCall call : output.getToolCalls()) {
                this.toolCalls.merge(call.id(), call, ModelTurn::merge);
            }
        }

        private static AssistantMessage.ToolCall merge(AssistantMessage.ToolCall earlier,
                                                       AssistantMessage.ToolCall later) {
            String name = StringUtils.hasText(later.name()) ? later.name() : earlier.name();
            String type = StringUtils.hasText(later.type()) ? later.type() : earlier.type();
            String arguments = longer(earlier.arguments(), later.arguments());
            return new AssistantMessage.ToolCall(earlier.id(), type, name, arguments);
        }

        private static String longer(String earlier, String later) {
            if (earlier == null) {
                return later;
            }
            return later != null && later.length() >= earlier.length() ? later : earlier;
        }

        String text() {
            return this.text.toString();
        }

        List<AssistantMessage.ToolCall> toolCalls() {
            return List.copyOf(this.toolCalls.values());
        }
    }
}
