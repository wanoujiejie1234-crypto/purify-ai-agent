package com.purify.purifyaiagent.rag.pgvector;

import com.purify.purifyaiagent.config.PgVectorProperties;
import com.purify.purifyaiagent.config.RagProperties;
import com.purify.purifyaiagent.exception.DocumentIndexException;
import com.purify.purifyaiagent.exception.UnsupportedDocumentException;
import com.purify.purifyaiagent.model.DocumentIndexResult;
import com.purify.purifyaiagent.model.KnowledgeBaseStats;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /** 支持的文档后缀。文本没有可靠的魔数，只能按后缀判定。 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md", "markdown");

    /**
     * 替换字符（U+FFFD）占比超过这个比例就认为编码不对。
     *
     * <p>Reader 一律按 UTF-8 读，Windows 上用记事本存出来的 GBK 文件会被整篇读成
     * 替换字符——不拦的话会把一锅乱码老老实实索引进去，检索时还查不出来问题在哪。
     */
    private static final double REPLACEMENT_CHAR_RATIO_LIMIT = 0.01;

    /** 表名/模式名的合法字符，与 {@code PgVectorSchemaValidator} 用的是同一套规则。 */
    private static final String SAFE_IDENTIFIER = "^[a-zA-Z0-9_]{1,64}$";

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
        // 而不是等到某次查询报语法错误
        assertSafeIdentifier(pgVectorProperties.getSchemaName(), "purify.rag.pgvector.schema-name");
        assertSafeIdentifier(pgVectorProperties.getTableName(), "purify.rag.pgvector.table-name");

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
        List<Document> chunks = split(read(resource, source, category), source);

        // 3. 向量转换与存储。先删后写，保证同一来源只有一份。
        //    过滤表达式用 Filter.Expression 而不是拼字符串：字符串版本要自己写 jsonpath 语法，
        //    拼错是运行时才失败，而且删 0 行也不报错
        vectorStore.delete(new FilterExpressionBuilder().eq(META_SOURCE, source).build());
        vectorStore.add(chunks);

        long characterCount = chunks.stream().mapToLong(chunk -> chunk.getText().length()).sum();
        log.info("[pgvector索引] {} 已入库：分类={} 切片={} 字符={}", source, category, chunks.size(), characterCount);

        // 4. 自检。delete 不返回影响行数，只能反查——如果删除表达式和写入的 source 对不上，
        //    表现就是「重传后出现两份」，这里至少能让它在日志里现形
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

        DocumentReader reader = switch (normalizedExtension) {
            case "txt" -> new TextReader(resource);
            case "md", "markdown" -> new MarkdownDocumentReader(resource, MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(false)
                    // 代码块对健康知识库是噪声，索引进去只会稀释召回
                    .withIncludeCodeBlock(false)
                    .withIncludeBlockquote(true)
                    .build());
            default -> throw new UnsupportedDocumentException(
                    "只支持 " + SUPPORTED_EXTENSIONS + " 这几种文本格式，当前文件是「" + source + "」，"
                            + "识别出的后缀是「" + normalizedExtension + "」。"
                            + "PDF / Word 需要先转成文本再上传。");
        };

        List<Document> documents = reader.get();
        if (documents.isEmpty() || documents.stream().allMatch(document -> !StringUtils.hasText(document.getText()))) {
            throw new DocumentIndexException("文档内容为空，没什么可索引的：" + source);
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

    /** 切片，并给每片打上序号。 */
    private List<Document> split(List<Document> documents, String source) {
        List<Document> chunks = splitter.apply(documents);

        if (chunks.isEmpty()) {
            throw new DocumentIndexException("切片结果为空，文档可能只有空白字符：" + source);
        }

        // TokenTextSplitter 到 maxNumChunks 就不再加了，超出的内容被静默丢弃。
        // 撞上上限就当作失败处理，而不是索引一份「只有前一半」的文档
        int maxNumChunks = pgVectorProperties.getChunk().getMaxNumChunks();
        if (chunks.size() >= maxNumChunks) {
            throw new DocumentIndexException(
                    "切片数达到上限 " + maxNumChunks + "，超出的内容会被丢弃。请把文档拆小后分几次上传，"
                            + "或调大 purify.rag.pgvector.chunk.max-num-chunks：" + source);
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
            throw new DocumentIndexException(
                    "文档内容不是有效的 UTF-8（" + replaced + "/" + total + " 个字符无法解码）：" + source
                            + "。请另存为 UTF-8 编码后重新上传。");
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
            throw new UnsupportedDocumentException("必须指定 classification（归档到哪一类）");
        }
        String category = classification.trim();

        List<String> known = ragProperties.getRouter().getCategories().stream()
                .map(RagProperties.Category::getValue)
                .filter(StringUtils::hasText)
                .toList();
        // 分类表没配时不拦：配置缺失不该让上传功能整个不可用
        if (!known.isEmpty() && !known.contains(category)) {
            throw new UnsupportedDocumentException(
                    "未知的分类「" + category + "」，可选值来自 purify.rag.router.categories：" + known);
        }
        return category;
    }

    private static String requireFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            throw new UnsupportedDocumentException("文件名不能为空：它同时用作切片来源标识，删改都靠它");
        }
        return filename.trim();
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String qualifiedTableName() {
        return pgVectorProperties.getSchemaName() + "." + pgVectorProperties.getTableName();
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
        String sql = "SELECT COALESCE(metadata->>'" + metadataKey + "', '(未标注)') AS key, count(*) AS total "
                + "FROM " + table + " GROUP BY 1 ORDER BY 2 DESC";

        Map<String, Long> counts = new LinkedHashMap<>();
        pgJdbcTemplate.query(sql, resultSet -> {
            counts.put(resultSet.getString("key"), resultSet.getLong("total"));
        });
        return counts;
    }

    private static void assertSafeIdentifier(String identifier, String propertyName) {
        if (identifier == null || !identifier.matches(SAFE_IDENTIFIER)) {
            throw new IllegalStateException(
                    propertyName + " 只能是字母、数字、下划线，且不超过 64 个字符，当前是：" + identifier);
        }
    }
}
