package com.purify.purifyaiagent.app;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.purify.purifyaiagent.advisor.LoggingAdvisor;
import com.purify.purifyaiagent.advisor.ReReadingAdvisor;
import com.purify.purifyaiagent.advisor.SensitiveWordAdvisor;
import com.purify.purifyaiagent.config.PromptProperties;
import com.purify.purifyaiagent.config.RagProperties;
import com.purify.purifyaiagent.config.VisionProperties;
import com.purify.purifyaiagent.model.SlimPlan;
import com.purify.purifyaiagent.prompt.PromptTemplateLoader;
import com.purify.purifyaiagent.rag.KnowledgeBaseAdvisor;
import com.purify.purifyaiagent.tools.UserProfileTool;
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
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 * 不是这里写死的流程；模型要靠 {@link #toolContext} 才知道现在说话的是谁，
 * 那几个入口一个都不能漏，原因见那个方法的注释。
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

    /** 用户没写提问时，用这句当默认问题。 */
    private static final String DEFAULT_IMAGE_QUESTION = "请帮我看看这张图片，说说和我的健康管理有什么关系。";

    private final ChatClient chatClient;
    private final ChatClient visionChatClient;
    private final ChatMemory chatMemory;
    private final PromptTemplateLoader promptTemplateLoader;
    private final PromptProperties promptProperties;

    public SlimApp(ChatModel chatModel,
                   ChatMemory chatMemory,
                   SensitiveWordAdvisor sensitiveWordAdvisor,
                   ReReadingAdvisor reReadingAdvisor,
                   LoggingAdvisor loggingAdvisor,
                   ObjectProvider<KnowledgeBaseAdvisor> knowledgeBaseAdvisor,
                   ToolCallbackProvider agentTools,
                   PromptTemplateLoader promptTemplateLoader,
                   PromptProperties promptProperties,
                   VisionProperties visionProperties,
                   RagProperties ragProperties) {
        this.chatMemory = chatMemory;
        this.promptTemplateLoader = promptTemplateLoader;
        this.promptProperties = promptProperties;

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

        // 启动日志打的是「实际有没有装上」，而不是「配置里写了什么」——这两者会不一致：
        // purify.rag.store 大小写写错（比如 PGVECTOR）时两个配置类都不装配、Advisor 根本不存在，
        // 但配置值读出来仍是 PGVECTOR。只念配置的话，日志会理直气壮地说「知识库=PGVECTOR」，
        // 而对话其实一个字都没查过知识库。
        String ragState = ragAdvisor == null ? "未接入" : "已接入/" + ragProperties.getStore();
        log.info("[SlimApp] 初始化完成：对话记忆 + 敏感词拦截 + Re-Reading + 日志{}，共 {} 个 Advisor；"
                        + "工具 {} 个；看图模型={}；知识库={}",
                ragAdvisor == null ? "" : " + 知识库检索(RAG)",
                advisors.size(),
                agentTools.getToolCallbacks().length,
                visionProperties.getModel(),
                ragState);

        // 开着 RAG 却一个 Advisor 都没有，只可能是配置写错了（enabled=true 且 store 合法时，
        // 两个配置类必有一个生效）。这时对话会静默退化成纯模型闲聊，不说一声很难发现。
        // 不直接启动失败，是为了保住 purify.rag.enabled 这条应急降级路径
        if (ragProperties.isEnabled() && ragAdvisor == null) {
            log.warn("[SlimApp] purify.rag.enabled=true 但知识库检索 Advisor 没有装配上："
                    + "对话不会查任何知识库。多半是 purify.rag.store 的值不合法（必须是全小写的 "
                    + "bailian 或 pgvector），当前读到的是「{}」", ragProperties.getStore());
        }
    }

    /** 生成一个新的会话 ID。长度固定 36，与 SPRING_AI_CHAT_MEMORY.conversation_id 列宽一致。 */
    public String newChatId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 多轮对话（阻塞式）。
     *
     * @param message 用户输入
     * @param chatId  会话 ID，相同 ID 共享历史记录
     */
    public String chat(String message, String chatId) {
        return chatClient.prompt()
                .system(renderSystemPrompt())
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .toolContext(toolContext(chatId))
                .call()
                .content();
    }

    /** 多轮对话（流式），返回逐段生成的文本。 */
    public Flux<String> chatStream(String message, String chatId) {
        return chatClient.prompt()
                .system(renderSystemPrompt())
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .toolContext(toolContext(chatId))
                .stream()
                .content();
    }

    /**
     * 根据当前会话的历史，生成一份结构化的瘦身计划。
     *
     * <p>与 {@link #chat} 走同一条 Advisor 链，区别只是用 {@code entity} 让模型返回 JSON，
     * 说明 Advisor 对「文本输出」和「结构化输出」都生效。
     */
    public SlimPlan generatePlan(String message, String chatId) {
        return chatClient.prompt()
                .system(renderSystemPrompt())
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .toolContext(toolContext(chatId))
                .call()
                .entity(SlimPlan.class);
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
     */
    public String explainImage(String question, Resource image, MimeType mimeType, String chatId) {
        String ask = (question == null || question.isBlank()) ? DEFAULT_IMAGE_QUESTION : question;
        String userText = promptTemplateLoader.render(IMAGE_EXPLAIN_TEMPLATE, Map.of("question", ask));

        return visionChatClient.prompt()
                .system(promptTemplateLoader.render(VISION_SYSTEM_TEMPLATE, Map.of()))
                .user(spec -> spec.text(userText).media(mimeType, image))
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .toolContext(toolContext(chatId))
                .call()
                .content();
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
     * 渲染系统提示词。
     *
     * <p>每次请求都重新渲染，而不是在构造时渲染一次：模板里的 {@code {today}}
     * 需要反映当天日期，服务长期运行也不会把日期讲错。渲染本身只是字符串替换，开销可以忽略。
     */
    private String renderSystemPrompt() {
        return promptTemplateLoader.render(SYSTEM_TEMPLATE, Map.of(
                "nickname", promptProperties.getNickname(),
                "today", LocalDate.now().toString()));
    }

    /**
     * 每个请求都要带的工具上下文，用来回答工具那句「现在跟我说话的是谁」。
     *
     * <p>这个值不会出现在工具的 JSON Schema 里，也就是模型看不到、更传不了它——
     * 这正是我们要的：用户身份不能由模型自己填，否则它会编一个出来，
     * 这次存下的画像下次就找不回来了。
     *
     * <p><b>四个对话入口一个都不能漏。</b>凡是声明了 {@code ToolContext} 参数的工具，
     * 在没有上下文的请求里被调用时，Spring AI 会直接抛
     * {@code IllegalArgumentException: ToolContext is required by the method as an argument}——
     * 也就是说漏掉这一行不是「画像读不到」，而是整个对话直接失败。
     *
     * <p>目前用会话 ID 当用户标识（这个项目还没有登录体系）。客户端复用同一个 chatId
     * 就能跨轮次记住画像；将来接入登录后，这里换成真实用户 ID 即可，工具本身不用动。
     */
    private static Map<String, Object> toolContext(String chatId) {
        return Map.of(UserProfileTool.USER_ID_KEY, chatId);
    }
}
