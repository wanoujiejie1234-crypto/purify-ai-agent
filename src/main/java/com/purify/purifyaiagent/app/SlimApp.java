package com.purify.purifyaiagent.app;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.purify.purifyaiagent.advisor.LoggingAdvisor;
import com.purify.purifyaiagent.advisor.ReReadingAdvisor;
import com.purify.purifyaiagent.advisor.SensitiveWordAdvisor;
import com.purify.purifyaiagent.agent.tool.ToolContexts;
import com.purify.purifyaiagent.chat.ChatRecordRepository;
import com.purify.purifyaiagent.chat.ChatScene;
import com.purify.purifyaiagent.config.PromptProperties;
import com.purify.purifyaiagent.i18n.Messages;
import com.purify.purifyaiagent.config.RagProperties;
import com.purify.purifyaiagent.config.VisionProperties;
import com.purify.purifyaiagent.model.ChatRecord;
import com.purify.purifyaiagent.prompt.PromptTemplateLoader;
import com.purify.purifyaiagent.rag.KnowledgeBaseAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.template.NoOpTemplateRenderer;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 「轻语」健康瘦身顾问应用。
 *
 * <p><b>提示词全部放在资源文件里</b>（{@code src/main/resources/prompts/*.st}），
 * 由 {@link PromptTemplateLoader} 加载并渲染，改提示词不需要动 Java 代码：
 * <pre>
 *   slim-app-system.st   文本对话的系统提示词（变量：{nickname} {today}）
 *   vision-system.st     看图对话的系统提示词
 *   image-explain.st     看图对话的用户消息模板（变量：{question}）
 * </pre>
 *
 * <p>Advisor 链的组装顺序（order 由各 Advisor 自己声明，ChatClient 会按 order 升序排列）：
 * <pre>
 *   MessageChatMemoryAdvisor (order ≈ Integer.MIN_VALUE)
 *     └── SensitiveWordAdvisor (0)      命中高危词直接拦截
 *           └── KnowledgeBaseAdvisor (5)  查知识库，把切片拼进用户消息
 *                                                      （内置关键词路由：不相关的不查，只涉及一类时只查这一类）
 *                 └── ReReadingAdvisor (10)   改写最后一条用户消息
 *                       └── LoggingAdvisor (20)  记录发给模型的完整内容与耗时
 *                             └── 真正调用模型
 * </pre>
 *
 * <p><b>检索排在 Re-Reading 之前</b>是有意的：拿用户原话来查知识库，
 * 而不是拿被追加了「请重新阅读问题」指令的改写版；排在敏感词之后则是为了
 * 让被拦下的请求压根不产生这一次远程检索。
 *
 * <p>记忆 Advisor 的 order 最小、最靠外层，因此它先把 MySQL 里的历史消息注入 Prompt，
 * 后面的 Advisor 看到的才是「带上下文」的完整请求；同时它记录进库的也是用户原始输入，
 * 不会被 Re-Reading 追加的指令污染。
 *
 * <p><b>工具</b>：模型能调哪些工具，全部由 {@code ToolConfig} 一处登记，这里只收一个
 * {@link ToolCallbackProvider}，不引用任何一个具体工具类——加工具不用改这个文件。
 * 工具挂上之后，「制定方案前先读用户画像」是模型照着工具描述自己做的决定，
 * 不是这里写死的流程；模型要靠 {@link toolContext} 才知道现在说话的是谁，
 * 那几个入口一个都不能漏，原因见那个方法的注释。
 *
 * <p><b>聊天记录</b>：每个问答入口在返回前都会往 {@code chat_record} 表写一行（问题 + 答复），
 * 由 {@link ChatRecordRepository} 落库，和智能体 {@code PurifyManus} 写的是同一张表，
 * 用 scene 区分是哪条链路。它和上面的对话记忆是两件事：记忆决定模型下一轮看到什么，
 * 记录是给人查的账本——所以记录写失败不影响对话，也正因为如此，
 * 每个入口都要显式写这一行，靠不了 Advisor 自动完成。
 *
 * <p><b>多模态</b>：{@link #explainImage} 走的是另一个 ChatClient（同一个 ChatModel，
 * 但换成 VL 模型名），Advisor 链与文本对话完全一致——也就是说看图同样有记忆、
 * 有敏感词拦截、有日志、也会去查一次知识库。
 *
 * <p><b>知识库（RAG）是可选的，而且是可换的</b>：具体用哪一条链路由
 * {@code purify.rag.store} 决定（百炼云知识库 / 本地 pgvector），两条链路产出的都是
 * {@link KnowledgeBaseAdvisor}，这里按这个接口注入、拿不到就跳过。所以：
 * <ul>
 *   <li>{@code purify.rag.enabled=false} 或条件没配对上时，容器里没有这个 Bean，
 *       链路退化成纯模型对话，应用照常启动——检索服务抖动时不用改代码就能降级；</li>
 *   <li>换 store 只影响下面查到的是谁，这个类的代码一个字都不用动。</li>
 * </ul>
 */
@Slf4j
@Component
public class SlimApp {

    /** 文本对话的系统提示词模板名。 */
    public static final String SYSTEM_TEMPLATE = "slim-app-system";

    /** 看图对话的系统提示词模板名。 */
    public static final String VISION_SYSTEM_TEMPLATE = "vision-system";

    /** 看图对话的用户消息模板名。 */
    public static final String IMAGE_EXPLAIN_TEMPLATE = "image-explain";

    /**
     * 用户没写提问时，用这句当默认问题。
     *
     * <p>它会被拼进**发给模型**的消息里，所以也走语言包：模型模仿的正是它收到的那段
     * 上下文，给它递一句中文提问，它多半就回中文——哪怕界面上全是英文。
     */
    private static final String DEFAULT_IMAGE_QUESTION_KEY = "slim.image.defaultQuestion";

    private final ChatClient chatClient;
    private final ChatClient visionChatClient;
    private final ChatMemory chatMemory;
    private final ChatRecordRepository chatRecordRepository;
    private final PromptTemplateLoader promptTemplateLoader;
    private final PromptProperties promptProperties;

    public SlimApp(ChatModel chatModel,
                   ChatMemory chatMemory,
                   SensitiveWordAdvisor sensitiveWordAdvisor,
                   ReReadingAdvisor reReadingAdvisor,
                   LoggingAdvisor loggingAdvisor,
                   ObjectProvider<KnowledgeBaseAdvisor> knowledgeBaseAdvisor,
                   // 显式点名要哪一个，而不是「按类型拿唯一那个」：
                   // MCP 的自动配置也会产出同类型的 Bean，靠"恰好只有一个候选"来保证不出错太脆
                   // （IDEA 就会因此报红）。点名之后拿不到会直接报「没有这个 Bean」，指向明确。
                   // 那份自动配置当前是关着的，见 application.yml 的 mcp.client.toolcallback.enabled
                   @Qualifier("agentToolCallbacks") ToolCallbackProvider agentTools,
                   PromptTemplateLoader promptTemplateLoader,
                   PromptProperties promptProperties,
                   VisionProperties visionProperties,
                   RagProperties ragProperties,
                   ChatRecordRepository chatRecordRepository) {
        this.chatMemory = chatMemory;
        this.promptTemplateLoader = promptTemplateLoader;
        this.promptProperties = promptProperties;
        this.chatRecordRepository = chatRecordRepository;

        // 两个 ChatClient 共用同一条 Advisor 链：Advisor 本身无状态，可以安全复用。
        // 这里的书写顺序不影响执行顺序，真正决定先后的是各自的 order，ChatClient 会升序排。
        List<Advisor> advisors = new ArrayList<>(List.of(
                sensitiveWordAdvisor,
                reReadingAdvisor,
                loggingAdvisor));
        // 知识库没启用时容器里没有这个 Bean，ifAvailable 会安静地跳过，
        // 而不是像直接注入那样让整个应用启动失败。
        // 拿到的这个引用还要用于下面的启动日志，所以只取一次，不重复调用 getIfAvailable
        KnowledgeBaseAdvisor ragAdvisor = knowledgeBaseAdvisor.getIfAvailable();
        if (ragAdvisor != null) {
            advisors.add(ragAdvisor);
        }
        advisors.add(MessageChatMemoryAdvisor.builder(chatMemory).build());

        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(advisors)
                // 工具来自 ToolConfig 这一个注册表，这里不关心具体有哪些
                .defaultToolCallbacks(agentTools)
                // 模板统一由 PromptTemplateLoader 渲染，这里关掉 ChatClient 的二次渲染：
                // 否则用户输入里出现的半角花括号会被当成模板变量，直接抛「变量未替换」的异常
                .defaultTemplateRenderer(new NoOpTemplateRenderer())
                .build();

        this.visionChatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(advisors)
                .defaultToolCallbacks(agentTools)
                .defaultTemplateRenderer(new NoOpTemplateRenderer())
                // 换模型只影响「读不读得懂图」，其余链路不变；
                // 这里没写的参数（如 temperature）会与 application.yml 里的默认值合并
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(visionProperties.getModel())
                        // 关键开关，缺了它一定会失败：DashScopeApi 是用这个布尔值来选端点的——
                        //   false → /api/v1/services/aigc/text-generation/generation
                        //   true  → /api/v1/services/aigc/multimodal-generation/generation
                        // 默认值是 false，所以不带图片的普通对话没问题；一旦带上 Media 却仍走文本端点，
                        // DashScope 会返回 HTTP 400 "url error, please check url！"
                        .withMultiModel(true)
                        .withVlHighResolutionImages(visionProperties.isHighResolutionImages())
                        .build())
                .build();

        // 启动日志打的是「实际有没有装上」，而不是「配置里写了什么」——这两者可能不一致。
        // （曾经举的例子是「store 大小写写错会导致都不装配」，那个说法是错的：
        //   条件的 havingValue 比较不区分大小写，大写也照样能装配。但「实际状态与配置值
        //   可能不一致」这件事本身仍然成立，所以这条日志照样念实际状态而不是配置值。）
        // 枚举名是全大写的（PGVECTOR），直接打出来和 yml 里写的 pgvector 对不上，
        // 排查时容易让人怀疑是不是读到了别的值，所以统一转成小写再打
        String ragState = ragAdvisor == null
                ? "未接入"
                : "已接入/" + ragProperties.getStore().name().toLowerCase(Locale.ROOT);
        log.info("[SlimApp] 初始化完成：对话记忆 + 敏感词拦截 + Re-Reading + 日志{}，共 {} 个 Advisor；"
                        + "工具 {} 个；看图模型={}；知识库={}",
                ragAdvisor == null ? "" : " + 知识库检索(RAG)",
                advisors.size(),
                agentTools.getToolCallbacks().length,
                visionProperties.getModel(),
                ragState);

        // 开着 RAG 却一个 Advisor 都没有，说明装配层出了问题（store 的取值合法时，
        // bailian / pgvector 两种都会装配出恰好一个 Advisor）。这时对话会静默退化
        // 成纯模型闲聊，不说一声很难发现。不直接启动失败，是为了保住
        // purify.rag.enabled 这条应急降级路径。
        //
        // 注意这条 WARN 的实际可达性很低：store 写了不认识的枚举值会在绑定期就抛异常，
        // 根本走不到这里。它更像是装配条件被改坏时的一道兜底，留着不碍事
        if (ragProperties.isEnabled() && ragAdvisor == null) {
            log.warn("[SlimApp] purify.rag.enabled=true 但知识库检索 Advisor 没有装配上："
                    + "对话不会查任何知识库。请检查 purify.rag.store 的取值"
                    + "（bailian 或 pgvector）以及各 RAG 配置类的装配条件，当前读到的是「{}」",
                    ragProperties.getStore());
        }
    }

    /** 生成一个新的会话 ID。长度固定 36，与 SPRING_AI_CHAT_MEMORY.conversation_id 列宽一致。 */
    public String newChatId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 多轮对话（阻塞式）。
     *
     * <p><b>它没有对应的 HTTP 入口</b>：接口层走的是 {@link #chatStream} 那条（理由见
     * {@code SlimAppController}——EventSource 自动重连会把同一句话再问一遍）。
     * 这个方法留给不走 HTTP 的调用方和测试。
     *
     * @param message 用户输入
     * @param chatId  会话 ID，相同 ID 共享历史记录
     * @param userId  发起这次对话的用户 id。它和 {@code chatId} <b>都是不透明的字符串，
     *                写反了能编译通过</b>，而表现是用户画像被存到了会话 id 上——
     *                所有入口都要保持 {@code (message, chatId, userId)} 这个顺序
     */
    public String chat(String message, String chatId, String userId, Messages i18n) {
        String reply = chatClient.prompt()
                .system(renderSystemPrompt(i18n))
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .toolContext(ToolContexts.of(userId, chatId))
                .call()
                .content();
        record(chatId, ChatScene.SLIM, message, reply);
        return reply;
    }

    /** 多轮对话（流式），返回逐段生成的文本。参数顺序同 {@link #chat}。 */
    public Flux<String> chatStream(String message, String chatId, String userId, Messages i18n) {
        // 流式拿不到一个「最终的字符串」，只能自己把分片攒起来；
        // 攒的动作和 LoggingAdvisor 汇总日志是同一个套路
        StringBuilder reply = new StringBuilder();
        return chatClient.prompt()
                .system(renderSystemPrompt(i18n))
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .toolContext(ToolContexts.of(userId, chatId))
                .stream()
                .content()
                .doOnNext(reply::append)
                // 只在流正常结束时记一笔。中途断了说明用户没拿到完整答复，
                // 记一条残缺的答案进库，比不记更容易误导后来看记录的人
                .doOnComplete(() -> record(chatId, ChatScene.SLIM, message, reply.toString()));
    }

    /**
     * 看图解读（多模态）。
     *
     * <p>与 {@link #chat} 共用同一份会话记忆，所以「先发图再追问」是连贯的。
     * 但有两点边界要知道：
     * <ul>
     *   <li>图片本身不会入库（记忆表只存文本），下一轮再问「刚才那张图」时模型已经看不到图了；</li>
     *   <li>敏感词拦截只能扫文本提问，扫不了图片内容，图片里的风险内容由视觉模型的
     *       系统提示词（{@code vision-system.st}）来约束。</li>
     * </ul>
     *
     * @param question 用户针对图片的提问，为空时使用默认提问
     * @param image    图片资源，交给模型前会整体读成字节
     * @param mimeType 图片 MIME 类型，决定模型按什么格式解码
     * @param chatId   会话 ID
     * @param userId   发起这次对话的用户 id，参数顺序同 {@link #chat}
     */
    public String explainImage(String question, Resource image, MimeType mimeType, String chatId, String userId,
                               Messages i18n) {
        String ask = (question == null || question.isBlank())
                ? i18n.get(DEFAULT_IMAGE_QUESTION_KEY)
                : question;
        String userText = promptTemplateLoader.render(i18n, IMAGE_EXPLAIN_TEMPLATE, Map.of("question", ask));

        String reply = visionChatClient.prompt()
                .system(promptTemplateLoader.render(i18n, VISION_SYSTEM_TEMPLATE, Map.of()))
                .user(spec -> spec.text(userText).media(mimeType, image))
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .toolContext(ToolContexts.of(userId, chatId))
                .call()
                .content();
        // 记的是 ask（用户没提问时那句默认问题），不是模板渲染后的正文——
        // 记录要能直接反映用户说了什么，模板细节不该漏进去
        record(chatId, ChatScene.SLIM_IMAGE, ask, reply);
        return reply;
    }

    /** 读取某个会话的完整历史（来自 MySQL）。 */
    public List<Message> history(String chatId) {
        return chatMemory.get(chatId);
    }

    /** 清空某个会话的历史。 */
    public void clearHistory(String chatId) {
        chatMemory.clear(chatId);
    }

    /**
     * 把这一轮问答记进 {@code chat_record} 表。
     *
     * <p>记在应用层而不是 controller：入口不止 HTTP 一个（测试、将来可能的定时任务都是直接调这里），
     * 而记录应该跟着「这次对话真的发生了」走，不是跟着「有没有走 HTTP」走。
     *
     * <p>写库失败不往外抛（{@link ChatRecordRepository#save} 自己兜住了），
     * 所以这里不用再兜一层异常，也不用担心它把一次已经答完的对话变成失败。
     */
    private void record(String chatId, ChatScene scene, String question, String answer) {
        chatRecordRepository.save(ChatRecord.slim(chatId, scene, question, answer));
    }
    /**
     * 渲染系统提示词。
     *
     * <p>每次请求都重新渲染，而不是在构造时渲染一次：模板里的 {@code {today}}
     * 需要反映当天日期，服务长期运行也不会把日期讲错。渲染本身只是字符串替换，开销可以忽略。
     */
    /**
     * 拼这一轮的系统提示词。
     *
     * <p><b>它跟着语言走。</b>{@code slim-app-system.st} 里写着「使用简体中文」，
     * 拿去给英文用户用，模型会一路用中文回答——哪怕界面上全是英文。
     * 所以英文走 {@code -en} 那一份。称呼同理：「朋友」对英文用户是个看不懂的词。
     */
    private String renderSystemPrompt(Messages i18n) {
        return promptTemplateLoader.render(i18n, SYSTEM_TEMPLATE, Map.of(
                "nickname", i18n.isChinese()
                        ? promptProperties.getNickname()
                        : promptProperties.getNicknameEn(),
                "today", LocalDate.now().toString()));
    }

    /*
     * 工具上下文不在这里拼，统一走 `ToolContexts.of(userId, chatId)`——
     * 上面三个入口（chat / chatStream / explainImage）都调它，一个都不能漏。
     *
     * 原先这里是一个私有的 `toolContext(userId)`，user id 为空时返回空 map。
     * 那是个雷：Spring AI 在「方法声明了 ToolContext 参数、而传进来的 map 是空的」
     * 时会直接抛 IllegalArgumentException，所以漏传的代价不是「画像读不到」
     * 而是整轮对话失败。ToolContexts 里的实现保证 map 永远非空，
     * 顺便把「工具也要知道自己在哪个会话里」补上了（资料库归档要用 chatId）。
     */
}
