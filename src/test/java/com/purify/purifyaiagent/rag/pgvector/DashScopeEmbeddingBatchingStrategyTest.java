package com.purify.purifyaiagent.rag.pgvector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分批策略的单元测试。纯计算，不连库、不调模型。
 *
 * <p>这里最要紧的一条是「所有批合起来的条数正好等于输入条数」：
 * {@code EmbeddingModel} 的默认实现里有一句
 * {@code Assert.isTrue(embeddings.size() == documents.size())}，
 * 分批只要漏掉一条，整个上传就会以一个和「分批」看起来毫无关系的断言失败告终。
 */
class DashScopeEmbeddingBatchingStrategyTest {

    /**
     * 我们自己配的单次条数上限。取 10 是因为 DashScope 服务端对 text-embedding-v3/v4
     * 的限制就是 10 条，而本项目的默认模型正是 v4。
     */
    private static final int SERVICE_SIDE_LIMIT = 10;

    /**
     * DashScope 客户端里 {@code Assert.isTrue(texts.size() <= 25)} 那条硬断言的上限。
     * 它是「配了这个值必然抛异常」的红线，配置项不能越过它。
     */
    private static final int CLIENT_SIDE_LIMIT = 25;

    private static List<Document> documents(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> new Document("第 %d 条测试文本，用于验证分批".formatted(index)))
                .toList();
    }

    private static DashScopeEmbeddingBatchingStrategy strategy(int maxDocumentsPerRequest) {
        return new DashScopeEmbeddingBatchingStrategy(maxDocumentsPerRequest, new TokenCountBatchingStrategy());
    }

    @Test
    @DisplayName("超过条数上限时按上限切开，一条不多一条不少")
    void batch_splitsByDocumentCount() {
        List<List<Document>> batches = strategy(SERVICE_SIDE_LIMIT).batch(documents(25));

        assertEquals(3, batches.size(), "25 条按每批 10 条应当切成 3 批");
        assertEquals(List.of(10, 10, 5), batches.stream().map(List::size).toList());
        assertEquals(25, batches.stream().mapToInt(List::size).sum(),
                "所有批的条数合计必须等于输入条数——少一条就会在 EmbeddingModel 的断言上炸");
    }

    @Test
    @DisplayName("默认策略挡不住的场景：短文本会被按条数牢牢压住")
    void batch_neverExceedsDocumentLimit() {
        // 25 条短文本按 token 算远远不到一个批，只靠 TokenCountBatchingStrategy 会全部挤在一起，
        // 正好踩中 DashScope 客户端 size<=25 的硬断言（服务端对 v3/v4 更严，只允许 10 条）
        List<List<Document>> batches = strategy(SERVICE_SIDE_LIMIT).batch(documents(25));

        assertTrue(batches.stream().allMatch(batch -> batch.size() <= SERVICE_SIDE_LIMIT),
                "任何一批都不能超过配置的条数上限，否则接口直接报错");
    }

    @Test
    @DisplayName("不到上限时只切一批，不白白拆开")
    void batch_singleBatchWhenUnderLimit() {
        List<List<Document>> batches = strategy(SERVICE_SIDE_LIMIT).batch(documents(3));

        assertEquals(1, batches.size());
        assertEquals(3, batches.get(0).size());
    }

    @Test
    @DisplayName("空输入返回空结果，不抛异常")
    void batch_emptyInput() {
        assertTrue(strategy(SERVICE_SIDE_LIMIT).batch(List.of()).isEmpty());
        assertTrue(strategy(SERVICE_SIDE_LIMIT).batch(null).isEmpty());
    }

    @Test
    @DisplayName("配置越界在构造时就挡掉，而不是等接口报 The input texts limit 25.")
    void constructor_rejectsOutOfRangeLimit() {
        // 0 或负数
        assertThrows(IllegalArgumentException.class, () -> strategy(0));

        // 超过 DashScope 客户端的硬上限：这个错必须在我们这里报，
        // 因为接口那句 "The input texts limit 25." 不会告诉你是哪个配置写错了
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> strategy(CLIENT_SIDE_LIMIT + 1));
        assertTrue(exception.getMessage().contains("25"), "报错信息里要写明上限是多少");
    }

    @Test
    @DisplayName("单条文本就超过 token 上限时会抛异常——这是「切片太大」，不会静默丢内容")
    void batch_oversizedSingleDocumentFailsLoudly() {
        // TokenCountBatchingStrategy 的上限是 8191 token。这里塞一条远超它的文本。
        String huge = "长文本".repeat(20_000);
        List<Document> documents = List.of(new Document(huge));

        // 锁住这个行为：它虽然是异常，但比「悄悄丢掉这一条」好——
        // 我们的 chunk-size 是 800 token，正常切片不可能走到这里
        assertThrows(IllegalArgumentException.class, () -> strategy(SERVICE_SIDE_LIMIT).batch(documents));
    }
}
