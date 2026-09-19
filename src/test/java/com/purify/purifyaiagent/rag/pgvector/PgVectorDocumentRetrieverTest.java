package com.purify.purifyaiagent.rag.pgvector;

import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.purify.purifyaiagent.config.PgVectorProperties;
import com.purify.purifyaiagent.config.RagProperties;
import com.purify.purifyaiagent.rag.KnowledgeRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地检索器的单元测试。不连库、不调模型：{@link VectorStore} 和 {@link RerankModel}
 * 都是几行的假实现。
 *
 * <p>这里盯的是三件在真环境里很难发现问题的事：
 * <ul>
 *   <li><b>过滤条件什么时候带、什么时候不带</b>——带错了的表现是「知识库突然查不到东西」，
 *       而带错了不会报任何错；</li>
 *   <li><b>过滤用的字段名来自配置</b>——硬编码 classification 的话，用户改了
 *       {@code filter-key} 就会让写入端和检索端对不上；</li>
 *   <li><b>精排的过滤、排序、截断顺序</b>——先截断再过滤会白白丢掉本该入选的切片。</li>
 * </ul>
 */
class PgVectorDocumentRetrieverTest {

    private static final String FILTER_KEY = "classification";

    /** 记录最后一次检索请求的假向量库。 */
    private static final class RecordingVectorStore implements VectorStore {

        private final List<Document> result;

        private SearchRequest lastRequest;

        private int searchCount;

        RecordingVectorStore(List<Document> result) {
            this.result = result;
        }

        @Override
        public void add(List<Document> documents) {
            throw new UnsupportedOperationException("检索器不该调用 add");
        }

        @Override
        public void delete(List<String> idList) {
            throw new UnsupportedOperationException("检索器不该调用 delete");
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            throw new UnsupportedOperationException("检索器不该调用 delete");
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            this.lastRequest = request;
            this.searchCount++;
            return result;
        }
    }

    private static List<Document> documents(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> Document.builder()
                        .text("第 %d 条切片".formatted(index))
                        .metadata(Map.of("source", "测试文档.md", "title", "第 " + index + " 节"))
                        .build())
                .toList();
    }

    /** 按传入的分数顺序给候选打分、原样回填文档的假重排模型。 */
    private static RerankModel rerankModel(double[] scores, AtomicInteger callCount) {
        return request -> {
            callCount.incrementAndGet();
            List<Document> candidates = request.getInstructions();
            List<DocumentWithScore> results = new ArrayList<>();
            for (int i = 0; i < candidates.size() && i < scores.length; i++) {
                results.add(DocumentWithScore.builder()
                        .withScore(scores[i])
                        .withDocument(candidates.get(i))
                        .build());
            }
            return new RerankResponse(results);
        };
    }

    private static RagProperties ragProperties(boolean enableReranking, int rerankTopN, double rerankMinScore) {
        RagProperties properties = new RagProperties();
        properties.setEnableReranking(enableReranking);
        properties.setRerankTopN(rerankTopN);
        properties.setRerankMinScore((float) rerankMinScore);
        properties.getRouter().setFilterKey(FILTER_KEY);
        properties.getRouter().setCategories(List.of(
                category("食物热量"), category("运动热量"), category("药物")));
        return properties;
    }

    private static RagProperties.Category category(String value) {
        RagProperties.Category category = new RagProperties.Category();
        category.setValue(value);
        return category;
    }

    private static PgVectorProperties pgVectorProperties(int coarseTopK) {
        PgVectorProperties properties = new PgVectorProperties();
        properties.setCoarseTopK(coarseTopK);
        return properties;
    }

    private static Query query(String text, List<String> categories) {
        Query.Builder builder = Query.builder().text(text);
        return categories == null ? builder.build()
                : builder.context(Map.of(KnowledgeRouter.CATEGORIES_KEY, categories)).build();
    }

    @Test
    @DisplayName("恰好命中一个分类时带上过滤条件，字段名取自配置")
    void retrieve_withSingleCategory_appliesFilter() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(documents(3));
        PgVectorDocumentRetriever retriever = new PgVectorDocumentRetriever(
                vectorStore, ragProperties(false, 5, 0.01), pgVectorProperties(20), null);

        retriever.retrieve(query("鸡胸肉多少大卡", List.of("食物热量")));

        Filter.Expression expected = new FilterExpressionBuilder().eq(FILTER_KEY, "食物热量").build();
        assertEquals(expected, vectorStore.lastRequest.getFilterExpression(),
                "过滤表达式必须等价于 classification == 食物热量；字段名要取自 router.filter-key 而不是硬编码");
    }

    @Test
    @DisplayName("命中多个分类时退化成查全库")
    void retrieve_withMultipleCategories_dropsFilter() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(documents(3));
        PgVectorDocumentRetriever retriever = new PgVectorDocumentRetriever(
                vectorStore, ragProperties(false, 5, 0.01), pgVectorProperties(20), null);

        // 「跑步后吃什么」会同时命中食物和运动两类。
        // 一次请求最多按一个字段的一个值过滤，为多分类再发一次请求不如干脆查全库
        retriever.retrieve(query("跑步后吃什么", List.of("食物热量", "运动热量")));

        assertNull(vectorStore.lastRequest.getFilterExpression(), "多分类时不该带过滤条件");
    }

    @Test
    @DisplayName("没命中分类时也查全库")
    void retrieve_withNoCategory_dropsFilter() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(documents(3));
        PgVectorDocumentRetriever retriever = new PgVectorDocumentRetriever(
                vectorStore, ragProperties(false, 5, 0.01), pgVectorProperties(20), null);

        retriever.retrieve(query("随便问问", null));
        assertNull(vectorStore.lastRequest.getFilterExpression());

        retriever.retrieve(query("随便问问", List.of()));
        assertNull(vectorStore.lastRequest.getFilterExpression());
    }

    @Test
    @DisplayName("分类名不在配置表里时退回全库，而不是拿一个查不到东西的条件去查")
    void retrieve_withUnknownCategory_fallsBackToAll() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(documents(3));
        PgVectorDocumentRetriever retriever = new PgVectorDocumentRetriever(
                vectorStore, ragProperties(false, 5, 0.01), pgVectorProperties(20), null);

        retriever.retrieve(query("这是什么", List.of("不存在的分类")));

        assertNull(vectorStore.lastRequest.getFilterExpression(),
                "分类名对不上时如果照常带过滤，表现会是「知识库突然什么都查不到」，比多带点噪声难查得多");
    }

    @Test
    @DisplayName("粗排参数来自 pgvector.coarse-top-k")
    void retrieve_usesConfiguredTopK() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(documents(3));
        PgVectorDocumentRetriever retriever = new PgVectorDocumentRetriever(
                vectorStore, ragProperties(false, 5, 0.01), pgVectorProperties(7), null);

        retriever.retrieve(query("鸡胸肉多少大卡", null));

        assertEquals(7, vectorStore.lastRequest.getTopK());
        assertEquals("鸡胸肉多少大卡", vectorStore.lastRequest.getQuery());
    }

    @Test
    @DisplayName("精排：先按阈值过滤、再倒序、最后截断到 top-n")
    void retrieve_reranksFiltersSortsAndTruncates() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(documents(4));
        AtomicInteger rerankCalls = new AtomicInteger();
        PgVectorDocumentRetriever retriever = new PgVectorDocumentRetriever(
                vectorStore, ragProperties(true, 2, 0.5), pgVectorProperties(20),
                // 分数与候选顺序不一致，用来验证「排序」这一步真的发生了；
                // 第 1 条（0.10）低于阈值 0.5，必须被丢掉
                rerankModel(new double[]{0.10, 0.95, 0.70, 0.20}, rerankCalls));

        List<Document> result = retriever.retrieve(query("鸡胸肉多少大卡", null));

        assertEquals(1, rerankCalls.get(), "开了重排就该只调一次");
        assertEquals(2, result.size(), "top-n=2，达到阈值的有 0.95 和 0.70 两条");
        assertEquals("第 1 条切片", result.get(0).getText(), "必须先按分数倒序再截断");
        assertEquals("第 2 条切片", result.get(1).getText());
    }

    @Test
    @DisplayName("精排返回的切片保留原始元数据")
    void retrieve_rerankKeepsMetadata() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(documents(2));
        PgVectorDocumentRetriever retriever = new PgVectorDocumentRetriever(
                vectorStore, ragProperties(true, 5, 0.01), pgVectorProperties(20),
                rerankModel(new double[]{0.9, 0.8}, new AtomicInteger()));

        List<Document> result = retriever.retrieve(query("鸡胸肉多少大卡", null));

        assertEquals("测试文档.md", result.get(0).getMetadata().get("source"),
                "重排是按序号回查原候选的，元数据不该丢——丢了提示词里的【文档名】就会渲染成 null");
    }

    @Test
    @DisplayName("关掉重排时按粗排顺序截断，不调用重排模型")
    void retrieve_withoutReranking_keepsCoarseOrder() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(documents(4));
        AtomicInteger rerankCalls = new AtomicInteger();
        PgVectorDocumentRetriever retriever = new PgVectorDocumentRetriever(
                vectorStore, ragProperties(false, 2, 0.01), pgVectorProperties(20),
                rerankModel(new double[]{0.9, 0.8, 0.7, 0.6}, rerankCalls));

        List<Document> result = retriever.retrieve(query("鸡胸肉多少大卡", null));

        assertEquals(0, rerankCalls.get(), "重排关掉时一次都不该调");
        assertEquals(2, result.size());
        assertEquals("第 0 条切片", result.get(0).getText());
    }

    @Test
    @DisplayName("没有重排模型时同样只做粗排")
    void retrieve_withoutRerankModel_keepsCoarseOrder() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(documents(3));
        PgVectorDocumentRetriever retriever = new PgVectorDocumentRetriever(
                vectorStore, ragProperties(true, 5, 0.01), pgVectorProperties(20), null);

        List<Document> result = retriever.retrieve(query("鸡胸肉多少大卡", null));

        assertEquals(3, result.size());
    }

    @Test
    @DisplayName("粗排没召回时不调重排，直接返回空")
    void retrieve_withNoCandidates_skipsRerank() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(List.of());
        AtomicInteger rerankCalls = new AtomicInteger();
        PgVectorDocumentRetriever retriever = new PgVectorDocumentRetriever(
                vectorStore, ragProperties(true, 5, 0.01), pgVectorProperties(20),
                rerankModel(new double[]{0.9}, rerankCalls));

        List<Document> result = retriever.retrieve(query("鸡胸肉多少大卡", null));

        assertTrue(result.isEmpty());
        assertEquals(0, rerankCalls.get(), "候选为空就别浪费一次重排调用");
    }

    @Test
    @DisplayName("阈值把候选全滤掉时返回空，而不是硬凑几条")
    void retrieve_whenNothingPassesThreshold_returnsEmpty() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(documents(3));
        PgVectorDocumentRetriever retriever = new PgVectorDocumentRetriever(
                vectorStore, ragProperties(true, 5, 0.99), pgVectorProperties(20),
                rerankModel(new double[]{0.1, 0.2, 0.3}, new AtomicInteger()));

        assertTrue(retriever.retrieve(query("鸡胸肉多少大卡", null)).isEmpty());
    }

    @Test
    @DisplayName("只有一个候选时不调重排，直接返回")
    void retrieve_withSingleCandidate_skipsRerank() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(documents(1));
        AtomicInteger rerankCalls = new AtomicInteger();
        PgVectorDocumentRetriever retriever = new PgVectorDocumentRetriever(
                vectorStore, ragProperties(true, 5, 0.01), pgVectorProperties(20),
                rerankModel(new double[]{0.9}, rerankCalls));

        List<Document> result = retriever.retrieve(query("鸡胸肉多少大卡", null));

        assertEquals(1, result.size());
        assertEquals(0, rerankCalls.get(), "只有一条候选没什么可排的，别浪费一次模型调用");
        assertNotNull(result.get(0));
    }
}
