package com.purify.purifyaiagent.tools;

import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 工具注册表：把「本项目的工具」和「MCP server 提供的工具」汇编成一份最终的工具清单。
 *
 * <p>这里的「本项目的工具」是那些带 {@code @Tool} 注解的普通对象（用户画像、读写文件、抓网页……），
 * 「MCP 工具」来自 {@code mcp-servers.json} 里配置的外部 server——目前是高德地图，
 * 它提供地图相关的工具（天气、地理编码、路线规划、周边搜索等）。两类工具对模型来说是同一种东西：
 * 都变成 {@link ToolCallback}，模型看得到名字和参数说明，自己决定调哪个。加一个 MCP server
 * 只需要改那个 JSON 文件，Java 代码一个字都不用动。
 *
 * <p><b>MCP 工具的名字比 server 里的原名叫得长</b>：Spring AI 会加一层前缀，
 * server 里的 {@code maps_weather} 到这里是 {@code purify_ai_agent_amap_maps_maps_weather}——
 * 前缀 = 客户端名 + server 连接名（{@code spring.ai.mcp.client.name} 和 mcp-servers.json 里的 key），
 * 拼完把非字母数字下划线短横线的字符删掉。目的是一台 client 连多个 server 时同名工具不打架。
 * 下面那行「从 MCP 取到」日志里打的就是这个带前缀的名字，对不上是正常的，别以为工具没注册上。
 *
 * <p><b>MCP 是「加」不是「换」。</b>连不上、拉不到工具清单时只丢 MCP 那部分，本地工具照常注册，
 * 应用照常启动——这和 {@code ToolConfig} 里「没配 Key 就不注册联网搜索」是同一条原则：
 * 少一个工具模型会自己用别的方式回答，而启动失败是整个服务都不可用。所以
 * {@code spring.ai.mcp.client.initialized} 特意配成了 false，握手挪到这里做，
 * 为的就是能接住异常。
 *
 * <p><b>每个工具外面都套了一层日志</b>（见 {@link LoggingToolCallback}），
 * 模型调了什么工具、传了什么参数、拿回什么结果、花了多久，都会打在 INFO 上。
 * 排查「模型为什么答错了」时，这段日志基本是唯一能说明问题的东西——
 * 光看模型的回答分不清它是查了工具没查到，还是压根没查。
 *
 * <p>这个类不依赖 Spring 容器，{@code new} 出来就能用，方便单独测。
 */
@Slf4j
public class AgentToolRegistry {

    /** 日志里参数的最大长度。工具参数一般很短，超长的多半是模型把正文塞进来了。 */
    private static final int MAX_ARG_LENGTH = 500;

    /** 日志里返回值的最大长度。MCP 的路线规划返回的是整段 JSON，不截断会刷屏。 */
    private static final int MAX_RESULT_LENGTH = 800;

    private final List<Object> localTools;
    private final List<McpSyncClient> mcpClients;

    /**
     * @param localTools 带 {@code @Tool} 注解的工具对象，由 {@code ToolConfig} 决定哪些够格进来
     * @param mcpClients MCP 客户端，来自 Spring AI 的自动配置；没配 MCP 时传空列表即可
     */
    public AgentToolRegistry(List<Object> localTools, List<McpSyncClient> mcpClients) {
        this.localTools = List.copyOf(localTools);
        this.mcpClients = List.copyOf(mcpClients);
    }

    /**
     * 注册表唯一的出口：一份可以直接交给 {@code ChatClient} 的工具清单。
     *
     * <p>返回的是套过日志的那一层，所以拿到它之后调用工具就会留下日志，不需要调用方再做什么。
     */
    public ToolCallback[] callbacks() {
        List<ToolCallback> callbacks = new ArrayList<>(List.of(ToolCallbacks.from(localTools.toArray())));
        callbacks.addAll(mcpToolCallbacks());

        assertNoDuplicateNames(callbacks);

        log.info("[AgentToolRegistry] 工具注册完成，共 {} 个：{}", callbacks.size(), namesOf(callbacks));
        return callbacks.stream().map(LoggingToolCallback::new).toArray(ToolCallback[]::new);
    }

    /** 连上每个 MCP server 并把它提供的工具收进来；单个 server 出问题不影响其余的，也不影响本地工具。 */
    private List<ToolCallback> mcpToolCallbacks() {
        if (mcpClients.isEmpty()) {
            log.warn("[AgentToolRegistry] 没有可用的 MCP 客户端：mcp-servers.json 里没配 server，"
                    + "或者 spring.ai.mcp.client.enabled=false。本次不注册任何 MCP 工具。");
            return List.of();
        }

        List<McpSyncClient> connected = new ArrayList<>();
        for (McpSyncClient client : mcpClients) {
            try {
                // 配的是 initialized=false，握手在这里做。已经初始化过就不重复发（重复握手会被 server 拒）
                if (!client.isInitialized()) {
                    client.initialize();
                }
                connected.add(client);
                log.info("[AgentToolRegistry] MCP server 已连接：{}", serverName(client));
            }
            catch (Exception e) {
                // 常见原因：npx/node 没装、包拉不下来、网络不通、AMAP_MAPS_API_KEY 无效
                log.warn("[AgentToolRegistry] MCP server 连接失败，跳过它提供的全部工具：{} | 原因={}",
                        serverName(client), e.getMessage());
            }
        }
        if (connected.isEmpty()) {
            return List.of();
        }

        try {
            // 内部会对每个 client 调一次 listTools，也就是这里才真正知道 server 提供了哪些工具
            List<ToolCallback> callbacks = SyncMcpToolCallbackProvider.syncToolCallbacks(connected);
            log.info("[AgentToolRegistry] 从 MCP 取到 {} 个工具：{}", callbacks.size(), namesOf(callbacks));
            return callbacks;
        }
        catch (Exception e) {
            log.warn("[AgentToolRegistry] 拉取 MCP 工具清单失败，本次不注册 MCP 工具：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 失败的 server 连握手都没成，拿不到它的 serverInfo，退回用客户端名——
     * 那个名字里带着连接名（形如 {@code purify-ai-agent - amap-maps}），定位问题够用了。
     */
    private static String serverName(McpSyncClient client) {
        return client.getServerInfo() != null ? client.getServerInfo().name() : client.getClientInfo().name();
    }

    /**
     * 重名工具必须炸出来，不能静默留一个——模型看到的是一份名字到工具的映射，
     * 两个工具同名时它调哪个由框架的遍历顺序决定，重名工具里必有一个永远调不到。
     *
     * <p>公开出来是给「在共享工具之外还要加自己的工具」的场景复用的：
     * 智能体（{@code PurifyManus}）会在本地工具 + MCP 工具之外再挂一个自己的控制工具，
     * 合并之后同样要查一遍重名，而那一份里有没有同名工具是运行期才知道的。
     * 判断逻辑只该有一份，所以从这里开放，而不是在调用方再抄一遍。
     *
     * @throws IllegalStateException 存在重名工具
     */
    public static void assertNoDuplicateNames(List<ToolCallback> callbacks) {
        List<String> duplicated = callbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.groupingBy(name -> name, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();

        if (!duplicated.isEmpty()) {
            throw new IllegalStateException("工具名重复：" + duplicated
                    + "。同一份工具清单里不能有同名工具，否则模型调它的时候分不清调的是哪一个");
        }
    }

    private static String namesOf(List<ToolCallback> callbacks) {
        return callbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.joining(", "));
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...(已截断)";
    }

    /**
     * 给工具套一层日志的装饰器，本身不改变任何行为：定义、元数据、调用全部原样转发给被包的那个。
     *
     * <p>为什么用装饰器而不是工具类里自己打日志：日志要对本地工具和 MCP 工具<b>一视同仁</b>，
     * 而 MCP 工具是框架生成的，没有可以插日志的地方。包在这里，两边就都是同一套格式，
     * 看日志时不用先分辨这个工具是谁提供的。
     *
     * <p>两个 {@code call} 重载都要实现，不能只写一个再去调另一个：
     * {@link ToolCallback} 里带 {@link ToolContext} 的那个是 default 方法，默认实现会丢掉上下文，
     * 而声明了 {@code ToolContext} 参数的工具（比如用户画像）少了它直接就抛异常。
     */
    private static final class LoggingToolCallback implements ToolCallback {

        private final ToolCallback delegate;

        private LoggingToolCallback(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return invoke(toolInput, () -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return invoke(toolInput, () -> delegate.call(toolInput, toolContext));
        }

        private String invoke(String toolInput, Supplier<String> call) {
            String name = delegate.getToolDefinition().name();
            long start = System.currentTimeMillis();
            log.info("[Tool] >>> {}({})", name, abbreviate(toolInput, MAX_ARG_LENGTH));
            try {
                String result = call.get();
                log.info("[Tool] <<< {} 完成，耗时 {}ms，返回：{}",
                        name, System.currentTimeMillis() - start, abbreviate(result, MAX_RESULT_LENGTH));
                return result;
            }
            catch (RuntimeException e) {
                // 异常照样往外抛：工具执行失败时 Spring AI 会把它作为错误信息回给模型，
                // 模型据此再决定重试还是换个说法。这里只是多留一行日志，不改变这条链路。
                log.warn("[Tool] <<< {} 失败，耗时 {}ms：{}", name, System.currentTimeMillis() - start, e.getMessage());
                throw e;
            }
        }
    }
}
