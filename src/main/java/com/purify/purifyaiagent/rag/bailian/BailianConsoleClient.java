package com.purify.purifyaiagent.rag.bailian;

import com.aliyun.bailian20231229.Client;
import com.aliyun.bailian20231229.models.ListChunksRequest;
import com.aliyun.bailian20231229.models.ListChunksResponseBody;
import com.aliyun.bailian20231229.models.ListIndexDocumentsRequest;
import com.aliyun.bailian20231229.models.ListIndexDocumentsResponseBody;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.purify.purifyaiagent.config.BailianKbProperties;
import com.purify.purifyaiagent.exception.UpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 百炼<b>管控面</b>的薄封装：列出一个云知识库下的文件清单与切片。
 *
 * <p><b>为什么需要它，为什么是管控面</b>：项目原来用的 {@code DashScopeApi} 是<i>数据面</i>，
 * 它和知识库相关的动作全是「点对点」的——给一个 query 去检索、给一个已知的 id 去删、
 * 把本地文本送去切分——<b>没有任何「列出」语义</b>。所以「百炼上现在有哪些文件」
 * 这个问题，数据面回答不了，只能走管控面（产品码 {@code bailian}，2023-12-29）。
 * 这两套是完全独立的两套接口和两套认证，不要试图用一个客户端打通。
 *
 * <p><b>本类只做翻译，不做判断。</b>它把 SDK 的请求/响应翻成项目自己的 record、
 * 把各种失败翻成 {@link UpstreamException}，仅此而已。至于「哪份该同步」「分类用哪个」
 * 这些都属于业务，在 {@link BailianKbSyncService} 里。分开是因为 SDK 的类型
 * （{@code ListIndexDocumentsResponseBody$...DataDocuments} 这种）不该漏到业务代码里，
 * 否则换个 SDK 版本就要改业务逻辑。
 *
 * <p><b>客户端是懒建的</b>：AK/SK 没配的时候 Bean 照样构造得出来，
 * 第一次真正调用才失败。这样「没配」不会变成「应用起不来」——那个后果太重了，
 * 而这个功能只是管理页上的一张卡片。
 */
@Slf4j
public class BailianConsoleClient {

    /** 建连超时。管控面在国内，正常都在几十毫秒内。 */
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    /**
     * 读超时。给得比建连宽，因为 {@code ListIndexDocuments} 要遍历知识库的文档列表，
     * 大库会慢一些。
     */
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    private final BailianKbProperties properties;

    /** 已解析好的业务空间 ID（回落规则见 {@link BailianKbProperties#getWorkspaceId()}）。 */
    private final String workspaceId;

    /**
     * {@code purify.rag.bailian.index-id} 留空时，用它去换一个来。
     *
     * <p>通常接的是数据面的 {@code getPipelineIdByName} —— 管控面的 IndexId 和数据面的
     * pipeline_id 到底是不是同一个值，本来就要靠 {@code /probe} 实测。
     * 实测下来一样，这个配置项就可以一直空着；不一样再填。
     *
     * <p><b>做成 Supplier 而不是在装配期解析好</b>：换 pipeline_id 是一次 HTTP 调用，
     * 放在构造器里就等于「启动时先连一次百炼」——知识库同步只是管理页上的一张卡片，
     * 不该让整个应用起不来。
     */
    private final Supplier<String> indexIdFallback;

    /** 懒建。见类注释。 */
    private volatile Client client;

    public BailianConsoleClient(BailianKbProperties properties,
                                String workspaceId,
                                Supplier<String> indexIdFallback) {
        this.properties = properties;
        this.workspaceId = workspaceId;
        this.indexIdFallback = indexIdFallback;
    }

    /**
     * 列出知识库里的文件。
     *
     * <p>限流 15 QPS，而这里一次调用只发一个请求，正常用不到限速。
     *
     * @param pageNumber 页码，从 1 开始
     * @param pageSize   每页几份
     * @param status     只看某种状态（如 {@code FINISH}）；空表示不筛
     * @param name       按文件名筛；空表示不筛。<b>传了就按模糊匹配</b>，
     *                   因为用户在搜索框里打的是片段，要求他打全名没法用
     */
    public ConsoleDocumentPage listDocuments(int pageNumber, int pageSize, String status, String name) {
        ListIndexDocumentsRequest request = new ListIndexDocumentsRequest()
                .setIndexId(requireIndexId())
                .setPageNumber(pageNumber)
                .setPageSize(pageSize);

        if (StringUtils.hasText(status)) {
            request.setDocumentStatus(status);
        }
        if (StringUtils.hasText(name)) {
            request.setDocumentName(name.trim());
            request.setEnableNameLike("true");
        }

        ListIndexDocumentsResponseBody body = call("ListIndexDocuments",
                () -> client().listIndexDocuments(workspaceId, request).getBody());
        requireSuccess("ListIndexDocuments", body == null ? null : body.getSuccess(),
                body == null ? null : body.getCode(), body == null ? null : body.getMessage());

        ListIndexDocumentsResponseBody.ListIndexDocumentsResponseBodyData data = body.getData();
        if (data == null || data.getDocuments() == null) {
            return new ConsoleDocumentPage(List.of(), 0);
        }

        List<ConsoleDocument> documents = new ArrayList<>(data.getDocuments().size());
        for (ListIndexDocumentsResponseBody.ListIndexDocumentsResponseBodyDataDocuments document
                : data.getDocuments()) {
            if (document == null) {
                continue;
            }
            documents.add(new ConsoleDocument(document.getId(), document.getName(), document.getStatus(),
                    document.getSize(), document.getDocumentType(), document.getGmtModified(),
                    document.getSourceId()));
        }

        long total = data.getTotalCount() == null ? documents.size() : data.getTotalCount();
        return new ConsoleDocumentPage(documents, total);
    }

    /**
     * 列出某一份文档在百炼侧的切片。
     *
     * <p>限流 10 QPS。<b>调用方要自己控制节奏</b>——拉一整份大文档是连续多次调用，
     * 并发或密集循环会被限流，而限流的表现是「同步大文档时随机几份失败」，很难查。
     * 详见 {@link BailianKbSyncService} 里的串行分页循环。
     *
     * @param fileId   文档标识，来自 {@link ConsoleDocument#fileId()}。
     *                 <b>不是 {@code sourceId}</b>——那个是类目 ID，传进去查不到东西
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页几片，<b>上限 100</b>
     */
    public ConsoleChunkPage listChunks(String fileId, int pageNum, int pageSize) {
        ListChunksRequest request = new ListChunksRequest()
                .setIndexId(requireIndexId())
                .setFileId(fileId)
                .setPageNum(pageNum)
                .setPageSize(pageSize);

        ListChunksResponseBody body = call("ListChunks",
                () -> client().listChunks(workspaceId, request).getBody());
        requireSuccess("ListChunks", body == null ? null : body.getSuccess(),
                body == null ? null : body.getCode(), body == null ? null : body.getMessage());

        ListChunksResponseBody.ListChunksResponseBodyData data = body.getData();
        if (data == null || data.getNodes() == null) {
            return new ConsoleChunkPage(List.of(), 0);
        }

        List<ConsoleChunk> chunks = new ArrayList<>(data.getNodes().size());
        for (ListChunksResponseBody.ListChunksResponseBodyDataNodes node : data.getNodes()) {
            if (node == null) {
                continue;
            }
            chunks.add(new ConsoleChunk(node.getText(), toMetadata(node.getMetadata())));
        }

        long total = data.getTotal() == null ? chunks.size() : data.getTotal();
        return new ConsoleChunkPage(chunks, total);
    }

    /**
     * 这次实际用的知识库 ID：配置里填了就用它，没填则回落到 {@link #indexIdFallback} 换来的那个。
     *
     * <p>自检接口会把它打出来——「第 2 步成功了」这件事本身说明不了用的是哪个 ID，
     * 而「两者是不是同一个值」正是这次自检要回答的问题之一。
     *
     * @throws UpstreamException 两处都拿不到时
     */
    public String indexId() {
        return requireIndexId();
    }

    /** 当前实际使用的业务空间 ID。给自检接口回显用。 */
    public String workspaceId() {
        return workspaceId;
    }

    // ==================== 内部实现 ====================

    /**
     * 定下这次调用用哪个知识库 ID。
     *
     * <p>配置优先；没配就去问 {@link #indexIdFallback}（见那边的注释）。
     * 两处都拿不到才报错，而且报的错要指名「该填哪个键」——留一句百炼自己说的
     * 「IndexId 不合法」对用户没有任何指向。
     */
    private String requireIndexId() {
        if (StringUtils.hasText(properties.getIndexId())) {
            return properties.getIndexId().trim();
        }
        String fallback = indexIdFallback == null ? null : indexIdFallback.get();
        if (!StringUtils.hasText(fallback)) {
            throw UpstreamException.failed("error.kb.bailianNotConfigured",
                    List.of("purify.rag.bailian.index-id"));
        }
        return fallback.trim();
    }

    private Client client() {
        Client current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                client = buildClient();
            }
            return client;
        }
    }

    private Client buildClient() {
        if (!StringUtils.hasText(properties.getAccessKeyId())
                || !StringUtils.hasText(properties.getAccessKeySecret())) {
            throw UpstreamException.failed("error.kb.bailianNotConfigured",
                    List.of("purify.rag.bailian.access-key-id", "purify.rag.bailian.access-key-secret"));
        }
        if (!StringUtils.hasText(workspaceId)) {
            throw UpstreamException.failed("error.kb.bailianNotConfigured",
                    List.of("purify.rag.workspace-id"));
        }

        Config config = new Config()
                .setAccessKeyId(properties.getAccessKeyId())
                .setAccessKeySecret(properties.getAccessKeySecret())
                .setEndpoint(properties.getEndpoint())
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setReadTimeout(READ_TIMEOUT_MILLIS);

        try {
            Client built = new Client(config);
            log.info("[百炼] 管控面客户端已创建：endpoint={} workspaceId={} indexId={}",
                    properties.getEndpoint(), mask(workspaceId), properties.getIndexId());
            return built;
        }
        catch (Exception exception) {
            // 走到这里说明配置本身有问题（endpoint 格式不对之类），不是网络问题
            throw UpstreamException.failed("error.kb.upstream",
                    "创建管控面客户端", describe(exception));
        }
    }

    /**
     * 调一次远程，把 SDK 抛出来的东西统一翻成 {@link UpstreamException}。
     *
     * <p>用 {@code Exception} 而不是 {@code RuntimeException} 接住：SDK 的方法签名带
     * {@code throws Exception}，虽然它实际抛的 {@code TeaException} 是运行时异常，
     * 但网络层（{@code SocketTimeoutException} 之类）是受检的，漏接会直接冒到
     * {@code GlobalExceptionHandler} 之外变成裸 500。
     */
    private <T> T call(String operation, RemoteCall<T> action) {
        try {
            return action.call();
        }
        catch (UpstreamException exception) {
            // 已经是本项目的异常（比如上面 requireIndexId 抛的），不要再包一层
            throw exception;
        }
        catch (Exception exception) {
            log.warn("[百炼] {} 调用失败：{}", operation, exception.toString());
            throw UpstreamException.failed("error.kb.upstream", operation, describe(exception));
        }
    }

    /**
     * 检查 HTTP 200 之外的失败。
     *
     * <p><b>{@code Success=false} 必须显式判</b>：这两个接口在参数不合法（比如 IndexId
     * 不对）时不一定抛异常，而是回一个 200 + {@code Success=false} + 一句错误说明。
     * 只接异常不判这个标志的话，会把「知识库 ID 填错了」当成「这个知识库是空的」，
     * 于是页面上显示一个空列表，用户以为自己的文件丢了。
     */
    private static void requireSuccess(String operation, Boolean success, String code, String message) {
        if (Boolean.TRUE.equals(success)) {
            return;
        }
        log.warn("[百炼] {} 返回失败：code={} message={}", operation, code, message);
        throw UpstreamException.failed("error.kb.upstream", operation, describe(code, message));
    }

    /**
     * 把切片元数据从 {@code Object} 转成 Map。
     *
     * <p>SDK 把这个字段声明成 {@code Object}（见 {@code ListChunksResponseBody$...DataNodes}），
     * 因为百炼侧的元数据是自由格式。正常是 JSON 对象，反序列化出来就是 Map；
     * 万一是别的形状，退回空 Map 并记一条，而不是抛——元数据缺失只是让分类要手选，
     * 不该让整个预览打不开。
     */
    private static Map<String, Object> toMetadata(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, value) -> converted.put(String.valueOf(key), value));
            return converted;
        }
        if (raw != null) {
            log.warn("[百炼] 切片元数据不是对象，已忽略：{}", raw.getClass().getName());
        }
        return Map.of();
    }

    private static String describe(Exception exception) {
        if (exception instanceof TeaException teaException) {
            return describe(teaException.getCode(), teaException.getMessage());
        }
        String message = exception.getMessage();
        return StringUtils.hasText(message)
                ? exception.getClass().getSimpleName() + ": " + message
                : exception.getClass().getSimpleName();
    }

    private static String describe(String code, String message) {
        if (!StringUtils.hasText(code)) {
            return StringUtils.hasText(message) ? message : "(百炼没有给出错误信息)";
        }
        return StringUtils.hasText(message) ? code + ": " + message : code;
    }

    /** 只留前 4 位。日志和自检接口都会回显它，而它们会被截图、被贴进群里。 */
    public static String mask(String value) {
        if (!StringUtils.hasText(value)) {
            return "(未配置)";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 4 ? "****" : trimmed.substring(0, 4) + "****";
    }

    /** 能抛受检异常的 {@code Supplier}。SDK 的方法签名带 {@code throws Exception}。 */
    @FunctionalInterface
    private interface RemoteCall<T> {
        T call() throws Exception;
    }

    /** 百炼侧的一份文档。SDK 类型的字段名原样搬过来，不做二次演绎。 */
    public record ConsoleDocument(String fileId,
                                  String name,
                                  String status,
                                  Integer size,
                                  String documentType,
                                  Long gmtModified,
                                  String sourceId) {
    }

    /** 文件清单的一页。 */
    public record ConsoleDocumentPage(List<ConsoleDocument> documents, long total) {
    }

    /** 百炼侧的一条切片。 */
    public record ConsoleChunk(String text, Map<String, Object> metadata) {
    }

    /** 切片的一页。 */
    public record ConsoleChunkPage(List<ConsoleChunk> chunks, long total) {
    }
}
