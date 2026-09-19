package com.purify.purifyaiagent.config;

import com.purify.purifyaiagent.profile.UserProfileRepository;
import com.purify.purifyaiagent.tools.FileOperationTool;
import com.purify.purifyaiagent.tools.PDFGenerationTool;
import com.purify.purifyaiagent.tools.ResourceDownloadTool;
import com.purify.purifyaiagent.tools.UserProfileTool;
import com.purify.purifyaiagent.tools.WebScrapingTool;
import com.purify.purifyaiagent.tools.WebSearchTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具的统一注册处。
 *
 * <p>和 {@code AdvisorConfig} 是同一套做法：工具类本身写成普通类（依赖走构造器，
 * 方便单独 new 出来测），在这里统一装配成 Bean，再由 {@code SlimApp} 一次性挂到 ChatClient 上。
 * 好处是加一个新工具只需要动这一个文件——注册表在这里，谁是工具的答案也在这里。
 *
 * <p><b>唯一的出口是 {@link #agentToolCallbacks}。</b>{@code SlimApp} 只依赖它，
 * 不直接引用任何一个具体的工具类，所以增删工具不需要改对话代码。
 *
 * <p><b>需要外部配置、又没配的工具不会被注册。</b>比如没填 {@code searchapi.api-key} 就没有联网搜索。
 * 这样做的理由：注册一个每次调用都只会报「没配置」的工具，比没有这个工具更糟——
 * 模型会看到它、会去调它、会拿回一句报错，最后把这段报错转述给用户。
 * 少一个工具则没有任何副作用，模型本来就会用别的方式回答。
 * 启动日志里会把最终注册了哪些工具、哪些因为没配置被跳过，都打出来。
 */
@Slf4j
@Configuration
public class ToolConfig {

    /**
     * 用户画像的读写。
     *
     * <p>注入的是容器里的 {@link JdbcTemplate}，也就是 MySQL 那个——
     * pgvector 链路用的 JdbcTemplate 刻意没有注册成 Bean（见 {@code PgVectorRagConfig}），
     * 所以这里按类型注入不会拿错。
     */
    @Bean
    public UserProfileRepository userProfileRepository(JdbcTemplate jdbcTemplate) {
        return new UserProfileRepository(jdbcTemplate);
    }

    @Bean
    public UserProfileTool userProfileTool(UserProfileRepository userProfileRepository) {
        return new UserProfileTool(userProfileRepository);
    }

    @Bean
    public FileOperationTool fileOperationTool() {
        return new FileOperationTool();
    }

    @Bean
    public WebScrapingTool webScrapingTool() {
        return new WebScrapingTool();
    }

    /** 下载工具要回一个能点开的链接，前缀只能从配置里拿（工具不在 HTTP 请求上下文里）。 */
    @Bean
    public ResourceDownloadTool resourceDownloadTool(ServerProperties serverProperties) {
        return new ResourceDownloadTool(serverProperties.getBaseUrl());
    }

    /**
     * PDF 生成工具。
     *
     * <p>这里无条件创建，但 {@code aliyun.oss.*} 没配齐时它不会被放进工具列表（见下面的汇总方法）——
     * 之所以还是要成 Bean，是因为它持有 OSS 客户端，需要容器在关闭时调到 {@code @PreDestroy}。
     * 客户端本身是懒创建的，所以「建了但没用」不会有任何网络开销。
     */
    @Bean
    public PDFGenerationTool pdfGenerationTool(AliyunOssProperties aliyunOssProperties) {
        return new PDFGenerationTool(aliyunOssProperties);
    }

    /**
     * 所有工具的汇总，也是 {@code SlimApp} 唯一依赖的工具 Bean。
     *
     * <p>实现是 {@link ToolCallbackProvider} 而不是 {@code ToolCallback[]}：
     * 直接往容器里放一个数组类型的 Bean，按类型注入时容易和「收集所有 ToolCallback Bean」
     * 的语义撞车（到底注入的是我声明的那一个，还是容器收集出来的一堆），
     * 换成一个语义明确的类型就没有这层歧义。ChatClient 的
     * {@code defaultToolCallbacks(ToolCallbackProvider...)} 正好收它。
     */
    @Bean
    public ToolCallbackProvider agentToolCallbacks(
            UserProfileTool userProfileTool,
            FileOperationTool fileOperationTool,
            WebScrapingTool webScrapingTool,
            ResourceDownloadTool resourceDownloadTool,
            PDFGenerationTool pdfGenerationTool,
            SearchApiProperties searchApiProperties,
            AliyunOssProperties aliyunOssProperties) {

        List<Object> tools = new ArrayList<>(List.of(
                userProfileTool,
                fileOperationTool,
                webScrapingTool,
                resourceDownloadTool));

        if (aliyunOssProperties.isConfigured()) {
            tools.add(pdfGenerationTool);
        } else {
            log.warn("[ToolConfig] 未注册「生成 PDF」：aliyun.oss 的 endpoint / bucket / "
                    + "access-key-id / access-key-secret 没有配齐（凭证放 application-local.yml）");
        }

        if (searchApiProperties.isConfigured()) {
            // 这个工具不持有任何需要关闭的资源，所以直接在这里 new，不必再做成一个 Bean
            tools.add(new WebSearchTool(searchApiProperties.getApiKey()));
        } else {
            log.warn("[ToolConfig] 未注册「联网搜索」：没有配置 searchapi.api-key");
        }

        // from(Object...) 会扫一遍每个对象上的 @Tool 方法并生成 JSON Schema，
        // 顺便校验工具名有没有重名（重了会直接抛异常，不会静默丢一个）
        ToolCallback[] callbacks = ToolCallbacks.from(tools.toArray());
        log.info("[ToolConfig] 已注册 {} 个工具：{}", callbacks.length, namesOf(callbacks));
        return ToolCallbackProvider.from(callbacks);
    }

    /** 启动日志里打工具名，方便一眼看出模型到底能调哪些。 */
    private static String namesOf(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.joining(", "));
    }
}
