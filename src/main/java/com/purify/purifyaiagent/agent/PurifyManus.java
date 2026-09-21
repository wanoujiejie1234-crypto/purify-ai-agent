package com.purify.purifyaiagent.agent;

import com.purify.purifyaiagent.advisor.SensitiveWordChecker;
import com.purify.purifyaiagent.agent.loop.LoopGuard;
import com.purify.purifyaiagent.chat.ChatRecordRepository;
import com.purify.purifyaiagent.config.PromptProperties;
import com.purify.purifyaiagent.model.ChatRecord;
import com.purify.purifyaiagent.prompt.PromptTemplateLoader;
import com.purify.purifyaiagent.rag.KnowledgeSearch;
import com.purify.purifyaiagent.agent.tool.ToolContexts;
import com.purify.purifyaiagent.i18n.Messages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PurifyManus：会自己把任务做完的智能体。参考 OpenManus 的分层
 * （{@code BaseAgent} → {@code ToolCallAgent} → {@code Manus}），这里对应
 * {@link BaseAgent} → {@link ToolCallAgent} → 本类。
 *
 * <p>本类只做「这个具体智能体是谁」的部分，机制全在父类：
 * <ul>
 *   <li>提示词从资源文件 {@code prompts/purify-manus-system.st} 加载（变量：{nickname} {today}），
 *       改人设和边界不用动 Java 代码——和 SlimApp 一样；</li>
 *   <li>工具是注入进来的一份清单，本地工具和高德 MCP 的工具混在一起，本类不区分；</li>
 *   <li>循环检测、问用户、收尾全部由父类驱动，这里只在「工具上下文」上补一样东西。</li>
 * </ul>
 *
 * <p><b>聊天记录</b>：每轮结束都往 {@code chat_record} 表写一行（问题 + 答复 + 收尾状态 + 步数），
 * 和轻语写的是同一张表，用 scene 区分是哪条链路。注意这<b>不是</b>它的对话记忆——
 * 记忆仍然是进程内的 {@link AgentMemory}（ReAct 历史里带着工具调用，落库再读回来会断，
 * 见那个类的注释）；落库的只是「用户问了什么、最后答了什么」，供人事后查。
 *
 * <p><b>无状态。</b>本类是单例 Bean，一次 run 的所有可变状态都在 {@link AgentRun} 里。
 * 唯一挂在实例上的是「哪些会话正在跑」这个集合——它不是业务状态，是并发闸门（见 {@link #chatStream}）。
 */
@Slf4j
public class PurifyManus extends ToolCallAgent {

    /** 系统提示词模板名，对应 {@code prompts/purify-manus-system.st}。 */
    public static final String SYSTEM_TEMPLATE = "purify-manus-system";

    /**
     * 正在跑的会话。同一个会话同时发两条消息会把记忆写乱：两条 run 各自从记忆取一份历史、
     * 各自追加自己的步骤，最后谁后写谁覆盖——用户看到的是「上一句白说了」。
     * 与其在记忆里加锁（那会把两条 run 串成一条，用户等更久），不如直接拒绝后到的那条。
     */
    private final Set<String> runningConversations = ConcurrentHashMap.newKeySet();

    private final ChatRecordRepository chatRecordRepository;

    /**
     * 开场预检索用的检索入口。为 null 表示知识库没接入
     * （{@code purify.rag.enabled=false} 或 store 没配上），这时既不做预检索、
     * 也不会有 knowledgeSearch 工具（那个工具由 {@code ManusAgentConfig} 按同一个判空决定）。
     */
    @Nullable
    private final KnowledgeSearch knowledgeSearch;

    /** 敏感词判据，与轻语的 Advisor 链共用同一份（同一个 Bean）。 */
    private final SensitiveWordChecker sensitiveWordChecker;

    public PurifyManus(int maxSteps,
                       LoopGuard loopGuard,
                       AgentMemory memory,
                       ChatModel chatModel,
                       ToolCallback[] toolCallbacks,
                       ToolCallingManager toolCallingManager,
                       PromptTemplateLoader promptTemplateLoader,
                       PromptProperties promptProperties,
                       ChatRecordRepository chatRecordRepository,
                       @Nullable KnowledgeSearch knowledgeSearch,
                       SensitiveWordChecker sensitiveWordChecker) {
        super("PurifyManus",
                maxSteps,
                loopGuard,
                memory,
                chatModel,
                toolCallbacks,
                toolCallingManager,
                // 每次调用现渲染：提示词里的 {today} 必须反映当天日期，服务长期运行才不会把日期讲错
                (run) -> promptTemplateLoader.render(run.i18n(), SYSTEM_TEMPLATE, Map.of(
                        "nickname", run.i18n().isChinese()
                                ? promptProperties.getNickname()
                                : promptProperties.getNicknameEn(),
                        "today", LocalDate.now().toString())));
        this.chatRecordRepository = chatRecordRepository;
        this.knowledgeSearch = knowledgeSearch;
        this.sensitiveWordChecker = sensitiveWordChecker;

        // 念「实际装上了什么」而不是「配置里写了什么」——这两者可能不一致，
        // 与 SlimApp 那句启动日志同一个口径
        log.info("[PurifyManus] 初始化完成：最多 {} 步，挂载 {} 个工具：{}",
                maxSteps, toolNames().size(), toolNames());
        log.info("[PurifyManus] 知识库={}",
                knowledgeSearch == null
                        ? "未接入（无开场预检索，也没有 knowledgeSearch 工具）"
                        : "已接入（开场预检索 + knowledgeSearch 工具）");
    }

    /** 生成一个新的会话 ID。 */
    public String newChatId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 开场：先查敏感词，再做一次预检索。
     *
     * <p><b>为什么要在这里补这两件事</b>：本类不经过 {@code ChatClient}——父类的
     * {@code think} 是直接调 {@code ChatModel} 的，而轻语那四个 Advisor 全都挂在
     * ChatClient 上。所以 RAG 与敏感词拦截在智能体这条链路上<b>一样都不会发生</b>，
     * 必须显式补。剩下两项（Re-Reading、日志）不补：日志这边父类和工具注册表已经覆盖了，
     * Re-Reading 的理由见 {@code ManusAgentConfig} 的注释。
     *
     * <p>顺序照着轻语那条 Advisor 链抄：敏感词（order 0）在知识库（order 5）之前，
     * 被拦下的请求因此压根不会产生一次远程检索。
     *
     * <p>只查用户最初那句话，一次 run 只查一次（循环本身保证只调一次，见
     * {@code BaseAgent#prologue}）。模型中途想追查别的细节由 knowledgeSearch 工具负责——
     * 那是两件不同的事：一件是「与轻语手感一致」的保底，一件是 ReAct 语义。
     */
    @Override
    protected Flux<AgentEvent> prologue(AgentRun run) {
        return Flux.defer(() -> {
            String question = SensitiveWordChecker.lastUserText(run.messages());

            String hit = sensitiveWordChecker.match(question);
            if (hit != null) {
                log.warn("[PurifyManus] 会话 {} 命中敏感词 [{}]，本轮不调用模型", run.chatId(), hit);
                // 这里不抛 SensitiveWordException：这条链路没有 Advisor 链，异常只会从流里穿出去，
                // 被 controller 的兜底 onErrorResume 变成「服务暂时出了点问题」，
                // 用户拿到的是一句无关的报错，引导话术被吞掉。收尾成终态才是对的做法。
                //
                // 收尾成 BLOCKED 而不是 ABORTED/ERROR：这是顾问按策略主动拒绝，
                // 那段话术本身就是要展示的内容，标成出错会让界面画红框
                //
                // 留痕范围要说准，别以为「拦下了就什么都不剩」：拦截发生在开场，
                // BaseAgent 的 memory.save 不会被调用，所以这段对话**不进 AgentMemory**
                // ——模型在后续轮次里看不到它。但 controller 已经先写了一行 chat_session
                // （标题就是用户原话），流跑完 recordIfCompleted 又会往 chat_record 写一行
                // （question 是原话，answer 是引导话术）。那两张表是给人查的账本，
                // 拦截不该让账本缺一笔。用户下一句说「我开玩笑的，接着说」时模型没有
                // 这段上下文，是拿连续性换「有害内容不进模型记忆」，这个取舍是有意的
                run.finish(AgentState.BLOCKED, sensitiveWordChecker.replyMessage(run.i18n().locale()));
                return Flux.empty();
            }

            if (knowledgeSearch == null) {
                return Flux.empty();
            }
            return Flux.just(AgentEvent.retrieval(preRetrieve(run, question)));
        });
    }

    /**
     * 预检索，并把材料写进 run；返回给用户看的那句摘要。
     *
     * <p><b>检索失败只降级、不往上抛</b>，这是 {@code RagRetrieval} 已经立下的规矩：
     * 检索没帮上忙，不该反过来把对话搞砸。而且这条降级路径不是理论情况——
     * pgvector 那条链路的连接池把 {@code initializationFailTimeout} 设成了 -1，
     * 数据库连不上时应用照常启动，失败正好推迟到第一次检索。
     */
    private String preRetrieve(AgentRun run, String question) {
        if (!StringUtils.hasText(question)) {
            return run.i18n().get("agent.retrievalSkipped");
        }
        try {
            KnowledgeSearch.Result result = knowledgeSearch.search(question);
            // 材料为空时 renderAgentReference 返回空串，buildPrompt 那边据此跳过注入
            // （往 Prompt 里塞一句「知识库没查到」会诱导模型当场拒答，理由见那个方法的注释）
            run.setReferenceContext(result.renderAgentReference());
            return result.summary(run.i18n());
        }
        catch (RuntimeException exception) {
            log.warn("[PurifyManus] 会话 {} 开场预检索失败，本轮按无参考资料处理：{}",
                    run.chatId(), exception.getMessage());
            return run.i18n().get("agent.retrievalFailed", exception.getMessage());
        }
    }

    /**
     * 阻塞式跑一次，拿到最终结果。
     *
     * <p>返回值里带状态：正常答完、需要用户回答、被中止是三种不同的东西，
     * 调用方要按状态决定怎么展示（见 {@link AgentResult}）。
     *
     * <p><b>它没有对应的 HTTP 入口</b>：面向页面的只有 {@link #chatStream} 那条，
     * 因为中间过程才是这个智能体的看点（理由见 {@code PurifyManusController}）。
     * 这个方法留给不走 HTTP 的调用方和测试——它和流式那条复用同一个循环，
     * 只是把结果攒起来再返回。
     */
    public AgentResult chat(String chatId, String userId, Messages i18n, String message) {
        if (!acquire(chatId)) {
            return busy(chatId, i18n);
        }
        try {
            AgentResult result = runBlocking(chatId, userId, i18n, message);
            record(chatId, message, answerOf(result), result.state(), result.steps());
            return result;
        }
        finally {
            release(chatId);
        }
    }

    /**
     * 流式跑一次，把每一步的中间过程实时推出去。
     *
     * <p>并发闸门放在 {@code Flux.defer} 里：这个方法是「返回一个流」，不是「开始跑」——
     * 真正的执行发生在订阅时。若把闸门放在方法体里，调用方拿着流不订阅（比如请求刚进来就被
     * 网关掐了），闸门就永远关不上了。
     */
    public Flux<AgentEvent> chatStream(String chatId, String userId, Messages i18n, String message) {
        return Flux.defer(() -> {
            if (!acquire(chatId)) {
                return Flux.just(AgentEvent.error(busyMessage(i18n)));
            }
            // 流式没有「一个最终结果」可以事后取，只能在流的过程中把要落库的两样东西攒下来：
            // 终态事件（答复和收尾状态都在里面）和步数
            AtomicReference<AgentEvent> terminal = new AtomicReference<>();
            AtomicInteger steps = new AtomicInteger();

            return runStream(chatId, userId, i18n, message)
                    .doOnNext(event -> {
                        if (event.state() != null) {
                            terminal.set(event);
                        }
                        else if (event.type() == AgentEvent.Type.STEP) {
                            // 每步开头发一个 STEP，数它等于数走了几步，和 runBlocking 那条路的
                            // AgentResult.steps() 是同一个口径
                            steps.incrementAndGet();
                        }
                    })
                    .doOnComplete(() -> recordIfCompleted(chatId, message, terminal.get(), steps.get()))
                    // 正常结束、出错、客户端断开都会走到这里，闸门一定放得掉
                    .doFinally(signal -> release(chatId));
        });
    }

    /**
     * 工具能看到的上下文，比父类多一样：用户是谁。
     *
     * <p>{@code UserProfileTool} 要靠它才知道读写谁的画像，而这个值不能由模型自己填——
     * 模型会编一个出来，这次存的画像下次就找不回来了。所以它由 HTTP 层从令牌里解出来、
     * 一路传到 {@link AgentRun}，再放进模型看不见的工具上下文里。
     *
     * <p><b>user id 为空时不要往 map 里塞 null</b>：{@code ToolContext} 的底层实现可能
     * 对内容做防御性拷贝（拷贝构造通常会拒绝 null），而 {@code Map.of} 更是直接抛异常。
     * 留键缺失才是安全的退化——{@code UserProfileTool} 已经能处理「上下文里没有这个键」，
     * 会返回一句让模型转告用户的话，而不是让整个对话崩掉。
     * 正常链路上这条分支走不到（聊天接口都要求登录），它是防「万一」的。
     */
    @Override
    protected Map<String, Object> toolContext(AgentRun run) {
        // 父类放进去的 HumanInterrupt 原样留着（那是「暂停等用户」的开关，本类也要用），
        // 这里补上「用户是谁」和「在哪个会话里」。
        //
        // 键名和取值都走 ToolContexts：这边和 SlimApp 那两个入口拼的是同一份上下文，
        // 各写一遍的话，哪天加了一个工具需要的键，很容易只改了其中一边，
        // 而症状是「某个功能只在某条链路上不工作」。
        Map<String, Object> context = new HashMap<>(super.toolContext(run));
        ToolContexts.putIfPresent(context, ToolContexts.USER_ID_KEY, run.userId());
        ToolContexts.putIfPresent(context, ToolContexts.CHAT_ID_KEY, run.chatId());
        return context;
    }

    /**
     * 这一轮的「答复」是哪句话。
     *
     * <p>智能体停下来问用户时，记的是它<b>反问的那句</b>——那才是用户这一轮实际看到的回应，
     * 也正好和下一轮「用户回答」配成一对，记录读起来才连贯。
     * 中止和出错时记的是那段说明文字（说清楚卡在哪），同样该留痕。
     */
    private static String answerOf(AgentResult result) {
        return result.question() != null ? result.question() : result.output();
    }

    /**
     * 流式那一路的落库：只在流跑完、并且确实拿到了终态事件时才记。
     *
     * <p>客户端中途断开时终态事件根本不会产生，这一轮用户也没看到完整答复——
     * 记一条半截的进库，比不记更容易误导。
     */
    private void recordIfCompleted(String chatId, String question, AgentEvent terminal, int steps) {
        if (terminal == null) {
            log.debug("[PurifyManus] 会话 {} 的流没有走到终态，本轮不记聊天记录", chatId);
            return;
        }
        // 终态事件里的 text 已经是「该给用户看的那句话」：等用户回答时是问题，否则是答复
        record(chatId, question, terminal.text(), terminal.state(), steps);
    }

    /** 一轮问答落库。写失败不会抛出来（见 {@code ChatRecordRepository#save}），对话不受影响。 */
    private void record(String chatId, String question, String answer, AgentState state, int steps) {
        chatRecordRepository.save(ChatRecord.manus(chatId, question, answer, state.name(), steps));
    }

    private boolean acquire(String chatId) {
        return runningConversations.add(chatId);
    }

    private void release(String chatId) {
        runningConversations.remove(chatId);
    }

    private AgentResult busy(String chatId, Messages i18n) {
        log.warn("[PurifyManus] 会话 {} 已有一个任务在跑，拒绝并发请求", chatId);
        return new AgentResult(chatId, AgentState.ERROR, busyMessage(i18n), null, 0, 0, List.of());
    }

    /**
     * 「上个任务还没跑完」那句话。
     *
     * <p>收 {@link Messages} 而不是自己去取语言：它的一个调用点在 {@code Flux.defer} 里面
     * （见 {@link #chatStream}），那已经不在请求线程上了。
     */
    private static String busyMessage(Messages i18n) {
        return i18n.get("agent.busy");
    }
}
