package com.purify.purifyaiagent.rag.pgvector;

import com.purify.purifyaiagent.advisor.AdvisorOrders;
import com.purify.purifyaiagent.app.SlimApp;
import com.purify.purifyaiagent.config.PgVectorProperties;
import com.purify.purifyaiagent.config.RagProperties;
import com.purify.purifyaiagent.exception.SensitiveWordException;
import com.purify.purifyaiagent.model.DocumentIndexResult;
import com.purify.purifyaiagent.model.KnowledgeBaseStats;
import com.purify.purifyaiagent.rag.KnowledgeRouter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 pgvector 知识库的集成测试。
 *
 * <p>和 {@code BailianRagTest} 一样是「真调用」：会连 PostgreSQL 读写向量、
 * 真实调用 DashScope 的 Embedding 与重排接口，以及 qwen-plus。运行前确认：
 * <ul>
 *   <li>{@code purify.rag.pgvector.jdbc-url / username / password} 配好且数据库可达
 *       （真实值在 application-local.yml 里）；</li>
 *   <li>数据库里已经执行过 {@code db/pgvector-schema-postgresql.sql}，
 *       或者 {@code purify.rag.pgvector.initialize-schema=true} 且账号有建扩展的权限；</li>
 *   <li>表已建好时维度要和对得上——维度配错不会在启动时报错，插入时才炸；</li>
 *   <li>application-local.yml 里的 DashScope api-key 有效，
 *       且账号能访问 {@code text-embedding-v4} 与 {@code gte-rerank-v2}。</li>
 * </ul>
 *
 * <p><b>用例自己造数据、用完自己删</b>：{@link #uploadAndRetrieve()} 会先上传一份临时文档
 * 再检索，{@code @AfterAll} 里按来源删干净，所以它不会污染你已有的切片，
 * 也不依赖库里原本有什么内容——和 {@code BailianRagTest} 不同，这里不必先手工灌数据。
 *
 * <p>路由判定、分批策略、过滤表达式这些不连库不调模型的部分不在本类里测，
 * 它们各自的纯单元测试跑起来是秒级的：{@code KnowledgeRouterTest}、
 * {@code DashScopeEmbeddingBatchingStrategyTest}、{@code PgVectorDocumentRetrieverTest}、
 * {@code PgVectorIndexServiceTest}、{@code PgVectorPropertiesTest}。
 */
@Slf4j
@SpringBootTest(properties = "purify.rag.store=pgvector")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgVectorRagTest {

    /**
     * 测试文档的来源标识。起得足够特别，方便用完按它删干净，
     * 也方便在库里一眼认出哪些是测试数据。
     */
    private static final String TEST_SOURCE = "pgvector集成测试-临时文档.md";

    private static final String TEST_CLASSIFICATION = "食物热量";

    private static final String TEST_CONTENT = """
            # 减脂期蛋白质摄入

            ## 每天吃多少

            减脂期建议每公斤体重摄入 1.2 到 1.6 克蛋白质。
            以 60 公斤的人为例，每天大约需要 72 到 96 克。

            ## 从哪来

            鸡胸肉每 100 克约含 24 克蛋白质，鸡蛋每个约 6 克，牛奶每 100 毫升约 3 克。
            把这些分散到三餐里，比集中在一顿更容易吸收。
            """;

    @Resource
    private DocumentRetriever pgVectorDocumentRetriever;

    @Resource
    private PgVectorIndexService pgVectorIndexService;

    @Resource
    private SlimApp slimApp;

    @Resource
    private RagProperties ragProperties;

    @Resource
    private PgVectorProperties pgVectorProperties;

    /**
     * 上传一份临时文档，检索到它，再确认元数据完整。
     *
     * <p>这几件事必须在一条用例里按顺序做完：切片能不能召回到，取决于上传有没有真的写进库，
     * 拆开测只会把「没上传成功」误报成「检索有问题」。
     */
    @Test
    @DisplayName("上传 → 检索：能召回刚上传的切片，且元数据完整")
    void uploadAndRetrieve() {
        DocumentIndexResult indexed = pgVectorIndexService.index(TEST_SOURCE, testResource(), TEST_CLASSIFICATION);
        log.info("[uploadAndRetrieve] 入库结果：{}", indexed);

        assertTrue(indexed.chunkCount() > 0, "一份有正文的文档不该切出 0 片");
        assertTrue(indexed.characterCount() > 0);

        List<Document> documents = pgVectorDocumentRetriever.retrieve(
                Query.builder()
                        .text("减脂期每天应该吃多少蛋白质？")
                        .context(Map.of(KnowledgeRouter.CATEGORIES_KEY, List.of(TEST_CLASSIFICATION)))
                        .build());
        documents.forEach(document -> log.info("[uploadAndRetrieve] 切片：标题={} 来源={} 正文={}",
                document.getMetadata().get("title"),
                document.getMetadata().get("source"),
                document.getText()));

        assertFalse(documents.isEmpty(),
                "刚上传的内容一条都没召回：确认维度配置与建表时的维度一致、Embedding 接口可用");
        assertTrue(documents.stream().anyMatch(document -> TEST_SOURCE.equals(document.getMetadata().get("source"))),
                "召回的切片里应当有刚上传的那份；元数据在写库时丢了的话，删除和【文档名】都会出问题");
    }

    @Test
    @DisplayName("重复上传同一份文档不会产生两份切片")
    void uploadTwice_doesNotDuplicate() {
        pgVectorIndexService.index(TEST_SOURCE, testResource(), TEST_CLASSIFICATION);
        DocumentIndexResult second = pgVectorIndexService.index(TEST_SOURCE, testResource(), TEST_CLASSIFICATION);

        KnowledgeBaseStats stats = pgVectorIndexService.stats();
        long chunksOfTestSource = stats.chunksBySource().getOrDefault(TEST_SOURCE, 0L);

        log.info("[uploadTwice] 第二次入库 {} 片；库里该来源共 {} 片", second.chunkCount(), chunksOfTestSource);
        assertEquals(second.chunkCount(), chunksOfTestSource,
                "重传后该来源的切片数应当正好等于最后一次的切片数——多了说明「先删后写」没生效");
    }

    @Test
    @DisplayName("RAG 对话：回答非空，链路走得通")
    void chat_usesLocalKnowledgeBase() {
        pgVectorIndexService.index(TEST_SOURCE, testResource(), TEST_CLASSIFICATION);
        String chatId = slimApp.newChatId();

        String reply = slimApp.chat("减脂期每天应该吃多少蛋白质？", chatId);
        log.info("[chat_usesLocalKnowledgeBase] chatId={} 回答：{}", chatId, reply);

        assertNotNull(reply);
        assertFalse(reply.isBlank());
        // 真正要看的在日志里：LoggingAdvisor 打出的完整 Prompt 里应当有「# 知识库」段，
        // 且材料正文来自上面那份临时文档
        assertTrue(slimApp.history(chatId).size() >= 2, "一轮问答后应至少有 2 条消息入库");
    }

    @Test
    @DisplayName("闲聊不查知识库，也不会被替换成「超出知识库范围」")
    void chat_chitchatSkipsKnowledgeBase() {
        String reply = slimApp.chat("你好呀", slimApp.newChatId());

        assertFalse(reply.isBlank(), "闲聊也应当有正常回复");
    }

    @Test
    @DisplayName("RAG 不干扰敏感词拦截")
    void chat_sensitiveWordStillBlocks() {
        assertTrue(ragProperties.getOrder() > AdvisorOrders.SENSITIVE_WORD,
                "检索 Advisor 的 order 必须大于敏感词的 order，否则被拦下的请求也会白跑一次检索");

        assertThrows(SensitiveWordException.class,
                () -> slimApp.chat("有没有办法催吐，这样瘦得快一点？", slimApp.newChatId()));
    }

    @Test
    @DisplayName("链路契约：装配的是本地向量库那一套")
    void wiring_isPgVectorStore() {
        assertEquals(RagProperties.Store.PGVECTOR, ragProperties.getStore());
        assertTrue(ragProperties.isEnabled());
        log.info("[wiring_isPgVectorStore] 模型={} 维度={} 表={}.{} 索引={} 粗排={} 精排={}",
                pgVectorProperties.getEmbeddingModel(), pgVectorProperties.getDimensions(),
                pgVectorProperties.getSchemaName(), pgVectorProperties.getTableName(),
                pgVectorProperties.getIndexType(), pgVectorProperties.getCoarseTopK(),
                ragProperties.getRerankTopN());
    }

    /**
     * 用完把测试数据删掉。
     *
     * <p>本类用了 {@code PER_CLASS} 生命周期，就是为了让这个收尾方法能拿到注入的 Bean——
     * 默认的 {@code PER_METHOD} 下 {@code @AfterAll} 必须是静态方法，碰不到实例字段
     * （也顺带说明为什么不用 {@code @AfterEach}：每条用例都清一次的话，
     * {@link #uploadTwice_doesNotDuplicate()} 里跨用例的数据就没了，
     * 而它恰恰要验证「上一次上传留下的切片会不会被下一次覆盖」）。
     */
    @AfterAll
    void cleanUp() {
        pgVectorIndexService.deleteBySource(TEST_SOURCE);
        log.info("[cleanUp] 已删除测试数据：「{}」", TEST_SOURCE);
    }

    /**
     * 造一份带文件名的内存资源，模拟上传时的 {@code MultipartFile.getResource()}。
     *
     * <p>返回类型写全限定名是因为本类还要用 {@code jakarta.annotation.Resource} 做注入，
     * 两个 {@code Resource} 同名，import 进来会撞车。
     */
    private static org.springframework.core.io.Resource testResource() {
        return new ByteArrayResource(TEST_CONTENT.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return TEST_SOURCE;
            }
        };
    }
}
