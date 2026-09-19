package com.purify.purifyaiagent.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * 本地 pgvector 知识库配置，对应 application.yml 中的 {@code purify.rag.pgvector.*}。
 *
 * <p>只有 {@code purify.rag.store=pgvector} 时这组参数才真正起作用
 * （见 {@code PgVectorRagConfig}）；{@code RagProperties} 里那些两条链路共用的
 * 参数（路由、重排阈值、Advisor 顺序）不在这里重复声明。
 *
 * <p>连接信息（{@code jdbc-url} / {@code username} / {@code password}）里的真实值
 * 只放 {@code application-local.yml}——那个文件被 {@code .gitignore} 的
 * {@code *.local.yml} 统一挡住，不会进仓库。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "purify.rag.pgvector")
public class PgVectorProperties {

    /**
     * 已知的 DashScope Embedding 模型维度表。
     *
     * <p><b>为什么需要这张表</b>：{@code DashScopeEmbeddingOptions} 里虽然有个
     * {@code dimensions} 字段，但 {@code DashScopeEmbeddingApi} 拼请求时只发
     * {@code model} 和 {@code text_type}，<b>维度根本不会发到接口</b>——
     * 也就是说向量维度完全由模型名决定。而 pgvector 的建表语句里维度是写死的，
     * 配错不会在启动时报错，要到第一条数据插入时才炸
     * （{@code ERROR: expected 1024 dimensions, not 1536}）。
     * 所以这里在装配期就把它拦下来。
     */
    private static final Map<String, Integer> KNOWN_DIMENSIONS = Map.of(
            "text-embedding-v1", 1536,
            "text-embedding-v2", 1536,
            "text-embedding-v3", 1024,
            "text-embedding-v4", 1024);

    // ==================== 连接 ====================

    /** PostgreSQL 连接串，形如 {@code jdbc:postgresql://host:5432/purify_ai_agent}。 */
    private String jdbcUrl;

    private String username;

    private String password;

    /**
     * 连接池大小。知识库读写是低频操作（只有上传和检索两类请求），5 条足够，
     * 不必照抄 MySQL 那边的规模。
     */
    private int poolSize = 5;

    // ==================== 表结构 ====================

    private String schemaName = "public";

    private String tableName = "rag_knowledge_chunk";

    /**
     * 主键类型，默认 {@code text} 而不是 pgvector 上游的 {@code uuid}。
     *
     * <p>理由是权限：{@code uuid} 类型会让 {@code PgVectorStore} 的自动建表
     * 额外执行一次 {@code CREATE EXTENSION "uuid-ossp"}，而阿里云 RDS 的普通账号
     * 未必有建扩展的权限。<b>改成 text 就少一个可能没权限的扩展</b>，
     * 代价仅仅是主键从 128 位变成变长字符串——本项目的数据量下没有区别。
     *
     * <p>可选值见 {@link PgVectorStore.PgIdType}。
     */
    private PgVectorStore.PgIdType idType = PgVectorStore.PgIdType.TEXT;

    /**
     * 距离函数，默认余弦。
     *
     * <p>DashScope 的 embedding 向量是归一化的，余弦和内积在排序上等价，
     * 但余弦的取值范围更好解释（相似度 = 1 - 距离）。
     */
    private PgVectorStore.PgDistanceType distanceType = PgVectorStore.PgDistanceType.COSINE_DISTANCE;

    /**
     * 向量索引类型，默认 HNSW——这就是流程图里「建立索引」那一步。
     *
     * <p><b>HNSW 需要 pgvector &gt;= 0.5.0</b>。版本低的库执行建索引语句会报
     * {@code type "hnsw" does not exist}，这时改成 {@code IVFFLAT}（0.4.0 起就有）
     * 或 {@code NONE}（不建索引，退化成全表扫描）。
     * 用 {@code SELECT extversion FROM pg_extension WHERE extname = 'vector';} 确认版本。
     */
    private PgVectorStore.PgIndexType indexType = PgVectorStore.PgIndexType.HNSW;

    /**
     * 向量维度，默认为 {@code text-embedding-v4} 的 1024。
     *
     * <p>必须与 {@link #embeddingModel} 的真实维度一致，装配期会校验（见 {@link #verifyDimensions()}）。
     */
    private int dimensions = 1024;

    /**
     * 是否让 {@code PgVectorStore} 自己建扩展/建表/建索引。
     *
     * <p>默认开着是为了「零手工步骤」。但它会无条件执行
     * {@code CREATE EXTENSION IF NOT EXISTS vector} 和
     * {@code CREATE EXTENSION IF NOT EXISTS hstore}——阿里云 RDS 上如果账号没有
     * 建扩展的权限，这里会以 {@code permission denied to create extension "hstore"}
     * 让应用启动失败。此时把本项改成 false，手工执行
     * {@code db/pgvector-schema-postgresql.sql} 即可。
     *
     * <p>另外注意上游的 {@code vector} 列类型建表语句用的是
     * <b>{@code metadata json} 而不是 jsonb</b>，删除时的过滤条件因而走的是
     * {@code metadata::jsonb @@ '...'::jsonpath} 表达式而非索引扫描，见建表脚本里的说明。
     */
    private boolean initializeSchema = true;

    /**
     * 启动时是否校验表结构。
     *
     * <p><b>必须保持 false，除非表一定已经存在。</b>因为校验跑在初始化<b>之前</b>
     * （{@code PgVectorStore#afterPropertiesSet} 里 validate 在前、initialize 在后），
     * 而 {@code PgVectorSchemaValidator} 在表不存在时是直接抛 {@code IllegalStateException}
     * 的——于是空库上「校验 + 自动建表」这个组合必然启动失败，根本轮不到建表。
     *
     * <p>想要「表没建好就早点报错」的话，正确做法是给 {@code initialize-schema}
     * 也配 false、让建表脚本负责建表，再打开本项。
     */
    private boolean validateSchema = false;

    // ==================== 向量化 ====================

    /**
     * Embedding 模型名。
     *
     * <p>{@code text-embedding-v4} 是当前推荐；换成 v3 也可以（同为 1024 维）。
     * <b>不能换成 v1/v2</b>——那两个是 1536 维，除非把 {@link #dimensions} 一起改掉。
     * 装配期会拿模型名去查已知维度表，对不上直接抛异常。
     */
    private String embeddingModel = "text-embedding-v4";

    /**
     * 文本类型，取 {@code document}（建索引）或 {@code query}（查询）。
     *
     * <p>DashScope 建议建索引用 document、检索用 query，会得到更好的召回。但
     * {@code VectorStore} 接口的 {@code similaritySearch} 是拿配置进来的这个
     * EmbeddingModel 去算查询向量的（Spring AI 1.0.0 的 {@code SearchRequest}
     * 不支持传入预计算好的向量），同一个实例没法按调用点区分，所以只能全局配一个。
     *
     * <p>这里默认 {@code document}：建索引和检索用的是同一套模型参数，
     * 至少在两侧保持了一致。<b>属于已知的取舍</b>，若发现检索效果不理想，
     * 可以把它改成 {@code query} 再观察——代价是索引侧的向量也用 query 类型算的。
     */
    private String embeddingTextType = "document";

    /**
     * 单次 Embedding 请求最多带几条切片。
     *
     * <p><b>这个限制必须由我们自己保证</b>：Spring AI 默认的
     * {@code TokenCountBatchingStrategy} 只按 token 切（每批约 7000 token），
     * 一条几百字的切片才二十来个 token，一个批能塞进几百条。而
     * {@code DashScopeApi#embeddings} 在客户端就有
     * {@code Assert.isTrue(size <= 25)} 的硬断言，服务端对 v3/v4 的上限更是只有 10 条。
     *
     * <p>所以 {@link com.purify.purifyaiagent.rag.pgvector.DashScopeEmbeddingBatchingStrategy}
     * 会在 token 分批之外再按条数切一次，这个值就是那个条数上限。
     */
    private int maxDocumentsPerRequest = 10;

    // ==================== 切片 ====================

    /** 切片参数，对应流程图里「文档切片」那一步。 */
    private Chunk chunk = new Chunk();

    // ==================== 检索 ====================

    /**
     * 粗排召回条数（向量相似度搜索返回多少条），之后再交给重排模型精排。
     *
     * <p>不复用百炼那条链路的 {@code dense-similarity-top-k}（默认 100）：
     * 那个数是百炼检索接口的上限，而这里粗排的每一条都要塞进重排请求，
     * 重排接口对条数和总 token 都有上限，100 条的重排本身也很慢。
     */
    private int coarseTopK = 20;

    /**
     * 相似度阈值，低于它的切片在粗排阶段就被丢掉。
     *
     * <p>默认 0.0 = 完全不裁，把筛选交给重排模型（{@code rerank-min-score}）。
     * 调高可以省一点重排的开销，但调过头会一条都召不回来，而且重排也就无从补救了。
     */
    private double similarityThreshold = 0.0;

    /**
     * 重排模型名。
     *
     * <p><b>刻意不复用 {@code purify.rag.rerank-model-name}</b>：那边填的是
     * 百炼平台侧的重排模型名（{@code gte-rerank-hybrid}），这里给的是 DashScope
     * 原生重排接口的模型名，两套名字不通用，混用会直接报模型不存在。
     * 而 {@code rerank-min-score} / {@code rerank-top-n} 语义完全一致，继续复用
     * {@code purify.rag.*} 下的那两个。
     */
    private String rerankModelName = "gte-rerank-v2";

    /** 上传文件大小上限（字节）。文本文件 1MB 已经约 50 万字，够用了。 */
    private long maxFileSize = 1024 * 1024;

    /**
     * 校验 {@link #dimensions} 与 {@link #embeddingModel} 是否匹配，不匹配直接抛异常。
     *
     * <p>在装配期调用。已知模型对不上是<b>配置错误</b>，早报早好；未知模型只警告不阻断——
     * 以后 DashScope 出了 v5，不该被这张写死的表卡住启动。
     */
    public void verifyDimensions() {
        if (!StringUtils.hasText(embeddingModel) || dimensions <= 0) {
            throw new IllegalStateException(
                    "purify.rag.pgvector 的 embedding-model 和 dimensions 都必须配置，当前是 embedding-model="
                            + embeddingModel + " dimensions=" + dimensions);
        }

        Integer known = KNOWN_DIMENSIONS.get(embeddingModel.trim().toLowerCase(Locale.ROOT));
        if (known == null) {
            log.warn("[pgvector] 未知的 Embedding 模型「{}」，无法校验 dimensions={} 是否正确，请自行确认该模型的向量维度",
                    embeddingModel, dimensions);
            return;
        }
        if (known != dimensions) {
            throw new IllegalStateException(
                    "维度配置不一致：模型 %s 的真实维度是 %d，但 purify.rag.pgvector.dimensions 配的是 %d。"
                            .formatted(embeddingModel, known, dimensions)
                            + "这个错不会在启动时报出来，而是到写入向量时才抛 "
                            + "「expected %d dimensions, not %d」；若表已经按旧维度建好，还得连表一起重建。"
                                    .formatted(dimensions, known));
        }
    }

    /** 切片参数。 */
    @Data
    public static class Chunk {

        /** 每个切片的目标 token 数。 */
        private int size = 800;

        /** 小于这个字符数的片段会被并入相邻切片，避免切出一堆碎片。 */
        private int minChunkSizeChars = 350;

        /** 短于这个长度的片段直接丢弃（通常是空白或单个标点）。 */
        private int minChunkLengthToEmbed = 5;

        /**
         * 单份文档最多产出多少个切片，<b>超出的部分会被静默丢弃、不报错</b>。
         *
         * <p>所以这个值要显式配出来，让「文档太大」这件事在切片阶段就被看见；
         * 默认 10000 是 Spring AI 的默认值，对 1MB 上限的文本文档而言过宽了。
         */
        private int maxNumChunks = 2000;

        /** 切片时保留分隔符，中文长句不会在标点处被切掉后语义断裂。 */
        private boolean keepSeparator = true;
    }
}
