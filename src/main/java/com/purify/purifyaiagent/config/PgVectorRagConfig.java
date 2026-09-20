package com.purify.purifyaiagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.model.RerankModel;
import com.purify.purifyaiagent.rag.KnowledgeBaseAdvisor;
import com.purify.purifyaiagent.rag.KnowledgeRouter;
import com.purify.purifyaiagent.rag.RagPrompts;
import com.purify.purifyaiagent.rag.pgvector.DashScopeEmbeddingBatchingStrategy;
import com.purify.purifyaiagent.rag.pgvector.PgVectorDocumentRetriever;
import com.purify.purifyaiagent.rag.pgvector.PgVectorIndexService;
import com.purify.purifyaiagent.rag.pgvector.PgVectorKnowledgeBaseAdvisor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 本地 pgvector 链路：文档、切片、向量全在本地的 PostgreSQL 里。
 *
 * <p>链路是这样串起来的，对应两张流程图：
 * <pre>
 *   建索引：上传的 txt/md
 *     └── PgVectorIndexService   预处理 → 切片 → 分批 add
 *           └── PgVectorStore    （内部调 DashScope Embedding 接口）→ 写 pgvector
 *
 *   检索：  用户问题
 *     └── PgVectorKnowledgeBaseAdvisor   路由 → 检索 → 拼进用户消息
 *           └── PgVectorDocumentRetriever   分类过滤 + 向量粗排 + Rank 模型精排
 *                 └── PgVectorStore / DashScopeRerankModel
 * </pre>
 *
 * <p><b>装配条件</b>：{@code purify.rag.enabled} 且 {@code purify.rag.store} 是
 * {@link RagStore#PGVECTOR}。整个类一起装配、一起不装配——本类产出的向量库、检索器、
 * Advisor 和上传服务都属于这一条链路，没有需要单独取舍的部分。
 *
 * <p>这里<b>没有</b> {@code matchIfMissing}，不能加：不写 store 时默认走百炼，
 * 不该把本地向量库也一起拉起来。
 *
 * <p>注意 {@code KnowledgeBaseController} 也带着同样的类级条件，两边的口径必须一致，
 * 否则会出现「Bean 在但上传接口 404」这种自相矛盾的状态。两边都引用 {@link RagStore}
 * 里的常量，就是为了让这个一致性由编译器保证，而不是靠人记着。
 *
 * <h2>为什么 PostgreSQL 的连接池不做成 Bean</h2>
 *
 * <p>这是本类最要紧的一处设计，改之前请先读完：
 *
 * <p>Boot 的 {@code DataSourceAutoConfiguration} 带 {@code @ConditionalOnMissingBean(DataSource.class)}——
 * 只要容器里出现<b>任何</b>一个 {@code DataSource} Bean，给对话记忆用的那个 MySQL 数据源
 * 就会整个退避。更糟的是 {@code JdbcTemplateConfiguration} 上是
 * {@code @ConditionalOnMissingBean(JdbcOperations.class)}：我们若再声明一个给 PG 用的
 * {@code JdbcTemplate}，Boot 的 {@code jdbcTemplate} 也会跟着退避，于是
 * {@code ChatMemoryConfig} 里裸注入的 {@code JdbcTemplate}（PG）与 {@code DataSource}（MySQL）
 * 会被拼在一起——<b>对话记忆静默写到 PG 去</b>。
 *
 * <p>要把多数据源做对，得手写两个 DataSource + 两个 JdbcTemplate、给其中一个标
 * {@code @Primary}、再给 {@code ChatMemoryConfig} 加 {@code @Qualifier}，
 * 等于把 Boot 的两条数据源自动配置全部接管。而 PG 这边只有一个消费者，
 * 换来的收益是零。
 *
 * <p>所以这里的做法是：{@link PgVectorJdbc} 把连接池和 JdbcTemplate
 * <b>包在一个不是 DataSource、也不是 JdbcTemplate 的类型里</b>，
 * 需要它的 Bean 通过这个类型拿。容器的「数据源类型空间」完全不受影响。
 *
 * <p><b>铁律</b>：本链路的所有协作者一律在 {@code @Bean} 方法里显式 new 出来，
 * 绝不把 {@code DataSource} / {@code JdbcTemplate} 作为参数按类型注入——
 * 一旦那样写，拿到的一定是 MySQL 那个。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "purify.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = RagStore.PREFIX, name = "store", havingValue = RagStore.PGVECTOR)
public class PgVectorRagConfig {

    /**
     * PG 的连接池与 JdbcTemplate。
     *
     * <p>刻意<b>不</b>实现 {@code DataSource}、也<b>不</b>做成 {@code JdbcTemplate} Bean：
     * 它的声明类型就是这个类本身，Boot 在判断「容器里有没有 DataSource」时看不到它，
     * 于是对话记忆的 MySQL 自动配置分毫未动。
     *
     * <p>实现 {@link DisposableBean} 是必须的：连接池不是 Spring 管的 Bean，
     * 不显式关就会每次重启漏一个池子。Spring 对 {@code @Bean} 方法返回的、
     * 实现了 {@code DisposableBean} 的对象会在容器关闭时调用 {@code destroy()}。
     */
    record PgVectorJdbc(HikariDataSource dataSource, JdbcTemplate jdbcTemplate) implements DisposableBean {

        @Override
        public void destroy() {
            dataSource.close();
        }
    }

    @Bean
    PgVectorJdbc pgVectorJdbc(PgVectorProperties pgVectorProperties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(pgVectorProperties.getJdbcUrl());
        config.setUsername(pgVectorProperties.getUsername());
        config.setPassword(pgVectorProperties.getPassword());
        config.setMaximumPoolSize(pgVectorProperties.getPoolSize());
        // < 0 表示建池时不预先探活：数据库暂时连不上也照常启动，
        // 失败推迟到第一次真正检索/上传时。默认值 1 会在建池时就取一条连接，
        // 拿不到直接让应用起不来
        config.setInitializationFailTimeout(-1);
        config.setPoolName("pgvector-pool");

        HikariDataSource dataSource = new HikariDataSource(config);
        log.info("[pgvector] 连接池已创建：{}（最大 {} 条），jdbc-url={}",
                config.getPoolName(), config.getMaximumPoolSize(), pgVectorProperties.getJdbcUrl());

        return new PgVectorJdbc(dataSource, new JdbcTemplate(dataSource));
    }

    /**
     * 向量化模型。
     *
     * <p><b>返回类型必须写成具体类 {@link DashScopeEmbeddingModel}</b>，不能写
     * {@code EmbeddingModel} 接口。因为 spring-ai-alibaba 的自动配置里已经有一个
     * {@code @Bean @ConditionalOnMissingBean @Primary DashScopeEmbeddingModel}——
     * 它的 {@code @ConditionalOnMissingBean} 比对的是 {@code @Bean} 方法的声明返回类型，
     * 我们写成接口的话条件不匹配、它照样创建，并且凭 {@code @Primary} 在按类型注入时
     * <b>静默胜出</b>：模型名是它默认的 {@code text-embedding-v1}（1536 维），
     * 而不是我们配的 v4（1024 维），直到写第一条向量才报维度不匹配。
     *
     * <p>这里额外加 {@code @Primary} 是双保险：万一上游把那个自动配置的
     * {@code @ConditionalOnMissingBean} 改坏（那个类的条件本身已经有一个
     * {@code spring.ai.model.audio.speech} 的复制粘贴错误），至少还是我们赢。
     */
    @Bean
    @Primary
    public DashScopeEmbeddingModel ragEmbeddingModel(DashScopeApi ragDashScopeApi,
                                                     PgVectorProperties pgVectorProperties) {
        // 维度配错的代价是「启动时不报错、写第一条数据时才炸」，所以在装配期先拦一道
        pgVectorProperties.verifyDimensions();

        DashScopeEmbeddingModel model = new DashScopeEmbeddingModel(ragDashScopeApi,
                // 放库和检索走的都是「纯文本」路径（EmbeddingModel 的默认实现用的是
                // Document::getText），这个参数只影响有人直接调 embed(Document) 的场景。
                // 用 NONE 而不是 EMBED：EMBED 会把 source/title/chunk_index 这些
                // 每片都差不多的元数据一起编进向量，等于往语义信号里掺噪声
                MetadataMode.NONE,
                DashScopeEmbeddingOptions.builder()
                        .withModel(pgVectorProperties.getEmbeddingModel())
                        .withTextType(pgVectorProperties.getEmbeddingTextType())
                        .build());

        log.info("[pgvector] 向量化模型={} 维度={} text-type={}",
                pgVectorProperties.getEmbeddingModel(),
                pgVectorProperties.getDimensions(),
                pgVectorProperties.getEmbeddingTextType());
        return model;
    }

    /**
     * 重排模型（流程图里的「Rank 模型」）。
     *
     * <p>和 Embedding 一样，返回类型写具体类才能让自动配置里那个
     * {@code @ConditionalOnMissingBean} 的 {@code DashScopeRerankModel} 退避。
     *
     * <p>{@code topN} 特意设成粗排条数而不是最终条数：重排接口的 topN 是「返回几条」，
     * 如果一上来只让它返回 5 条，那 5 条里只要有 3 条没达到
     * {@code rerank-min-score}，结果就只剩 2 条——即使排第 6 的其实达标。
     * 让它把候选全还回来，过滤和截断都由我们自己按阈值做。
     */
    @Bean
    @ConditionalOnProperty(prefix = "purify.rag", name = "enable-reranking", havingValue = "true",
            matchIfMissing = true)
    public DashScopeRerankModel ragRerankModel(DashScopeApi ragDashScopeApi,
                                               PgVectorProperties pgVectorProperties) {
        DashScopeRerankModel model = new DashScopeRerankModel(ragDashScopeApi,
                DashScopeRerankOptions.builder()
                        .withModel(pgVectorProperties.getRerankModelName())
                        .withTopN(pgVectorProperties.getCoarseTopK())
                        // 不需要接口把原文回传：重排结果里的 Document 是按序号
                        // 从我们传进去的候选里回查的，元数据不会丢
                        .withReturnDocuments(false)
                        .build());
        log.info("[pgvector] 重排模型={}", pgVectorProperties.getRerankModelName());
        return model;
    }

    /**
     * 向量库本体。
     *
     * <p>分批策略必须换掉：默认的 {@code TokenCountBatchingStrategy} 只按 token 切，
     * 一个批能塞进几百条短切片，而 DashScope 的 Embedding 接口只允许 10 条。
     * 详见 {@link DashScopeEmbeddingBatchingStrategy}。
     *
     * <p>这里没有配 {@code removeExistingVectorStoreTable}——建表只做
     * {@code CREATE TABLE IF NOT EXISTS}，绝不会有「重启即清库」这种事。
     */
    @Bean
    public VectorStore pgVectorStore(PgVectorJdbc pgVectorJdbc,
                                     DashScopeEmbeddingModel ragEmbeddingModel,
                                     PgVectorProperties pgVectorProperties) {
        return PgVectorStore.builder(pgVectorJdbc.jdbcTemplate(), ragEmbeddingModel)
                .schemaName(pgVectorProperties.getSchemaName())
                .vectorTableName(pgVectorProperties.getTableName())
                .idType(pgVectorProperties.getIdType())
                .distanceType(pgVectorProperties.getDistanceType())
                .indexType(pgVectorProperties.getIndexType())
                .dimensions(pgVectorProperties.getDimensions())
                .initializeSchema(pgVectorProperties.isInitializeSchema())
                .vectorTableValidationsEnabled(pgVectorProperties.isValidateSchema())
                .batchingStrategy(new DashScopeEmbeddingBatchingStrategy(
                        pgVectorProperties.getMaxDocumentsPerRequest(), new TokenCountBatchingStrategy()))
                .build();
    }

    /** 检索器：分类过滤 + 向量粗排 + Rank 模型精排。 */
    @Bean
    public DocumentRetriever pgVectorDocumentRetriever(VectorStore pgVectorStore,
                                                       RagProperties ragProperties,
                                                       PgVectorProperties pgVectorProperties,
                                                       ObjectProvider<RerankModel> ragRerankModel) {
        return new PgVectorDocumentRetriever(pgVectorStore, ragProperties, pgVectorProperties,
                // 关掉 enable-reranking 时容器里没有这个 Bean，检索退化成只做向量粗排
                ragRerankModel.getIfAvailable());
    }

    /**
     * 检索 Advisor。行为与百炼那条链路的 Advisor 对齐。
     *
     * <p><b>Bean 名与百炼链路刻意一致</b>（{@code knowledgeBaseRetrievalAdvisor}）。
     * 两条链路的装配条件在类级就是互斥的（store 不可能同时等于 pgvector 和 bailian），
     * 所以容器里永远只有一个，{@code SlimApp} 按 {@link KnowledgeBaseAdvisor} 类型取
     * 也就不会撞上 {@code NoUniqueBeanDefinitionException}。
     */
    @Bean
    public KnowledgeBaseAdvisor knowledgeBaseRetrievalAdvisor(DocumentRetriever pgVectorDocumentRetriever,
                                                             RagProperties ragProperties,
                                                             KnowledgeRouter knowledgeRouter) {
        if (ragProperties.isEnableReference()) {
            // 引用标注的实现绑在百炼官方的 Advisor 父类上，本地链路没有这一步。
            // 与其让用户以为开着，不如说清楚。
            log.warn("[pgvector] purify.rag.enable-reference=true 在本地向量库链路上不生效："
                    + "引用标注由百炼官方的 Advisor 实现，本地链路没有对应实现。"
                    + "需要引用标注请把 purify.rag.store 改成 bailian");
        }

        return new PgVectorKnowledgeBaseAdvisor(pgVectorDocumentRetriever,
                RagPrompts.USER_TEXT_ADVISE,
                ragProperties.getOrder(),
                knowledgeRouter,
                ragProperties.getRouter().isEnabled());
    }

    /**
     * 索引服务：上传接口背后真正干活的那个。
     *
     * <p>在这里手工 new 出来，把 {@link PgVectorJdbc} 里的 JdbcTemplate 显式传进去——
     * 这个 JdbcTemplate 连的是 PostgreSQL。如果改成让 Spring 按类型注入
     * {@code JdbcTemplate}，拿到的一定是对话记忆用的那个 MySQL 连接。
     */
    @Bean
    public PgVectorIndexService pgVectorIndexService(VectorStore pgVectorStore,
                                                     PgVectorJdbc pgVectorJdbc,
                                                     PgVectorProperties pgVectorProperties,
                                                     RagProperties ragProperties) {
        return new PgVectorIndexService(pgVectorStore, pgVectorJdbc.jdbcTemplate(),
                pgVectorProperties, ragProperties);
    }
}
