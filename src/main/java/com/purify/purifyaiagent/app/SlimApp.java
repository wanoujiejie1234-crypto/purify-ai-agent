package com.purify.purifyaiagent.app;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrievalAdvisor;
import com.purify.purifyaiagent.advisor.LoggingAdvisor;
import com.purify.purifyaiagent.advisor.ReReadingAdvisor;
import com.purify.purifyaiagent.advisor.SensitiveWordAdvisor;
import com.purify.purifyaiagent.config.PromptProperties;
import com.purify.purifyaiagent.config.VisionProperties;
import com.purify.purifyaiagent.model.SlimPlan;
import com.purify.purifyaiagent.prompt.PromptTemplateLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.template.NoOpTemplateRenderer;
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
 *           └── DashScopeDocumentRetrievalAdvisor (5)  查百炼云知识库，把切片拼进用户消息
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
 * <p><b>多模态</b>：{@link #explainImage} 走的是另一个 ChatClient（同一个 ChatModel，
 * 但换成 VL 模型名），Advisor 链与文本对话完全一致——也就是说看图同样有记忆、
 * 有敏感词拦截、有日志、也会去查一次知识库。
 *
 * <p><b>知识库（RAG）是可选的</b>：{@code purify.rag.enabled=false} 时容器里不存在
 * {@link DashScopeDocumentRetrievalAdvisor}，这里靠 {@link ObjectProvider} 拿不到就跳过，
 * 链路退化成纯模型对话，应用照常启动——这样检索服务抖动时不用改代码就能降级。
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
                   ObjectProvider<DashScopeDocumentRetrievalAdvisor> knowledgeBaseAdvisor,
                   PromptTemplateLoader promptTemplateLoader,
                   PromptProperties promptProperties,
                   VisionProperties visionProperties) {
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
        // 而不是像直接注入那样让整个应用启动失败
        knowledgeBaseAdvisor.ifAvailable(advisors::add);
        advisors.add(MessageChatMemoryAdvisor.builder(chatMemory).build());

        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(advisors)
                // 模板统一由 PromptTemplateLoader 渲染，这里关掉 ChatClient 的二次渲染：
                // 否则用户输入里出现的半角花括号会被当成模板变量，直接抛「变量未替换」的异常
                .defaultTemplateRenderer(new NoOpTemplateRenderer())
                .build();

        this.visionChatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(advisors)
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

        log.info("[SlimApp] 初始化完成：对话记忆 + 敏感词拦截 + Re-Reading + 日志{}，共 {} 个 Advisor；看图模型={}",
                knowledgeBaseAdvisor.getIfAvailable() == null ? "" : " + 云知识库检索(RAG)",
                advisors.size(),
                visionProperties.getModel());
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
                .call()
                .content();
    }

    /** 多轮对话（流式），返回逐段生成的文本。 */
    public Flux<String> chatStream(String message, String chatId) {
        return chatClient.prompt()
                .system(renderSystemPrompt())
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
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
}
