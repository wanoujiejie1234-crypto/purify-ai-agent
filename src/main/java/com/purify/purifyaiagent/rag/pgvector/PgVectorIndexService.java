package com.purify.purifyaiagent.rag.pgvector;

import com.purify.purifyaiagent.config.PgVectorProperties;
import com.purify.purifyaiagent.config.RagProperties;
import com.purify.purifyaiagent.exception.ApiException;
import com.purify.purifyaiagent.model.ChunkPreview;
import com.purify.purifyaiagent.model.DocumentIndexResult;
import com.purify.purifyaiagent.model.KnowledgeBaseStats;
import com.purify.purifyaiagent.model.KnowledgeDocumentItem;
import com.purify.purifyaiagent.model.KnowledgeDocumentPage;
import com.purify.purifyaiagent.model.SyncedSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 本地向量库的索引服务 —— 流程图左半边「建立索引」那一段。
 *
 * <p>一份文档从上传到入库经过四步，正好对应图里的四个框：
 * <pre>
 *   原始文档（上传的 txt / md）
 *     └── 文档预处理   按后缀选 Reader 解析出纯文本
 *           └── 文档切片     TokenTextSplitter 切成分片
 *                 └── 向量转换与存储  vectorStore.add()（内部调 Embedding 接口后写 pgvector）
 * </pre>
 *
 * <p><b>幂等靠「先按来源删干净、再写新的」</b>：同一个文件名重复上传，
 * 结果永远只有一份切片，而不是越传越多。代价是这两步不在一个事务里
 * （{@code PgVectorStore} 内部没有事务），中途失败会让该来源的数据暂时为空，
 * 重新上传一次即可——换来的是「同一份文档永远不会出现两份」。
 *
 * <p><b>关于 JdbcTemplate</b>：这个类是手工 {@code new} 出来的（见 {@code PgVectorRagConfig}），
 * 传进来的是 pgvector 自己的那个 JdbcTemplate，<b>不是</b>容器里给对话记忆用的 MySQL 那个。
 * 千万别把本类的构造参数改成从容器注入——按类型注入必定拿到 MySQL 的连接。
 * 它只用于 {@link #stats()} 和索引后的自检，读写切片本身一律走 {@link VectorStore}。
 */
@Slf4j
public class PgVectorIndexService {

    /** 元数据键：文档来源，也是幂等删除的锚点。 */
    public static final String META_SOURCE = "source";

    /** 元数据键：文档名，拼进提示词的「【文档名】」。 */
    public static final String META_DOC_NAME = "doc_name";

    /** 元数据键：切片标题，拼进提示词的「【标题】」。 */
    public static final String META_TITLE = "title";

    /** 元数据键：切片在本份文档里的序号，从 0 开始。 */
    public static final String META_CHUNK_INDEX = "chunk_index";

    /** 元数据键：入库时刻。 */
    public static final String META_UPLOADED_AT = "uploaded_at";

    /**
     * 替换字符（U+FFFD）占比超过这个比例就认为编码不对。
     *
     * <p>Reader 一律按 UTF-8 读，Windows 上用记事本存出来的 GBK 文件会被整篇读成
     * 替换字符——不拦的话会把一锅乱码老老实实索引进去，检索时还查不出来问题在哪。
     */
    private static final double REPLACEMENT_CHAR_RATIO_LIMIT = 0.01;

    /** 预览最多列出几片。整份列出来在管理页上没法看，而「切成多少片」那个数仍然是真的。 */
    private static final int PREVIEW_LIMIT = 20;

    /** 预览里每片回显的正文长度。 */
    private static final int PREVIEW_EXCERPT_LENGTH = 80;

    /** 单页最多几份文档。挡一道，免得前端传个 size=100000 把整库拉进内存。 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 元数据里没有分类时，分组统计显示的占位符。 */
    private static final String UNLABELED = "(未标注)";

    private final VectorStore vectorStore;

    private final JdbcTemplate pgJdbcTemplate;

    private final PgVectorProperties pgVectorProperties;

    private final RagProperties ragProperties;

    private final TokenTextSplitter splitter;

    public PgVectorIndexService(VectorStore vectorStore,
                                JdbcTemplate pgJdbcTemplate,
                                PgVectorProperties pgVectorProperties,
                                RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.pgJdbcTemplate = pgJdbcTemplate;
        this.pgVectorProperties = pgVectorProperties;
        this.ragProperties = ragProperties;

        // 表名要拼进 SQL，这里挡一道：配置写错了应当即刻失败，
        // 而不是等到某次查询报语法错误。防线本身在 PgVectorSql 里，
        // 关键词检索那一路用的是同一份
        PgVectorSql.assertSafeIdentifier(pgVectorProperties.getSchemaName(), "purify.rag.pgvector.schema-name");
        PgVectorSql.assertSafeIdentifier(pgVectorProperties.getTableName(), "purify.rag.pgvector.table-name");

        PgVectorProperties.Chunk chunk = pgVectorProperties.getChunk();
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(chunk.getSize())
                .withMinChunkSizeChars(chunk.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(chunk.getMinChunkLengthToEmbed())
                .withMaxNumChunks(chunk.getMaxNumChunks())
                .withKeepSeparator(chunk.isKeepSeparator())
                .build();
    }

    /**
     * 给一份文档建索引。同一来源重复上传会覆盖旧切片。
     *
     * @param originalFilename 上传时的文件名，同时用作 {@code source} 元数据
     * @param resource         文档内容
     * @param classification   归档分类，必须与配置的分类表对得上
     */
    public DocumentIndexResult index(String originalFilename, Resource resource, String classification) {
        String source = requireFilename(originalFilename);
        String category = requireKnownClassification(classification);

        // 1. 文档预处理 + 2. 切片（校验都在切片完成之前做，这样校验不通过时旧数据原封不动）
        return write(source, category, split(read(resource, source, category), source));
    }

    /**
     * 把一批<b>已经切好的</b>切片写进本地向量库。
     *
     * <p><b>存在的唯一理由</b>：百炼那边的切片比本地 {@code TokenTextSplitter} 好，
     * 而这个效果全在「怎么切」上。所以这条路上<b>绝不能再切一次</b>——再切一次就等于
     * 把百炼的切片换成本地的，这个功能的意义当场归零。因此这里跳过
     * {@link #read} 和 {@link #split}，只补元数据再落库。
     *
     * <p><b>分工与 {@link #read} 一致：调用方给什么就用什么，给不了的本地兜底。</b>
     * 百炼侧能提供的（{@code title}、以及调用方自己填的同步标记）由调用方预先写进
     * 各片的元数据，本方法只负责补齐其余几项——所以 {@code title} 用的是
     * {@code putIfAbsent} 而不是 {@code put}。
     *
     * <p>逐条校验都是照着 {@code read()} + {@code split()} 抄的，一处没漏：漏掉任何一条，
     * 这条路就成了绕过校验的后门，而两条路产出的切片在检索时无从区分。
     *
     * @param source         幂等锚点，用百炼侧的文件名（Name），与上传路径的
     *                       {@code originalFilename} 同域——这样「上传 A.md」和
     *                       「同步 A.md」在文档列表里是同一行
     * @param classification 整份文档统一的分类。一份文档内的多分类已由调用方塌缩
     * @param chunks         百炼切好的切片，顺序即 {@code chunk_index}
     */
    public DocumentIndexResult indexPreChunked(String source, String classification, List<Document> chunks) {
        String normalizedSource = requireFilename(source);
        String category = requireKnownClassification(classification);

        if (chunks == null || chunks.isEmpty()) {
            throw ApiException.documentIndexFailed("error.kb.chunksEmpty", normalizedSource);
        }
        if (chunks.stream().allMatch(chunk -> !StringUtils.hasText(chunk.getText()))) {
            throw ApiException.documentIndexFailed("error.kb.contentEmpty", normalizedSource);
        }

        // 上限判定刻意用 > 而不是 split() 里的 >= ：那边的 >= 是因为 TokenTextSplitter
        // 到上限就静默丢弃、撞上等于内容被截断；而这里一份诚实切出 2000 片的文档是完整的，
        // 只在真正超过时才拒绝。也正因为语义不同，不能复用 error.kb.chunkLimitReached——
        // 那条文案写着「超出的内容会被丢弃」，在这里是句假话
        int maxNumChunks = pgVectorProperties.getChunk().getMaxNumChunks();
        if (chunks.size() > maxNumChunks) {
            throw ApiException.documentIndexFailed(
                    "error.kb.bailianChunkLimitExceeded", maxNumChunks, normalizedSource);
        }

        String fallbackTitle = stripExtension(normalizedSource);
        String uploadedAt = Instant.now().toString();
        String filterKey = ragProperties.getRouter().getFilterKey();

        for (int i = 0; i < chunks.size(); i++) {
            // 覆盖而不是 putIfAbsent，理由同 read()：Reader 自带的 source 会和幂等锚点同名不同值，
            // 不覆盖的表现是「删 0 行、不报错、每次同步都多一份切片」
            Map<String, Object> metadata = chunks.get(i).getMetadata();
            metadata.put(META_SOURCE, normalizedSource);
            metadata.put(META_DOC_NAME, normalizedSource);
            metadata.putIfAbsent(META_TITLE, fallbackTitle);
            metadata.put(filterKey, category);
            metadata.put(META_UPLOADED_AT, uploadedAt);
            metadata.put(META_CHUNK_INDEX, i);
        }

        // 百炼侧那份文件本身是 GBK 时，乱码会经由 HTTP 原样传下来。这里同样是唯一能拦住它的地方
        verifyEncoding(chunks, normalizedSource);
        return write(normalizedSource, category, chunks);
    }

    /**
     * 落库那一段：<b>先按来源删干净、再写新的、最后反查自检</b>。
     *
     * <p><b>两条路（本地切片 / 百炼切片）共用这一份实现，不能各写各的。</b>
     * 这十几行是幂等性的全部实现——删除用的过滤表达式和写入前写进元数据的 {@code source}
     * 必须逐字一致。两份实现一旦漂移，表现是「每次同步多一份切片、检索召回重复内容」，
     * 而日志里只有一条 warn。抽在这里之后，「同一份文档永远只有一份」这个保证
     * 只由一个地方提供。
     *
     * <p>代价与上传路径完全相同：这两步不在一个事务里（{@code PgVectorStore} 内部没有事务），
     * 中途失败会让该来源的数据暂时为空，重新来一次即可。
     */
    private DocumentIndexResult write(String source, String category, List<Document> chunks) {
        // 过滤表达式用 Filter.Expression 而不是拼字符串：字符串版本要自己写 jsonpath 语法，
        // 拼错是运行时才失败，而且删 0 行也不报错
        vectorStore.delete(new FilterExpressionBuilder().eq(META_SOURCE, source).build());
        vectorStore.add(chunks);

        long characterCount = chunks.stream().mapToLong(chunk -> chunk.getText().length()).sum();
        log.info("[pgvector索引] {} 已入库：分类={} 切片={} 字符={}", source, category, chunks.size(), characterCount);

        // 自检。delete 不返回影响行数，只能反查——如果删除表达式和写入的 source 对不上，
        // 表现就是「重传后出现两份」，这里至少能让它在日志里现形
        long actual = countBySource(source);
        if (actual != chunks.size()) {
            log.warn("[pgvector索引] {} 期望 {} 片、实际查到 {} 片，可能存在残留切片",
                    source, chunks.size(), actual);
        }

        return new DocumentIndexResult(source, category, chunks.size(), characterCount);
    }

    /** 按来源删除该文档的全部切片。删不到任何东西不算错误（幂等）。 */
    public void deleteBySource(String source) {
        String normalized = requireFilename(source);
        vectorStore.delete(new FilterExpressionBuilder().eq(META_SOURCE, normalized).build());
        log.info("[pgvector索引] {} 的切片已删除", normalized);
    }

    /**
     * 索引前预览：这份文档会被切成什么样。<b>不写库、不调 Embedding 接口。</b>
     *
     * <p>走的是和 {@link #index} 完全相同的解析与切片，包括那些校验（编码不是 UTF-8、
     * 内容为空、切片数撞上限都会照常抛）——预览要是走了另一条宽松的路，
     * 它给的就是一个「上传之后才发现不是这样」的假象，比没有预览更糟。
     *
     * <p>代价是切片这一步会被重复做一次（预览一次、真上传一次）。这是划算的：
     * 切片是纯本地计算，而真上传那次还要花 Embedding 的钱。
     */
    public ChunkPreview preview(String originalFilename, Resource resource, String classification) {
        String source = requireFilename(originalFilename);
        String category = requireKnownClassification(classification);

        List<Document> chunks = split(read(resource, source, category), source);

        long characters = chunks.stream().mapToLong(chunk -> chunk.getText().length()).sum();
        boolean truncated = chunks.size() > PREVIEW_LIMIT;
        List<ChunkPreview.Chunk> previews = chunks.stream()
                .limit(PREVIEW_LIMIT)
                .map(chunk -> new ChunkPreview.Chunk(
                        // chunk_index 是 split() 打好的，和真正入库时的编号是同一个
                        intOf(chunk.getMetadata().get(META_CHUNK_INDEX)),
                        chunk.getText().length(),
                        abbreviate(chunk.getText())))
                .toList();

        log.info("[pgvector索引] 预览 {}：分类={} 切片={} 字符={}（未写库）",
                source, category, chunks.size(), characters);

        return new ChunkPreview(source, category, chunks.size(), characters, previews, truncated);
    }

    /**
     * 文档列表：按来源分组，一份文档一行。
     *
     * <p>{@link #stats()} 回答「有多少」，这个回答「有哪些」——管理页要能单独删掉某一份，
     * 光有聚合计数做不到。两者不合并，是因为用途不同：统计页要的是分类分布，
     * 管理页要的是逐份明细，硬塞进一个返回体只会让两边都别扭。
     *
     * <p>排序按最近入库时间倒序：刚传上去的排在最前面，符合「传完看一眼」的习惯。
     */
    public KnowledgeDocumentPage listDocuments(int page, int size) {
        String table = qualifiedTableName();
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 1);
        String classificationKey = PgVectorSql.safeMetadataKey(
                ragProperties.getRouter().getFilterKey(), "purify.rag.router.filter-key");

        Long total = pgJdbcTemplate.queryForObject(
                "SELECT count(DISTINCT metadata->>'" + META_SOURCE + "') FROM " + table, Long.class);

        // 分组里的 max()/min() 只是为了把「同一份文档的每一片都相同的那些字段」取出来一个。
        // sum(length(content)) 是这一份文档真正入库的字符总量——拿它和原文件比对，
        // 能一眼看出切片有没有把内容弄丢
        String sql = "SELECT metadata->>'" + META_SOURCE + "' AS source, "
                + "count(*) AS chunks, "
                + "COALESCE(sum(length(content)), 0) AS characters, "
                + "COALESCE(max(metadata->>'" + classificationKey + "'), '" + UNLABELED + "') AS classification, "
                + "COALESCE(max(metadata->>'" + META_UPLOADED_AT + "'), '') AS uploaded_at "
                + "FROM " + table + " "
                + "GROUP BY 1 "
                + "ORDER BY max(metadata->>'" + META_UPLOADED_AT + "') DESC NULLS LAST, 1 ASC "
                + "LIMIT ? OFFSET ?";

        List<KnowledgeDocumentItem> items = pgJdbcTemplate.query(sql, (resultSet, rowNum) ->
                new KnowledgeDocumentItem(
                        resultSet.getString("source"),
                        resultSet.getLong("chunks"),
                        resultSet.getLong("characters"),
                        resultSet.getString("classification"),
                        resultSet.getString("uploaded_at")),
                safeSize, (long) (safePage - 1) * safeSize);

        return new KnowledgeDocumentPage(total == null ? 0 : total, safePage, safeSize, items);
    }

    /**
     * 向量库概览。
     *
     * <p>这是本类唯一真正连库的方法——纯单元测试构造本类时会传 null 的 JdbcTemplate，
     * 且不调用它。
     */
    public KnowledgeBaseStats stats() {
        String table = qualifiedTableName();

        Long total = pgJdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        Map<String, Long> bySource = groupCount(table, META_SOURCE);
        // 分类字段名取自配置：用户改了 filter-key 之后，统计口径应当跟着变
        Map<String, Long> byClassification = groupCount(table, ragProperties.getRouter().getFilterKey());

        return new KnowledgeBaseStats(total == null ? 0 : total, bySource.size(), bySource, byClassification);
    }

    /**
     * 批量查「这几个来源在本地有没有、多少片、什么时候进来的、带着哪些同步标记」。
     *
     * <p>给知识库同步用：一页最多列一百份百炼文档，逐份去查就是一百次数据库往返，
     * 所以拼一条带 IN 的 SQL 一次拿完。
     *
     * <p><b>两个标记键由调用方传入，而不是在本类里写死</b>：它们是「哪一方、在什么时间
     * 把这份文档推进来的」这类<b>来源侧</b>的记账信息，写进去的那一方才知道它们叫什么。
     * 本类只管按名字读回来——和 {@link #groupCount} 按配置的 filter-key 分组是同一个做法。
     *
     * @param sources        要查的来源标识，通常是文件名
     * @param fileIdKey      存「来源侧文件标识」的元数据键；本地手动上传的文档没有这个键
     * @param gmtModifiedKey 存「来源侧文件修改时间」的元数据键，值是原始长整型
     * @return 来源 → 摘要。查不到的来源<b>不会</b>出现在返回的 Map 里，而不是映射成一份空摘要——
     *         调用方要靠这个区别来分辨「本地压根没有」和「本地有但是空的」，后者不是一回事
     */
    public Map<String, SyncedSource> summariesBySources(List<String> sources,
                                                        String fileIdKey,
                                                        String gmtModifiedKey) {
        if (sources == null || sources.isEmpty()) {
            return Map.of();
        }

        String table = qualifiedTableName();
        String fileKey = PgVectorSql.safeMetadataKey(fileIdKey, "同步标记元数据键（文件标识）");
        String modifiedKey = PgVectorSql.safeMetadataKey(gmtModifiedKey, "同步标记元数据键（修改时间）");

        // 占位符按个数拼，值一律走参数。listDocuments 已经立了参数化的规矩，
        // 这里不因为「值是自家的文件名」就开一个拼字符串的口子
        String placeholders = String.join(", ", Collections.nCopies(sources.size(), "?"));
        String sql = "SELECT metadata->>'" + META_SOURCE + "' AS source, "
                + "count(*) AS chunks, "
                + "COALESCE(max(metadata->>'" + META_UPLOADED_AT + "'), '') AS uploaded_at, "
                + "max(metadata->>'" + fileKey + "') AS file_id, "
                + "max(metadata->>'" + modifiedKey + "') AS gmt_modified "
                + "FROM " + table + " "
                + "WHERE metadata->>'" + META_SOURCE + "' IN (" + placeholders + ") "
                + "GROUP BY 1";

        // 用 RowMapper 而不是 RowCallbackHandler：后者那个重载和 RowMapper 的
        // query(String, ..., Object...) 在块状 lambda 下会被 javac 判成歧义
        List<SyncedSource> rows = pgJdbcTemplate.query(sql, (resultSet, rowNum) -> new SyncedSource(
                resultSet.getString("source"),
                resultSet.getLong("chunks"),
                resultSet.getString("uploaded_at"),
                resultSet.getString("file_id"),
                parseLong(resultSet.getString("gmt_modified"))), sources.toArray());

        Map<String, SyncedSource> summaries = new LinkedHashMap<>();
        for (SyncedSource row : rows) {
            summaries.put(row.source(), row);
        }
        return summaries;
    }

    // ==================== 内部实现 ====================

    /**
     * 文档预处理：按后缀选 Reader 解析成纯文本。
     *
     * <p><b>只按后缀判定，不看 Content-Type</b>——这和图片那边
     * （{@code SlimAppController#resolveImageType} 优先信 Content-Type）的取向正好相反，
     * 原因是文本没有可靠的魔数可以验，后缀是唯一可判定的信息；
     * 而判错的后果也不一样：图片判错会把一堆不确定的字节丢给模型，
     * 文本判错最多是读到一段乱码，后面还有编码检查兜着。
     */
    private List<Document> read(Resource resource, String source, String classification) {
        String extension = StringUtils.getFilenameExtension(source);
        String normalizedExtension = extension == null ? "" : extension.toLowerCase(Locale.ROOT);

        DocumentReader reader = DocFormat.of(normalizedExtension)
                .orElseThrow(() -> ApiException.unsupportedDocument(
                        "error.kb.extensionUnsupported", DocFormat.extensions(), source, normalizedExtension))
                .reader(resource);

        List<Document> documents = reader.get();
        if (documents.isEmpty() || documents.stream().allMatch(document -> !StringUtils.hasText(document.getText()))) {
            throw ApiException.documentIndexFailed("error.kb.contentEmpty", source);
        }

        String docName = source;
        String fallbackTitle = stripExtension(source);
        String uploadedAt = Instant.now().toString();
        String filterKey = ragProperties.getRouter().getFilterKey();

        for (Document document : documents) {
            // 统一在读完之<后>覆盖元数据，而不是靠各 Reader 自带的 customMetadata：
            // TextReader 自己就会往 metadata 里写一个 source（值是资源的描述串），
            // 正好和我们的幂等锚点同名。不覆盖的话，写入时是 A、删除时按 B 过滤，
            // 结果是「删 0 行、不报错、每次上传都多一份切片」
            Map<String, Object> metadata = document.getMetadata();
            metadata.put(META_SOURCE, source);
            metadata.put(META_DOC_NAME, docName);
            // md 的每个小节由 Reader 标好了标题，不能覆盖；txt 没有标题，用文件名兜底
            metadata.putIfAbsent(META_TITLE, fallbackTitle);
            metadata.put(filterKey, classification);
            metadata.put(META_UPLOADED_AT, uploadedAt);
        }

        verifyEncoding(documents, source);
        return documents;
    }

    /**
     * 支持的文档格式，以及「这个后缀该用哪个 Reader 解析」。
     *
     * <p><b>为什么把这两件事放在一起</b>：它们本来是同一件事的两半——「支持哪些格式」是
     * 说给用户听的，{@code read()} 里的分派是做给程序看的，但两者必须是同一份清单。
     * 拆成「一个 {@code SUPPORTED_EXTENSIONS} 常量 + 一个 switch」时，加一种格式要改两处，
     * 而且没有任何机制保证两处同步：switch 的 {@code default} 会吃掉编译期检查，
     * 白名单又会被拼进报错文案。于是漂移的表现是<b>自相矛盾的提示</b>——
     * 弹窗里写着「只支持 [txt, md, markdown]」，而你传的正是一个 md，它却被 default 拦下了。
     *
     * <p>合成枚举之后，加一种格式 = 加一个常量，白名单和分派一起生效，
     * 报错文案也自动跟着对。
     *
     * <p><b>只按后缀判定，不看 Content-Type</b>，理由见 {@link #read}。
     */
    private enum DocFormat {

        TXT("txt", TextReader::new),

        MD("md", DocFormat::markdownReader),

        MARKDOWN("markdown", DocFormat::markdownReader);

        private final String extension;

        private final Function<Resource, DocumentReader> readerFactory;

        DocFormat(String extension, Function<Resource, DocumentReader> readerFactory) {
            this.extension = extension;
            this.readerFactory = readerFactory;
        }

        DocumentReader reader(Resource resource) {
            return readerFactory.apply(resource);
        }

        /**
         * 支持的后缀清单，按声明顺序——它会原样出现在「只支持 {0} 这几种文本格式」那条
         * 报错里，顺序固定下来，用户对比自己的文件时不用在两个顺序之间来回找。
         */
        static Set<String> extensions() {
            Set<String> extensions = new LinkedHashSet<>();
            for (DocFormat format : values()) {
                extensions.add(format.extension);
            }
            return extensions;
        }

        /** 后缀对应的格式，已转小写；认不出来时返回空，由调用方决定报什么错。 */
        static Optional<DocFormat> of(String extension) {
            for (DocFormat format : values()) {
                if (format.extension.equals(extension)) {
                    return Optional.of(format);
                }
            }
            return Optional.empty();
        }

        private static DocumentReader markdownReader(Resource resource) {
            return new MarkdownDocumentReader(resource, MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(false)
                    // 代码块对健康知识库是噪声，索引进去只会稀释召回
                    .withIncludeCodeBlock(false)
                    .withIncludeBlockquote(true)
                    .build());
        }
    }

    /** 切片，并给每片打上序号。 */
    private List<Document> split(List<Document> documents, String source) {
        List<Document> chunks = splitter.apply(documents);

        if (chunks.isEmpty()) {
            throw ApiException.documentIndexFailed("error.kb.chunksEmpty", source);
        }

        // TokenTextSplitter 到 maxNumChunks 就不再加了，超出的内容被静默丢弃。
        // 撞上上限就当作失败处理，而不是索引一份「只有前一半」的文档
        int maxNumChunks = pgVectorProperties.getChunk().getMaxNumChunks();
        if (chunks.size() >= maxNumChunks) {
            throw ApiException.documentIndexFailed("error.kb.chunkLimitReached", maxNumChunks, source);
        }

        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).getMetadata().put(META_CHUNK_INDEX, i);
        }
        return chunks;
    }

    /** 编码检查：替换字符占比过高说明文件不是 UTF-8。 */
    private static void verifyEncoding(List<Document> documents, String source) {
        long total = 0;
        long replaced = 0;
        for (Document document : documents) {
            String text = document.getText();
            total += text.length();
            replaced += text.chars().filter(character -> character == '�').count();
        }
        if (total > 0 && (double) replaced / total > REPLACEMENT_CHAR_RATIO_LIMIT) {
            throw ApiException.documentIndexFailed("error.kb.notUtf8", replaced, total, source);
        }
    }

    /**
     * 分类必须是配置里已知的值。
     *
     * <p>过滤条件是「字段 = 值」的等值匹配，值写错了查不到任何东西、也不报错。
     * 百炼那边因为打标在控制台里做，只能靠人细心；本地这边能在入口拦住，就别放过。
     */
    private String requireKnownClassification(String classification) {
        if (!StringUtils.hasText(classification)) {
            throw ApiException.unsupportedDocument("error.kb.classificationRequired");
        }
        String category = classification.trim();

        List<String> known = ragProperties.getRouter().getCategories().stream()
                .map(RagProperties.Category::getValue)
                .filter(StringUtils::hasText)
                .toList();
        // 分类表没配时不拦：配置缺失不该让上传功能整个不可用
        if (!known.isEmpty() && !known.contains(category)) {
            throw ApiException.unsupportedDocument("error.kb.classificationUnknown", category, known);
        }
        return category;
    }

    private static String requireFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            throw ApiException.unsupportedDocument("error.kb.filenameRequired");
        }
        return filename.trim();
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String qualifiedTableName() {
        return PgVectorSql.qualifiedTableName(pgVectorProperties);
    }

    private long countBySource(String source) {
        Long count = pgJdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + qualifiedTableName() + " WHERE metadata->>'" + META_SOURCE + "' = ?",
                Long.class, source);
        return count == null ? 0 : count;
    }

    /**
     * 按某个元数据字段分组计数。
     *
     * <p>用 {@code metadata->>'字段'} 而不是检索时那套 {@code metadata::jsonb @@ jsonpath}：
     * 这里只需要「按值分组」，普通取值语法更简单也更快。
     */
    private Map<String, Long> groupCount(String table, String metadataKey) {
        String key = PgVectorSql.safeMetadataKey(metadataKey, "purify.rag.router.filter-key");
        String sql = "SELECT COALESCE(metadata->>'" + key + "', '" + UNLABELED + "') AS key, count(*) AS total "
                + "FROM " + table + " GROUP BY 1 ORDER BY 2 DESC";

        Map<String, Long> counts = new LinkedHashMap<>();
        pgJdbcTemplate.query(sql, resultSet -> {
            counts.put(resultSet.getString("key"), resultSet.getLong("total"));
        });
        return counts;
    }

    /** 从元数据里取一个整数。类型对不上时退回 0，不让它把一次预览变成异常。 */
    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 把元数据里取回来的长整型文本解析成数字；解析不了退回 {@code null}。
     *
     * <p>元数据过一道 JSON 序列化，数字取回来是文本，且不保证合法（可能是人手写进库的，
     * 也可能是更早的版本写的别的格式）。一个存坏的标记不该让整页清单打不开——
     * 退成 null 的表现是那一行显示「未同步」，而不是整个接口 500。
     */
    private static Long parseLong(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Long.valueOf(text.trim());
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= PREVIEW_EXCERPT_LENGTH
                ? flattened
                : flattened.substring(0, PREVIEW_EXCERPT_LENGTH) + "…";
    }
}
