package com.purify.purifyaiagent.rag.pgvector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.purify.purifyaiagent.config.PgVectorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 关键词检索那一路 —— 流程图里与「向量检索」并行的那条支路。
 *
 * <p>它做的是<b>字面上的精确匹配</b>：把问题拆成几个检索词
 * （见 {@link KeywordTermExtractor}），在 {@code content} 上做 {@code LIKE '%词%'}，
 * 按命中词数排序。补的是向量检索的固有短板——「利拉鲁肽」这类专有名词在
 * embedding 空间里没有稳定的邻域。
 *
 * <h2>为什么不走 VectorStore</h2>
 *
 * <p>Spring AI 的 {@code similaritySearch} 把 SQL 拼死了（{@code SELECT *, embedding <=> ? ...}），
 * 关键词那一路插不进去，只能自己拿 {@code JdbcTemplate} 写。
 *
 * <h2>为什么不实现 DocumentRetriever</h2>
 *
 * <p>{@code RagCommonConfig.knowledgeSearch(DocumentRetriever retriever, ...)} 是<b>按类型注入</b>的，
 * 那条链路的注释里写明「必然恰好一个检索器，不存在两个候选」。这里多实现一个
 * {@code DocumentRetriever} Bean 会让它当场抛 {@code NoUniqueBeanDefinitionException}。
 * 所以关键词路是一个<b>别的类型</b>的 Bean，由 {@code PgVectorDocumentRetriever} 显式持有。
 *
 * <h2>降级：探测，而不是 catch 异常</h2>
 *
 * <p><b>这一点是整个类最要紧的设计。</b>本类的 SQL 只引用 PostgreSQL 核心操作符
 * （{@code LIKE}、{@code length}、{@code ->>}），<b>不调用任何 pg_bigm 函数</b>——
 * 所以 pg_bigm 没装时查询<b>不会报错</b>，只会静默退化成整表扫描。
 * 那比报错危险得多：它是一个看不见的成本，而且这一路的召回质量无从判断。
 *
 * <p>所以没有扩展时<b>不能靠 catch 异常发现</b>，只能在启动期主动探测
 * （见 {@link #status()}），探不到就明确停用并打一条能照着做的 WARN。
 */
@Slf4j
public class PgKeywordSearcher {

    /**
     * 命中词数的元数据键。
     *
     * <p>不进提示词（{@code RagPrompts.DOCUMENT_FORMATTER} 只读 index_id / doc_name / title
     * 与正文），它服务于日志和 {@code /api/rag/search} 自检接口。
     */
    public static final String META_HIT_COUNT = "keyword_hit_count";

    /** FROM 子句用的表别名，SQL 里各处引用要和它对上。 */
    private static final String ALIAS = "c";

    /**
     * 内联模式下允许出现在检索词里的字符。
     *
     * <p>和 {@code KeywordTermExtractor.isContentChar} 是同一套字符集——那里已经把所有
     * 非「汉字 / 字母数字」的字符剥掉了，这里是<b>拼进 SQL 之前的最后一道断言</b>。
     * 没有引号、没有反斜杠、没有通配符，构造不出注入。
     */
    private static final Pattern SAFE_INLINE_TERM = Pattern.compile("^[\\p{IsHan}a-z0-9]+$");

    /**
     * 探测 SQL。
     *
     * <p><b>只引用核心目录与核心函数</b>，所以 pg_bigm 没装时它照样能跑——
     * 这正是「主动探测」相对于「try 一下看看」的全部价值。{@code to_regclass}
     * 在索引不存在时返回 NULL，{@code pg_get_indexdef} 跟着返回 NULL。
     *
     * <p><b>第一列和第二列是判断依据，其余全是「我看到了什么」。</b>它们的存在是为了让
     * 失败信息<b>自解释</b>：光说「检测不到 pg_bigm」，对着的是一个可能装着几十个扩展的库，
     * 而实际原因就三种——装到别的库去了、这台机器压根没装、只装了扩展没建索引。
     * 把清单打出来，这三种一眼就能分辨，不用再让人手工去连库比对。
     *
     * <p>第二列（{@code pg_available_extensions}）是关键的一格：它列的是<b>这台服务器上
     * 有安装文件</b>的扩展，与库无关。所以「它非空 + 本库的 {@code pg_extension} 里没有」
     * 就等于「装到别的库去了」——这一条用户已经白跑过一轮，不写进来还会再犯。
     *
     * <p>最后两列（库名、服务器地址）是为了回答「应用到底连的是哪个库」。
     * 这个问题看着蠢，但排查时它恰恰是唯一没被固定住的变量。
     */
    private static final String PROBE_SQL =
            "SELECT (SELECT count(*) FROM pg_extension WHERE extname = 'pg_bigm') AS pg_bigm, "
                    + "(SELECT count(*) FROM pg_available_extensions "
                    + "  WHERE name = 'pg_bigm') AS pg_bigm_available, "
                    + "(SELECT string_agg(extname, ', ' ORDER BY extname) FROM pg_extension) "
                    + "  AS installed_extensions, "
                    + "(SELECT string_agg(indexname, ', ' ORDER BY indexname) FROM pg_indexes "
                    + "  WHERE schemaname = ? AND tablename = ?) AS table_indexes, "
                    + "pg_get_indexdef(to_regclass(?)) AS indexdef, "
                    + "current_database() AS database_name, "
                    + "coalesce(inet_server_addr()::text, '本地套接字') "
                    + "  || ':' || coalesce(inet_server_port()::text, '-') AS server_address";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate pgJdbcTemplate;

    private final PgVectorProperties pgVectorProperties;

    private final KeywordTermExtractor extractor;

    /** 探测结果。懒算 + 记忆化：探测要连库，不该在构造器里做（那样连不上会拖慢启动）。 */
    private volatile KeywordArmStatus status;

    /** 上次探测的时刻，{@link System#nanoTime()} 口径。只用于给「不可用」的结果算过期。 */
    private volatile long probedAt;

    /** 上次打出去的那条「不可用」原因，用来给重复的 WARN 去重。见 {@link #firstTimeFor}。 */
    private volatile String lastUnavailableReason;

    public PgKeywordSearcher(JdbcTemplate pgJdbcTemplate,
                             PgVectorProperties pgVectorProperties,
                             KeywordTermExtractor extractor) {
        this.pgJdbcTemplate = pgJdbcTemplate;
        this.pgVectorProperties = pgVectorProperties;
        this.extractor = extractor;
    }

    /**
     * 这一路的工作状态。<b>第一次调用时才真正探测</b>，之后按下面的规则取用缓存。
     *
     * <p>启动时 {@code RagCommonConfig} 的体检日志会调它，所以探测实际发生在启动期，
     * 但又不至于把「连库」这件事塞进构造器。
     *
     * <p><b>可用是稳定的，不可用不是</b>（见 {@link #expired}）：探到可用就一直用，
     * 探到不可用只认一小会儿。
     */
    public KeywordArmStatus status() {
        KeywordArmStatus current = status;
        if (current != null && !expired(current)) {
            return current;
        }
        synchronized (this) {
            if (status == null || expired(status)) {
                status = probe();
                probedAt = now();
            }
            return status;
        }
    }

    /**
     * 现在几点（{@link System#nanoTime()} 口径，只用于算时间差）。
     *
     * <p>抽成方法是为了给单元测试留接缝：验证「过期重探」不必真睡六十秒。
     * 与 {@code queryRows} / {@code probeRow} 是同一个思路。
     */
    long now() {
        return System.nanoTime();
    }

    /**
     * 「不可用」这个结论要不要作废。
     *
     * <p><b>可用是稳定的，不可用不是。</b>索引被删这种事落到 {@link #search} 的
     * catch 那条路上，不必在这里重复发现；反过来，扩展没装是<b>人可以去修</b>的，
     * 而修好之后应用要是还固执地说「检测不到」，排查时看着就像「修了没用」——
     * 用户已经为这个白跑过两轮。
     *
     * <p>代价是这一路不可用时每次检索多一次 catalog 查询（`queryForMap`，微秒级）。
     * 相比同一次检索里必然发生的 embedding 与重排网络往返，可以忽略。
     */
    private boolean expired(KeywordArmStatus current) {
        if (current.available()) {
            return false;
        }
        if (!current.enabled()) {
            // 配置里关掉的。这种「不可用」是人明确要求的，重探只会每 60 秒重复刷一条
            // INFO，且结论永远不会变——它跟「探不到」不是一回事
            return false;
        }
        int retrySeconds = pgVectorProperties.getKeyword().getProbeRetrySeconds();
        return retrySeconds > 0
                && now() - probedAt >= TimeUnit.SECONDS.toNanos(retrySeconds);
    }

    /**
     * 这条原因是不是第一次出现。
     *
     * <p>「不可用」现在会定期重探，而那条 WARN 有二十来行——每探一次刷一遍会把日志淹掉。
     * 原因变了才说：原因没变就说明情况跟上次一模一样，重复没有信息量。
     */
    private boolean firstTimeFor(String reason) {
        if (reason.equals(lastUnavailableReason)) {
            return false;
        }
        lastUnavailableReason = reason;
        return true;
    }

    /** 这次问题会拆出哪些检索词。给自检接口回显用——切得对不对，看一眼就知道。 */
    public List<String> termsOf(String question) {
        return extractor.extract(question);
    }

    /**
     * 检索。
     *
     * @param question 用户这一轮的原始提问
     * @param filter   分类过滤。<b>与向量那一路共用同一份判定</b>（见 {@link ClassificationFilter}），
     *                 两路按不同的分类去查是这一路上最隐蔽的错法
     * @param limit    最多返回几条
     * @return 命中的切片，<b>顺序即名次</b>；不可用或提不出词时返回空列表，不抛异常
     */
    public List<Document> search(String question, ClassificationFilter filter, int limit) {
        if (!status().available() || limit <= 0) {
            return List.of();
        }

        List<String> terms = extractor.extract(question);
        if (terms.isEmpty()) {
            // 提不出词就**根本不发这次查询**。发一条注定匹配不到任何东西、
            // 却要扫一遍表的 SQL，只是白白花掉一次数据库往返
            return List.of();
        }

        String sql = buildSql(terms, filter);
        Object[] args = buildArgs(terms, filter, limit);
        try {
            return queryRows(sql, args);
        }
        catch (DataAccessException exception) {
            // 探测时还好好的、用的时候炸了（权限被回收、连接超时、索引被删）。
            // 只记一条、返回空——向量那一路照常，与「重排抖动就退回粗排」同一个取向
            log.warn("[pgvector] 关键词检索失败，这一次退化成纯向量检索：{}", exception.getMessage());
            return List.of();
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 探测这一路能不能工作。
     *
     * <p>五种结果，处置各不相同，见下面的分支注释。
     */
    private KeywordArmStatus probe() {
        PgVectorProperties.Keyword config = pgVectorProperties.getKeyword();
        if (!config.isEnabled()) {
            log.info("[pgvector] 关键词检索已关闭（purify.rag.pgvector.keyword.enabled=false）");
            return KeywordArmStatus.disabled();
        }
        if (pgJdbcTemplate == null) {
            // 纯单元测试构造本类时不给 JdbcTemplate（项目里已有的惯例，见 PgVectorIndexService）。
            // 这时不假装自己可用，但也不抛
            return new KeywordArmStatus(true, false, "没有数据库连接");
        }

        boolean extensionPresent;
        boolean extensionAvailableOnServer;
        String indexDefinition;
        String installedExtensions;
        String tableIndexes;
        String databaseName;
        String serverAddress;
        try {
            Map<String, Object> row = probeRow(qualifiedIndexName());
            extensionPresent = toLong(row.get("pg_bigm")) > 0;
            extensionAvailableOnServer = toLong(row.get("pg_bigm_available")) > 0;
            indexDefinition = row.get("indexdef") == null ? null : String.valueOf(row.get("indexdef"));
            installedExtensions = orNone(row.get("installed_extensions"));
            tableIndexes = orNone(row.get("table_indexes"));
            databaseName = orNone(row.get("database_name"));
            serverAddress = orNone(row.get("server_address"));
        }
        catch (DataAccessException exception) {
            // 探不了就不当它可用。对关键词这一路保守关闭，但**不阻断启动**——
            // 与连接池 initializationFailTimeout(-1) 的取向一致
            log.warn("[pgvector] 关键词检索探测失败（数据库连不上或权限不足），这一路先停用：{}",
                    exception.getMessage());
            return new KeywordArmStatus(true, false, "探测失败：" + exception.getMessage());
        }

        boolean indexReady = indexDefinition != null
                && indexDefinition.toLowerCase(Locale.ROOT).contains("using gin");

        if (extensionPresent && indexReady) {
            // 把索引定义全文打出来：一眼能看出用的是 gin_bigm_ops 还是（兜底时的）gin_trgm_ops
            log.info("[pgvector] 关键词检索已启用：{}", indexDefinition);
            // 曾经不可用、现在好了。清掉去重标记，好让将来再坏时那条 WARN 能重新打出来
            lastUnavailableReason = null;
            return KeywordArmStatus.ready();
        }

        // 「我看到了什么」随原因一起带上。见 PROBE_SQL 的注释：失败的实际原因就那两种，
        // 把清单摆出来让人一眼分辨，而不是让他自己连库去比对
        String observed = "连的是 " + databaseName + "（" + serverAddress + "）"
                + "；这个库里已装的扩展：" + installedExtensions
                + "；表上已有的索引：" + tableIndexes;
        String missing = extensionPresent
                ? "找不到关键词索引 " + qualifiedIndexName() + "（扩展装了但索引没建）。" + observed
                : extensionAvailability(extensionAvailableOnServer) + observed;

        if (!config.isRequireExtension()) {
            if (firstTimeFor(missing)) {
                log.warn("[pgvector] 关键词检索在缺少索引的情况下被启用了（require-extension=false）。"
                        + "每次检索都会整表扫描，只适合几千条以下的小库。缺的是：{}", missing);
            }
            return new KeywordArmStatus(true, true, null);
        }

        if (firstTimeFor(missing)) {
            log.warn(unavailableMessage(missing));
        }
        return new KeywordArmStatus(true, false, missing);
    }

    /**
     * 扩展不在这个库里时的说法——<b>「装到别处了」和「压根没装」必须分开说</b>。
     *
     * <p>这两种的修法毫无交集（一个是换个库重跑 CREATE EXTENSION，一个是去装包），
     * 而它们在日志里长得一模一样。用户已经为这个差别白跑过一轮：
     * 明明在服务器上执行了 {@code CREATE EXTENSION pg_bigm}，应用却说检测不到——
     * 因为那一句执行时连的不是应用连的这个库。
     *
     * <p>判据是 {@code pg_available_extensions}：它列的是**这台服务器上有安装文件**的扩展，
     * 与当前库无关。所以「它非空 + 本库的 pg_extension 里没有」就等价于「装到别的库去了」。
     */
    private static String extensionAvailability(boolean availableOnServer) {
        return availableOnServer
                ? "检测不到 pg_bigm 扩展，但这台服务器上**有它的安装文件**——"
                        + "CREATE EXTENSION 是按库生效的，多半是执行那句时连的不是这个库。"
                : "检测不到 pg_bigm 扩展，这台服务器上也**没有它的安装文件**"
                        + "（包没装，或者连的根本是另一台机器）。";
    }

    /**
     * 这一路不可用时的 WARN 正文。
     *
     * <p>写得这么长是有意的：读到这条日志的人手上多半正拿着一份「检索效果不好」的
     * 报告，而这句话要能让他直接动手，不必再去翻文档。
     */
    private static String unavailableMessage(String reason) {
        return "\n[pgvector] 关键词检索那一路没有启用：" + reason + "\n"
                + "  原因：pg_bigm 是**静态加载**的扩展，装它要动服务端配置，顺序是\n"
                + "        1) 把 pg_bigm 加进 postgresql.conf 的 shared_preload_libraries\n"
                + "        2) 重启 PostgreSQL\n"
                + "        3) 用超级用户执行 CREATE EXTENSION pg_bigm;\n"
                + "        版本要求：PG 10~15 需内核小版本 >= 20230830，PG 17 需 >= 20250830\n"
                + "  影响：检索退化成**纯向量检索**。「利拉鲁肽」这类专有名词的精确匹配会变差，\n"
                + "        其余一切正常，向量那一路完全不受影响。\n"
                + "  若这台库改不了 shared_preload_libraries（托管实例常见），退路是 pg_trgm：\n"
                + "        CREATE EXTENSION pg_trgm;\n"
                + "        CREATE INDEX ... USING gin (content gin_trgm_ops);\n"
                + "        再把 keyword.index-name 指过去、keyword.min-term-length 提到 3。\n"
                + "        中文效果比 pg_bigm 差，但比关掉这一路好，且**不需要重启**。\n"
                + "  若想先在没有索引的库上跑起来（会整表扫描，几千条以下可以接受）：\n"
                + "        purify.rag.pgvector.keyword.require-extension=false\n";
    }

    /** 跑一次探测查询。抽成方法是为了给单元测试留接缝（避开 Mockito 的 varargs 麻烦）。 */
    Map<String, Object> probeRow(String indexName) {
        return pgJdbcTemplate.queryForMap(PROBE_SQL,
                pgVectorProperties.getSchemaName(), pgVectorProperties.getTableName(), indexName);
    }

    /** 关键词索引的全名。两段都过标识符校验。 */
    private String qualifiedIndexName() {
        return PgVectorSql.assertSafeIdentifier(
                pgVectorProperties.getSchemaName(), "purify.rag.pgvector.schema-name")
                + "."
                + PgVectorSql.assertSafeIdentifier(
                        pgVectorProperties.getKeyword().effectiveIndexName(pgVectorProperties.getTableName()),
                        "purify.rag.pgvector.keyword.index-name");
    }

    /**
     * 拼检索 SQL。
     *
     * <p>每个检索词出现<b>两次</b>：{@code SELECT} 里一次（算命中数，用来排序）、
     * {@code WHERE} 里一次（真正过滤、也是走索引的那一处）。两次都不省——
     * 少了 SELECT 那次就没有排序依据，少了 WHERE 那次就要把整表拉回内存再筛。
     */
    String buildSql(List<String> terms, ClassificationFilter filter) {
        String table = PgVectorSql.qualifiedTableName(pgVectorProperties);

        StringBuilder hits = new StringBuilder();
        StringBuilder matches = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            String operand = likeOperand(terms.get(i));
            if (i > 0) {
                hits.append(" + ");
                matches.append(" OR ");
            }
            hits.append('(').append(ALIAS).append(".content LIKE ").append(operand)
                    .append(" ESCAPE '\\')::int");
            matches.append(ALIAS).append(".content LIKE ").append(operand).append(" ESCAPE '\\'");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(ALIAS).append(".id, ")
                .append(ALIAS).append(".content, ")
                .append(ALIAS).append(".metadata, ")
                .append('(').append(hits).append(") AS hit_count")
                .append(" FROM ").append(table).append(' ').append(ALIAS)
                .append(" WHERE (").append(matches).append(')');

        // 分类过滤：与向量那一路同一份判定，只是翻译成了 SQL 方言
        filter.toSqlPredicate(ALIAS).ifPresent(predicate -> sql.append(" AND ").append(predicate));

        // 排序三级，缺一不可：命中词越多越相关；同样命中的短切片更聚焦；
        // id 兜底保证**完全确定性**——RRF 读的是名次，名次不可复现的话测试会 flaky
        sql.append(" ORDER BY hit_count DESC, length(").append(ALIAS).append(".content) ASC, ")
                .append(ALIAS).append(".id ASC")
                .append(" LIMIT ?");
        return sql.toString();
    }

    /**
     * 一个检索词在 SQL 里的样子：正常情况下是 {@code ?}；开了内联则是字面量。
     *
     * <p>内联是个逃生门——参数化的 {@code LIKE ?} 有可能让规划器估错选择率而不走
     * GIN 索引。让它成为计划期常量是唯一的解法。
     */
    private String likeOperand(String term) {
        if (!pgVectorProperties.getKeyword().isInlinePatterns()) {
            return "?";
        }
        if (!SAFE_INLINE_TERM.matcher(term).matches()) {
            // 走到这里说明提取器的归一化规则被放宽了，而内联的前提正是
            // 「检索词里只可能有汉字和字母数字」。宁可当场炸，也不要往 SQL 里拼未知内容
            throw new IllegalStateException(
                    "关键词内联模式下检索词只允许汉字、字母、数字，当前是：" + term);
        }
        return "'%" + term + "%'";
    }

    /**
     * 组参数。
     *
     * <p><b>顺序必须和 SQL 里 {@code ?} 出现的顺序完全一致</b>：{@code SELECT} 里的 N 个
     * 在前（它在 WHERE 之前），然后 {@code WHERE} 里的 N 个，然后分类值，最后 limit。
     * 两个列表内容一样，写反了不会报错，只会让 {@code hit_count} 算成别的值——
     * 排序悄悄变了，而检索结果看起来仍然正常。
     */
    Object[] buildArgs(List<String> terms, ClassificationFilter filter, int limit) {
        List<Object> args = new ArrayList<>();
        if (!pgVectorProperties.getKeyword().isInlinePatterns()) {
            for (String term : terms) {
                args.add(PgVectorSql.likePattern(term));
            }
            for (String term : terms) {
                args.add(PgVectorSql.likePattern(term));
            }
        }
        filter.toSqlArgument().ifPresent(args::add);
        args.add(limit);
        return args.toArray();
    }

    /** 执行查询。抽成方法是为了给单元测试留一个不用 Mockito 摆弄 varargs 的接缝。 */
    List<Document> queryRows(String sql, Object[] args) {
        return pgJdbcTemplate.query(sql, this::mapRow, args);
    }

    /** 把一行映射成 {@code Document}。列名与 {@code PgVectorIndexService} 里那些查询保持一致。 */
    Document mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        String content = resultSet.getString("content");
        Map<String, Object> metadata = parseMetadata(resultSet.getString("metadata"));
        metadata.put(META_HIT_COUNT, resultSet.getInt("hit_count"));
        return new Document(resultSet.getString("id"), content == null ? "" : content, metadata);
    }

    /**
     * 把 {@code metadata} 列的 JSON 解析成 Map。
     *
     * <p>解析失败退回空 Map 而不是抛：正文还能用，这条切片仍然有检索价值；
     * 而分类等信息在 SQL 里已经作为过滤条件用过了，退成空的不会让结果出错。
     */
    private static Map<String, Object> parseMetadata(String json) {
        if (!StringUtils.hasText(json)) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        }
        catch (JsonProcessingException exception) {
            log.warn("[pgvector] 关键词检索读到一条无法解析的元数据，已忽略：{}", exception.getMessage());
            return new HashMap<>();
        }
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** {@code string_agg} 一行都没有时返回 null，这里给个能直接读的占位。 */
    private static String orNone(Object value) {
        return value == null ? "（无）" : String.valueOf(value);
    }
}
