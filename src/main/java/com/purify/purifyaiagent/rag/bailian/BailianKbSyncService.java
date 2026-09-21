package com.purify.purifyaiagent.rag.bailian;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.purify.purifyaiagent.config.BailianKbProperties;
import com.purify.purifyaiagent.config.PgVectorProperties;
import com.purify.purifyaiagent.config.RagProperties;
import com.purify.purifyaiagent.exception.ApiException;
import com.purify.purifyaiagent.exception.UpstreamException;
import com.purify.purifyaiagent.i18n.MessageResolver;
import com.purify.purifyaiagent.model.BailianChunkItem;
import com.purify.purifyaiagent.model.BailianChunkPage;
import com.purify.purifyaiagent.model.BailianDocumentItem;
import com.purify.purifyaiagent.model.BailianDocumentPage;
import com.purify.purifyaiagent.model.BailianSyncRequest;
import com.purify.purifyaiagent.model.BailianSyncState;
import com.purify.purifyaiagent.model.BailianSyncStatus;
import com.purify.purifyaiagent.model.BatchIndexResult;
import com.purify.purifyaiagent.model.DocumentIndexResult;
import com.purify.purifyaiagent.model.SyncedSource;
import com.purify.purifyaiagent.rag.pgvector.PgVectorIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 「百炼知识库 → 本地 pgvector」同步的编排。
 *
 * <p><b>它存在的理由</b>：百炼控制台的切片效果比本地 {@code TokenTextSplitter} 好，
 * 所以想用百炼的切片，但仍要在本地向量库里检索。于是需要一条
 * 「把百炼已经切好的切片原样搬过来、不重切」的路，以及一个让超级用户先看一眼
 * 再决定搬哪些的闸门。
 *
 * <p><b>它和检索链路完全解耦。</b>本类只读百炼、只写 pgvector，不碰
 * {@code DocumentRetriever} / {@code Advisor} / 任何一条检索链路。
 * 百炼挂了不影响问答，同步挂了也不影响问答。这一点值得明说，因为
 * {@code RagConfig} 和 {@code PgVectorRagConfig} 之间那种「装配条件互斥」的紧张关系
 * 会让人以为这里也有牵连——没有。
 *
 * <p><b>幂等性由 {@link PgVectorIndexService#indexPreChunked} 提供</b>，
 * 本类不另做一套：同一份文档同步多少次，结果都是「先按 source 删干净、再写一批」，
 * 所以重复同步是安全的。这不是顺手得到的好处——它正是「用户可以放心点两次」的前提。
 *
 * <h2>「已同步」是怎么判出来的</h2>
 *
 * <p>光看文件名在不在本地是不够的：本地可能有一份<b>同名但手动上传</b>的文档，
 * 也可能百炼那边后来改过。所以判定同时看两样：本地这份的 {@code source} 在不在，
 * 以及它带着的百炼标记是不是这个文件的。
 *
 * <p>判断「百炼改过没有」时，比的是<b>百炼自己的两个时间戳</b>——同步那一刻记下的
 * 和当前列表里的。绝不拿本地的 {@code uploaded_at} 去比，那是两台机器的时钟。
 */
@Slf4j
public class BailianKbSyncService {

    /**
     * 元数据键：这份切片来自哪个百炼文件。
     *
     * <p>有了它才能分辨「本地这份是从百炼同步来的」和「本地这份是手传的、只是重名」。
     * 后者同步时会被覆盖，属于破坏性操作，必须先认出来再让用户确认。
     */
    public static final String META_FILE_ID = "bailian_file_id";

    /**
     * 元数据键：同步那一刻百炼侧的修改时间，<b>原始值</b>。
     *
     * <p>它本身没有意义，有意义的是「它和百炼当前值是否相等」。见
     * {@link SyncedSource} 的注释：绝不能用本地时间戳替代它。
     */
    public static final String META_GMT_MODIFIED = "bailian_gmt_modified";

    /** 列表筛选：只看某状态。 */
    private static final String STATUS_ALL = "ALL";

    /**
     * 列表默认只看这个状态。
     *
     * <p>{@code FINISH} 才有切片可拉，还在解析的文档拉不到东西。但<b>不能因此把它们
     * 从列表里藏掉</b>——用户会问「我刚传上去的那份去哪了」。所以默认过滤，但支持
     * {@link #STATUS_ALL} 看全部。
     */
    private static final String DEFAULT_STATUS = "FINISH";

    /** 文件清单每页上限。挡一道，免得前端传个 size=100000 把整库拉进内存。 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 切片每页上限。**100 是百炼接口的硬上限**，超过不会报错、会被静默截断。 */
    private static final int MAX_CHUNK_PAGE_SIZE = 100;

    /**
     * 连续翻页之间的停顿。
     *
     * <p>{@code ListChunks} 限流 10 QPS，一份一千片的文档就是十次连续调用。
     * 单次往返通常已经慢于 100ms，但那是「通常」——网络好的时候几毫秒也能回来，
     * 而一旦踩到限流，表现是「同步大文档时随机几份失败」，很难往限流这个方向想。
     * 这点停顿买的是确定性。
     */
    private static final long PAGE_INTERVAL_MILLIS = 120;

    private final BailianConsoleClient client;

    private final BailianKbProperties properties;

    private final PgVectorIndexService indexService;

    private final RagProperties ragProperties;

    private final PgVectorProperties pgVectorProperties;

    private final MessageResolver messageResolver;

    private final DashScopeApi dashScopeApi;

    public BailianKbSyncService(BailianConsoleClient client,
                                BailianKbProperties properties,
                                PgVectorIndexService indexService,
                                RagProperties ragProperties,
                                PgVectorProperties pgVectorProperties,
                                MessageResolver messageResolver,
                                DashScopeApi dashScopeApi) {
        this.client = client;
        this.properties = properties;
        this.indexService = indexService;
        this.ragProperties = ragProperties;
        this.pgVectorProperties = pgVectorProperties;
        this.messageResolver = messageResolver;
        this.dashScopeApi = dashScopeApi;
    }

    // ==================== 配置自检 ====================

    /**
     * 配置齐不齐、缺哪几项、可选分类有哪些。<b>不发任何远程请求。</b>
     *
     * <p>判断放在这里而不是 {@code BailianKbProperties} 上，是因为依据横跨两个配置类：
     * 业务空间 ID 在本类和 {@code purify.rag.workspace-id} 之间回落。
     */
    public BailianSyncStatus status() {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(properties.getAccessKeyId())) {
            missing.add("purify.rag.bailian.access-key-id");
        }
        if (!StringUtils.hasText(properties.getAccessKeySecret())) {
            missing.add("purify.rag.bailian.access-key-secret");
        }
        if (!StringUtils.hasText(properties.getEndpoint())) {
            missing.add("purify.rag.bailian.endpoint");
        }
        // index-id 留空**不算缺**：它可以从 index-name 换一个来（见
        // BailianConsoleClient 里 indexIdFallback 的注释），而换的那一下是一次 HTTP 调用，
        // 这个方法承诺不发远程请求，所以没法在这里验证它换得到。
        // 真正拿不到 ID 的只有「两个都没配」这一种，那时才报，报的是能去修的那个键
        if (!StringUtils.hasText(properties.getIndexId())
                && !StringUtils.hasText(ragProperties.getIndexName())) {
            missing.add("purify.rag.bailian.index-id");
        }
        if (!StringUtils.hasText(effectiveWorkspaceId())) {
            missing.add("purify.rag.workspace-id");
        }

        return new BailianSyncStatus(missing.isEmpty(),
                properties.getIndexId(),
                ragProperties.getIndexName(),
                // 只回显前四位。这个响应会出现在管理页上，会被截图、会被贴进群里排障
                BailianConsoleClient.mask(properties.getAccessKeyId()),
                BailianConsoleClient.mask(effectiveWorkspaceId()),
                List.copyOf(missing),
                categories());
    }

    /** 管控面该用的业务空间 ID：本功能自己的优先，没配就回落到 RAG 那条。 */
    private String effectiveWorkspaceId() {
        return StringUtils.hasText(properties.getWorkspaceId())
                ? properties.getWorkspaceId()
                : ragProperties.getWorkspaceId();
    }

    /** 可选分类值。直出给前端，省得它再硬编码一份——硬编码多一处就多一处会漂移的地方。 */
    private List<String> categories() {
        return ragProperties.getRouter().getCategories().stream()
                .map(RagProperties.Category::getValue)
                .filter(StringUtils::hasText)
                .toList();
    }

    // ==================== 文件清单 ====================

    /**
     * 百炼的文件清单，每行带上它在本地的同步状态。
     *
     * <p><b>本地状态是一条 SQL 批量查出来的，不是逐份去查。</b>一页二十行，
     * 逐份查就是二十次数据库往返；而远程那边更是<b>一次都不多发</b>——
     * 整个列表页只有一次 {@code ListIndexDocuments} 调用。
     *
     * <p>这也是为什么每行没有「百炼侧有多少片」：那个数要逐份调 {@code ListChunks}
     * 才拿得到（限流 10 QPS），一页就是二十次远程调用，只为在列表上显示一个数字。
     * 想看片数就展开那一行。
     *
     * @param status {@code ALL} 表示不筛，其余按百炼的状态值筛；空则用默认的 FINISH
     */
    public BailianDocumentPage listDocuments(int page, int size, String status, String name) {
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 1);

        String statusFilter = STATUS_ALL.equalsIgnoreCase(status == null ? "" : status.trim())
                ? null
                : (StringUtils.hasText(status) ? status.trim() : DEFAULT_STATUS);

        BailianConsoleClient.ConsoleDocumentPage remote =
                client.listDocuments(safePage, safeSize, statusFilter, name);

        // 查本地状态用的键**必须和写入端逐字一致**：那边经过 requireFilename 的 trim，
        // 所以这里也得先 trim。不一致的表现是「百炼文件名带首尾空格时，
        // 这份文档永远显示未同步，而且每点一次同步就重复同步一次」——不报任何错
        List<String> sources = remote.documents().stream()
                .map(document -> sourceKeyOf(document.name()))
                .filter(StringUtils::hasText)
                .toList();
        Map<String, SyncedSource> local =
                indexService.summariesBySources(sources, META_FILE_ID, META_GMT_MODIFIED);

        List<BailianDocumentItem> items = remote.documents().stream()
                .map(document -> toItem(document, local.get(sourceKeyOf(document.name()))))
                .toList();

        return new BailianDocumentPage(remote.total(), safePage, safeSize, items);
    }

    /**
     * 拿百炼的文件名去本地库里查来源时用的键。
     *
     * <p>写入端（{@code PgVectorIndexService#requireFilename}）会把文件名 trim 之后再当
     * {@code source} 用，所以查的时候也必须 trim——两边不一致的话，
     * 名字带空格的那几份永远查不到，表现为「一直显示未同步」，而且不报错。
     *
     * <p>返回 {@code null} 时调用方分别做了过滤和 {@code Map.get(null)}，
     * 两条路都不会炸。
     */
    private static String sourceKeyOf(String name) {
        return name == null ? null : name.trim();
    }

    private static BailianDocumentItem toItem(BailianConsoleClient.ConsoleDocument document,
                                              SyncedSource local) {
        BailianSyncState state = local == null ? BailianSyncState.MISSING : resolveState(document, local);
        return new BailianDocumentItem(document.fileId(), document.name(), document.status(),
                document.size(), document.documentType(), document.gmtModified(),
                state,
                local == null ? 0 : local.chunks(),
                local == null ? null : local.uploadedAt());
    }

    /**
     * 四态判定。
     *
     * <p>先看「本地这份是不是从<b>这个</b>百炼文件来的」——本地的 {@code bailian_file_id}
     * 为空（手传的）或和当前文件对不上，都属于 {@link BailianSyncState#NAME_CONFLICT}：
     * 同步会覆盖掉本地那份，那是破坏性的，必须先让用户确认。
     *
     * <p>确认同源之后再看时间戳。用 {@code !Objects.equals} 而不是「小于」：
     * 本地那份的基准值正常情况下不可能比百炼当前值大，真出现那种情况（比如换了百炼文件
     * 但 fileId 恰好没变）判成 {@code STALE} 也只是「多同步一次」，比判成「已是最新」
     * 然后让用户永远看不到更新要安全得多。
     */
    private static BailianSyncState resolveState(BailianConsoleClient.ConsoleDocument document,
                                                 SyncedSource local) {
        if (!StringUtils.hasText(local.fileId()) || !local.fileId().equals(document.fileId())) {
            return BailianSyncState.NAME_CONFLICT;
        }
        if (!Objects.equals(document.gmtModified(), local.gmtModified())) {
            return BailianSyncState.STALE;
        }
        return BailianSyncState.SYNCED;
    }

    // ==================== 切片预览 ====================

    /**
     * 某一份百炼文档的切片预览 —— 让超级用户在同步之前看清「百炼到底切成了什么样」。
     *
     * <p>正文不截断，理由见 {@link BailianChunkItem}。
     */
    public BailianChunkPage listChunks(String fileId, int pageNum, int pageSize) {
        if (!StringUtils.hasText(fileId)) {
            throw ApiException.unsupportedDocument("error.kb.filenameRequired");
        }
        if (pageSize > MAX_CHUNK_PAGE_SIZE) {
            // 不静默截断：调用方以为拿到 200 条、实际只有 100 条，
            // 会表现为「翻着翻着内容对不上」，比直接报错难查得多
            throw ApiException.unsupportedDocument(
                    "error.kb.bailianPageSizeTooLarge", MAX_CHUNK_PAGE_SIZE, pageSize);
        }

        int safeSize = Math.clamp(pageSize, 1, MAX_CHUNK_PAGE_SIZE);
        int safePage = Math.max(pageNum, 1);

        BailianConsoleClient.ConsoleChunkPage remote = client.listChunks(fileId, safePage, safeSize);
        String filterKey = ragProperties.getRouter().getFilterKey();
        List<String> known = categories();
        int offset = (safePage - 1) * safeSize;

        Set<String> seen = new LinkedHashSet<>();
        List<BailianChunkItem> chunks = new ArrayList<>(remote.chunks().size());
        for (int i = 0; i < remote.chunks().size(); i++) {
            BailianConsoleClient.ConsoleChunk chunk = remote.chunks().get(i);
            String classification = classificationOf(chunk.metadata(), filterKey, known);
            if (classification != null) {
                seen.add(classification);
            }
            String text = chunk.text() == null ? "" : chunk.text();
            chunks.add(new BailianChunkItem(offset + i, chunkIdOf(chunk.metadata()),
                    text.length(), text, chunk.metadata(), classification));
        }

        // 这是**本页**的观察，不是整份文档的结论。要知道整份是否混标得把所有页拉下来，
        // 而预览是逐页按需触发的——那样等于每次预览都拉整份。整份判定发生在同步那一刻，
        // 那时本来就要拉全量
        BailianChunkPage.ClassificationSource source =
                seen.isEmpty() ? BailianChunkPage.ClassificationSource.NONE
                        : (seen.size() == 1 ? BailianChunkPage.ClassificationSource.METADATA
                        : BailianChunkPage.ClassificationSource.MIXED);

        return new BailianChunkPage(fileId, safePage, safeSize, remote.total(), chunks, source);
    }

    // ==================== 同步 ====================

    /**
     * 把选中的文档搬进本地向量库。<b>逐份独立，一份失败不影响其余。</b>
     *
     * <p>逐份失败只记一行、不往上抛：抛出去会让整批 400，已经成功的那几份
     * 用户就以为也白传了。（与 {@code KnowledgeBaseController#uploadBatch} 同一个理由。）
     */
    public BatchIndexResult sync(List<BailianSyncRequest.Item> items) {
        if (items == null || items.isEmpty()) {
            throw ApiException.unsupportedDocument("error.kb.bailianNoSelection");
        }

        List<BatchIndexResult.Item> results = new ArrayList<>(items.size());
        for (BailianSyncRequest.Item item : items) {
            String name = item == null || !StringUtils.hasText(item.name()) ? "(未命名)" : item.name();
            try {
                DocumentIndexResult result = syncOne(item);
                results.add(BatchIndexResult.Item.ok(
                        result.source(), result.chunkCount(), result.characterCount()));
            }
            catch (RuntimeException exception) {
                // 记一行就够，不往上抛。见方法注释
                log.warn("[百炼同步] {} 失败：{}", name, exception.getMessage());
                results.add(BatchIndexResult.Item.failed(name, readable(exception)));
            }
        }

        long succeeded = results.stream().filter(BatchIndexResult.Item::ok).count();
        log.info("[百炼同步] 完成：成功 {} 份，失败 {} 份", succeeded, results.size() - succeeded);
        return new BatchIndexResult((int) succeeded, (int) (results.size() - succeeded), results);
    }

    /**
     * 同步一份文档：拉全量切片 → 定分类 → 落库。
     *
     * <p>顺序不能反：校验都在拉取之后、写入之前，这样校验不通过时本地旧数据原封不动。
     */
    private DocumentIndexResult syncOne(BailianSyncRequest.Item item) {
        if (item == null || !StringUtils.hasText(item.fileId()) || !StringUtils.hasText(item.name())) {
            throw ApiException.unsupportedDocument("error.kb.filenameRequired");
        }

        List<BailianConsoleClient.ConsoleChunk> chunks = fetchAllChunks(item.fileId());
        if (chunks.isEmpty()) {
            // 最常见的原因是百炼那边还在解析。说清楚，比「切片为空」有用得多
            throw ApiException.documentIndexFailed("error.kb.bailianNotReady", item.name());
        }

        String classification = resolveClassification(chunks, item.classification(), item.name());
        List<Document> documents = toDocuments(chunks, item.fileId(), item.gmtModified());

        return indexService.indexPreChunked(item.name(), classification, documents);
    }

    /**
     * 把一份文档在百炼侧的切片全部拉下来，串行翻页。
     *
     * <p><b>必须串行，且翻页之间要停顿</b>——{@code ListChunks} 限流 10 QPS，
     * 并发拉会让「同步大文档时随机几份失败」，而失败信息里不会提到限流。
     * 见 {@link #PAGE_INTERVAL_MILLIS}。
     *
     * <p><b>拉取有上限。</b>本地单份文档本来就只收 {@code max-num-chunks} 片，
     * 多拉的部分注定要被拒。所以拉满上限再多一条就停手，把「太多」这件事交给
     * {@link PgVectorIndexService#indexPreChunked} 去报——它的报错文案才是准确的，
     * 这里只需要不把一份不可能的文档整个下载下来。
     */
    private List<BailianConsoleClient.ConsoleChunk> fetchAllChunks(String fileId) {
        int pageSize = Math.clamp(properties.getChunkPageSize(), 1, MAX_CHUNK_PAGE_SIZE);
        int limit = pgVectorProperties.getChunk().getMaxNumChunks() + 1;

        List<BailianConsoleClient.ConsoleChunk> all = new ArrayList<>();
        int pageNum = 1;
        while (all.size() < limit) {
            BailianConsoleClient.ConsoleChunkPage page = client.listChunks(fileId, pageNum, pageSize);
            if (page.chunks().isEmpty()) {
                break;
            }
            all.addAll(page.chunks());

            // 两种收尾条件：已经够到总数了，或者这一页没满（说明是最后一页）
            if (all.size() >= page.total() || page.chunks().size() < pageSize) {
                break;
            }
            pageNum++;
            pauseBetweenPages();
        }

        if (all.size() >= limit) {
            log.warn("[百炼同步] {} 的切片数已达到本地单份上限，不再继续拉取", fileId);
        }
        return all;
    }

    private static void pauseBetweenPages() {
        try {
            Thread.sleep(PAGE_INTERVAL_MILLIS);
        }
        catch (InterruptedException exception) {
            // 恢复中断标志而不是吞掉：这个方法跑在请求线程上，中断意味着这个请求
            // 已经不该继续了，把标志留着让上层能看见
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 定下这份文档用哪个分类。
     *
     * <p>规则只有一条，好解释也好看：<b>百炼切片的元数据里恰好只有一个认识的分类时用它，
     * 其余情况（一个都没有、标了多个、标的值不在配置表里）都用请求里带的那个。</b>
     * 前者是「百炼已经标清楚了」，后者是「百炼没说清，让人来定」。
     *
     * <p>为什么标了多个时不挑一个：分类做的是等值过滤，挑错的后果是这些切片
     * 在带分类过滤的提问下永远检索不到，<b>而且不报任何错</b>。既然要塌缩成一个
     * （一份文档两行会让「这个问题该查哪一类」的语义变模糊），那就必须由人来选。
     *
     * <p>反过来，请求里带的那个值会被 {@code requireKnownClassification} 再校验一遍，
     * 所以这里不做重复校验，只负责「有没有」。
     */
    private String resolveClassification(List<BailianConsoleClient.ConsoleChunk> chunks,
                                         String fallback,
                                         String source) {
        String filterKey = ragProperties.getRouter().getFilterKey();
        List<String> known = categories();

        Set<String> distinct = new LinkedHashSet<>();
        for (BailianConsoleClient.ConsoleChunk chunk : chunks) {
            String value = classificationOf(chunk.metadata(), filterKey, known);
            if (value != null) {
                distinct.add(value);
            }
        }

        if (distinct.size() == 1) {
            String only = distinct.iterator().next();
            if (StringUtils.hasText(fallback) && !only.equals(fallback.trim())) {
                log.info("[百炼同步] {} 的元数据标着「{}」，请求里带的是「{}」，以元数据为准",
                        source, only, fallback);
            }
            return only;
        }

        if (!StringUtils.hasText(fallback)) {
            // 两种「定不下来」的原因要分开说：一个是百炼没标，一个是标了多个。
            // 合成一句「请指定分类」的话，用户不知道该不该去百炼那边补标
            throw ApiException.unsupportedDocument(
                    distinct.isEmpty() ? "error.kb.bailianClassificationMissing"
                            : "error.kb.bailianClassificationAmbiguous",
                    source, distinct);
        }
        return fallback.trim();
    }

    /**
     * 把百炼的切片转成 Spring AI 的 {@code Document}，并把百炼能提供的信息先填进元数据。
     *
     * <p>分工与本地那条路一致：<b>这里给什么就用什么，给不了的由
     * {@link PgVectorIndexService#indexPreChunked} 兜底</b>。所以 {@code title}
     * 只有百炼真的给了才写——写了的话写入端用的是 {@code putIfAbsent}，不会被覆盖成文件名。
     */
    private static List<Document> toDocuments(List<BailianConsoleClient.ConsoleChunk> chunks,
                                              String fileId,
                                              Long gmtModified) {
        List<Document> documents = new ArrayList<>(chunks.size());
        for (BailianConsoleClient.ConsoleChunk chunk : chunks) {
            Document document = new Document(chunk.text() == null ? "" : chunk.text());
            Map<String, Object> metadata = document.getMetadata();

            Object title = chunk.metadata().get(PgVectorIndexService.META_TITLE);
            if (title != null && StringUtils.hasText(String.valueOf(title))) {
                metadata.put(PgVectorIndexService.META_TITLE, String.valueOf(title));
            }
            metadata.put(META_FILE_ID, fileId);
            if (gmtModified != null) {
                metadata.put(META_GMT_MODIFIED, gmtModified);
            }
            documents.add(document);
        }
        return documents;
    }

    // ==================== 联调自检 ====================

    /**
     * 管控面联调自检：把「这条路到底通不通」四问一次答完。
     *
     * <p><b>为什么要有它</b>：这个功能依赖四件本地验证不了的事——AK 有没有百炼权限、
     * 业务空间 ID 填什么、管控面的 IndexId 和数据面的 pipeline_id 是不是同一个、
     * 以及文件标识该用哪个字段。这些只能实调一次才知道。和 {@code /api/rag/search}
     * 留存下来的理由一样：把中间状态暴露出来，让「它在工作」可以被验证，
     * 而不是只能靠感觉。上线后它继续是排障入口。
     *
     * <p><b>返回的是原始形状而不是一个规整的 DTO</b>：这个接口存在的意义就是
     * 让人看见「百炼到底回了什么」，规整化会把要找的那点差异抹掉。
     *
     * <p>四步是递进的，第 2 步拿不到文件就做不了第 3、4 步，所以每步失败只记下来、
     * 不抛出去——一次调用就能看到「卡在哪一步」，而不是改一次配置重跑一次。
     */
    public Map<String, Object> probe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", status());
        result.put("accessKeyId", BailianConsoleClient.mask(properties.getAccessKeyId()));
        result.put("accessKeySecret", BailianConsoleClient.mask(properties.getAccessKeySecret()));

        // 第 1 步：数据面按名字换 pipeline_id，和管控面的 IndexId 对拍
        String pipelineId = null;
        try {
            pipelineId = dashScopeApi.getPipelineIdByName(ragProperties.getIndexName());
            result.put("step1_pipelineId", pipelineId);
            result.put("step1_indexNameEqualsPipelineId",
                    pipelineId != null && pipelineId.equals(properties.getIndexId()));
        }
        catch (RuntimeException exception) {
            result.put("step1_pipelineIdError", readable(exception));
        }

        // 管控面这次实际会用哪个知识库 ID。**第 2 步成功本身说明不了用的是什么**：
        // 配了 bailian.index-id 就用它，没配则回落到上面那个 pipeline_id。
        // 而「两者是不是同一个值」正是这次自检要回答的问题，所以必须讲清楚是哪一种
        try {
            result.put("step1b_effectiveIndexId", client.indexId());
        }
        catch (RuntimeException exception) {
            result.put("step1b_effectiveIndexIdError", readable(exception));
        }

        // 第 2 步：列文件。这一页就是「百炼上有什么」的直接证据
        String firstFileId = null;
        String firstSourceId = null;
        try {
            BailianConsoleClient.ConsoleDocumentPage page = client.listDocuments(1, 5, null, null);
            result.put("step2_total", page.total());
            result.put("step2_documents", page.documents().stream()
                    .map(document -> Map.of(
                            "id", String.valueOf(document.fileId()),
                            "name", String.valueOf(document.name()),
                            "status", String.valueOf(document.status()),
                            "documentType", String.valueOf(document.documentType()),
                            "size", String.valueOf(document.size()),
                            "gmtModified", String.valueOf(document.gmtModified()),
                            "sourceId", String.valueOf(document.sourceId())))
                    .toList());
            if (!page.documents().isEmpty()) {
                firstFileId = page.documents().get(0).fileId();
                firstSourceId = page.documents().get(0).sourceId();
            }
        }
        catch (RuntimeException exception) {
            result.put("step2_error", readable(exception));
        }

        // 第 3 步：拿 Id 当 FileId 拉切片。能拉到，整条路就通了；
        // 顺便把元数据的键名列出来——「百炼到底给我们标了什么」全靠这一行
        if (StringUtils.hasText(firstFileId)) {
            try {
                BailianConsoleClient.ConsoleChunkPage page = client.listChunks(firstFileId, 1, 3);
                result.put("step3_total", page.total());
                result.put("step3_chunks", page.chunks().stream()
                        .map(chunk -> Map.of(
                                "text", abbreviate(chunk.text()),
                                "metadataKeys", List.copyOf(chunk.metadata().keySet()),
                                "metadata", String.valueOf(chunk.metadata())))
                        .toList());
            }
            catch (RuntimeException exception) {
                result.put("step3_error", readable(exception));
            }
        }

        // 第 4 步：故意用 sourceId 当 FileId 再试一次。**预期失败**——
        // sourceId 是类目 ID 不是文件标识，这一步成功反而说明我们对字段的理解错了
        if (StringUtils.hasText(firstSourceId)) {
            try {
                client.listChunks(firstSourceId, 1, 1);
                result.put("step4_sourceIdAsFileId", "意外成功了：sourceId 竟然能当 FileId 用，"
                        + "请重新确认文档里对这两个字段的说明");
            }
            catch (RuntimeException exception) {
                result.put("step4_sourceIdAsFileId",
                        "如期失败（这正是期望的结果）：" + exception.getMessage());
            }
        }

        return result;
    }

    // ==================== 内部工具 ====================

    /**
     * 从切片元数据里按配置的字段名取分类，并校验它确实在配置的分类表里。
     *
     * <p>取值不在表里就当「没标」：那个值不可能被任何一次带分类过滤的检索命中，
     * 沿用它的结果和用错分类一样糟，还不如让用户重新选一个。
     * 分类表没配时不拦，与 {@code requireKnownClassification} 同口径。
     */
    private static String classificationOf(Map<String, Object> metadata,
                                           String filterKey,
                                           List<String> known) {
        if (metadata == null) {
            return null;
        }
        Object raw = metadata.get(filterKey);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (!known.isEmpty() && !known.contains(value)) {
            return null;
        }
        return value;
    }

    /** 百炼把切片 ID 放在元数据的 {@code _id} 里。取不到就留空，不影响审切片。 */
    private static String chunkIdOf(Map<String, Object> metadata) {
        Object raw = metadata == null ? null : metadata.get("_id");
        return raw == null ? null : String.valueOf(raw);
    }

    /**
     * 把异常翻成给用户看的一句话。
     *
     * <p><b>必须翻译，不能直接用 {@code getMessage()}。</b>本项目里
     * {@code ApiException} / {@code UpstreamException} 的 {@code getMessage()}
     * 返回的是 {@code messages*.properties} 里的<b>键</b>（见它们的类注释），
     * 直接透出去用户会看到 {@code error.kb.contentEmpty} 这种东西。
     * （既有的 {@code uploadBatch} 就是这么干的，那个瑕疵不在本次范围内。）
     *
     * <p>这个方法跑在请求线程上，所以 {@code MessageResolver.current()} 是准的。
     */
    private String readable(RuntimeException exception) {
        if (exception instanceof ApiException apiException) {
            return messageResolver.current().get(apiException.getMessageKey(), apiException.getArgs());
        }
        if (exception instanceof UpstreamException upstreamException) {
            return messageResolver.current()
                    .get(upstreamException.getMessageKey(), upstreamException.getArgs());
        }
        String message = exception.getMessage();
        return StringUtils.hasText(message) ? message : exception.getClass().getSimpleName();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 100 ? flattened : flattened.substring(0, 100) + "…";
    }
}
