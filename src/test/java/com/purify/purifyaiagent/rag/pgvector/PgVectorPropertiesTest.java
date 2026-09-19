package com.purify.purifyaiagent.rag.pgvector;

import com.purify.purifyaiagent.config.PgVectorProperties;
import com.purify.purifyaiagent.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地向量库配置的单元测试。
 *
 * <p><b>不启动 Spring、不连库、不调模型</b>，只把 {@code application.yml} 里的
 * {@code purify.rag.pgvector.*} 真的绑定一遍。
 *
 * <p>这个测试的价值全在「键名写错会静默保持默认值」这件事上：yml 里把
 * {@code initialize-schema} 敲成 {@code initialize-schemaa}，Spring 不会报错，
 * 应用照常启动，只是那个开关根本没生效——于是「想关掉自动建表却关不掉」或者反过来，
 * 都要等到连库那一刻才发现。下面把每个有副作用的键都断言一遍，就是为了让这种错误
 * 在跑测试时就暴露。
 */
@Slf4j
class PgVectorPropertiesTest {

    private static RagProperties ragProperties;

    private static PgVectorProperties pgVectorProperties;

    @BeforeAll
    static void loadConfigFromYml() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);

        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        ragProperties = binder.bind("purify.rag", Bindable.of(RagProperties.class)).get();
        pgVectorProperties = binder.bind("purify.rag.pgvector", Bindable.of(PgVectorProperties.class)).get();

        log.info("[PgVectorPropertiesTest] 绑定结果：store={} 模型={} 维度={}",
                ragProperties.getStore(), pgVectorProperties.getEmbeddingModel(), pgVectorProperties.getDimensions());
    }

    @Test
    @DisplayName("链路开关：默认走百炼，且能绑成枚举")
    void store_bindsToEnum() {
        // 默认值必须是 BAILIAN——不写 store 时的行为要和加这条链路之前一模一样
        assertEquals(RagProperties.Store.BAILIAN, new RagProperties().getStore(),
                "store 的字段默认值必须是百炼，否则老配置会突然改走本地向量库");
        assertEquals(RagProperties.Store.BAILIAN, ragProperties.getStore(),
                "application.yml 里 store 应当是 bailian；这条断言挂了说明开关被改了或者键名写错了");
    }

    @Test
    @DisplayName("表结构相关配置真的从 yml 绑上了")
    void tableStructure_isBound() {
        assertEquals("public", pgVectorProperties.getSchemaName());
        assertEquals("rag_knowledge_chunk", pgVectorProperties.getTableName());
        assertEquals(PgVectorStore.PgIdType.TEXT, pgVectorProperties.getIdType(),
                "id-type 必须是 text：用 uuid 会让自动建表多执行一次 CREATE EXTENSION uuid-ossp");
        assertEquals(PgVectorStore.PgDistanceType.COSINE_DISTANCE, pgVectorProperties.getDistanceType());
        assertEquals(PgVectorStore.PgIndexType.HNSW, pgVectorProperties.getIndexType());
        assertEquals(5, pgVectorProperties.getPoolSize());
    }

    @Test
    @DisplayName("两个开关：建表默认开、校验必须关")
    void schemaSwitches_matchTheOnlyWorkableCombination() {
        assertTrue(pgVectorProperties.isInitializeSchema(),
                "initialize-schema 默认应当是 true，否则首次使用必须手工执行建表脚本");
        assertFalse(pgVectorProperties.isValidateSchema(),
                "validate-schema 必须是 false：校验跑在初始化之前，而表不存在时校验直接抛异常，"
                        + "于是空库上「校验 + 自动建表」这个组合必然启动失败，根本轮不到建表");
    }

    @Test
    @DisplayName("向量化与切片配置真的从 yml 绑上了")
    void embeddingAndChunking_isBound() {
        assertEquals("text-embedding-v4", pgVectorProperties.getEmbeddingModel());
        assertEquals(1024, pgVectorProperties.getDimensions());
        assertEquals("document", pgVectorProperties.getEmbeddingTextType());
        assertEquals(10, pgVectorProperties.getMaxDocumentsPerRequest(),
                "默认的按 token 分批策略挡不住条数上限，这个值必须绑上，否则稍微大点的文档就会报 "
                        + "The input texts limit 25.");

        PgVectorProperties.Chunk chunk = pgVectorProperties.getChunk();
        assertEquals(800, chunk.getSize());
        assertEquals(350, chunk.getMinChunkSizeChars());
        assertEquals(5, chunk.getMinChunkLengthToEmbed());
        assertEquals(2000, chunk.getMaxNumChunks(),
                "max-num-chunks 超过上限的切片会被静默丢弃，必须显式配对");
        assertTrue(chunk.isKeepSeparator());
    }

    @Test
    @DisplayName("检索配置真的从 yml 绑上了")
    void retrieval_isBound() {
        assertEquals(20, pgVectorProperties.getCoarseTopK());
        assertEquals(0.0, pgVectorProperties.getSimilarityThreshold());
        assertEquals("gte-rerank-v2", pgVectorProperties.getRerankModelName());
        assertTrue(pgVectorProperties.getMaxFileSize() > 0);
    }

    @Test
    @DisplayName("维度校验：默认配置能通过")
    void verifyDimensions_acceptsDefault() {
        assertDoesNotThrow(pgVectorProperties::verifyDimensions);
    }

    @Test
    @DisplayName("维度校验：模型与维度对不上时直接抛，把运行期错误提前到启动期")
    void verifyDimensions_rejectsMismatch() {
        PgVectorProperties wrong = new PgVectorProperties();
        // v2 的真实维度是 1536，这里故意配成 1024
        wrong.setEmbeddingModel("text-embedding-v2");
        wrong.setDimensions(1024);

        IllegalStateException exception = assertThrows(IllegalStateException.class, wrong::verifyDimensions);
        assertTrue(exception.getMessage().contains("1536") && exception.getMessage().contains("1024"),
                "报错信息里要同时给出真实维度和配置值，否则还得自己去查模型文档");
    }

    @Test
    @DisplayName("维度校验：未知模型只警告不阻断，免得以后出新模型被这张表卡死")
    void verifyDimensions_toleratesUnknownModel() {
        PgVectorProperties unknown = new PgVectorProperties();
        unknown.setEmbeddingModel("text-embedding-v99");
        unknown.setDimensions(1024);

        assertDoesNotThrow(unknown::verifyDimensions);
    }
}
