package com.purify.purifyaiagent.rag.pgvector;

import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.purify.purifyaiagent.config.PgVectorProperties;
import com.purify.purifyaiagent.config.RagProperties;
import com.purify.purifyaiagent.rag.KnowledgeRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;

import java.util.Comparator;
import java.util.List;

/**
 * 本地 pgvector 检索器 —— 流程图右半边「文档过滤和检索」那一段。
 *
 * <p>一次 {@link #retrieve} 做两件事，对应图里的两段箭头：
 * <ol>
 *   <li><b>粗排</b>：把问题向量化（{@code VectorStore} 内部完成），在 pgvector 里做
 *       相似度搜索，带上分类过滤条件，捞出 {@code coarse-top-k} 条候选；</li>
 *   <li><b>精排</b>：把候选交给 DashScope 的 Rank 模型打分，按分数过滤、倒序、
 *       截到 {@code rerank-top-n} 条。</li>
 * </ol>
 *
 * <p><b>分类过滤的语义与百炼那条链路保持一致</b>（见 {@code RoutingDocumentRetriever}）：
 * 恰好命中一个分类才带过滤条件，命中多个或一个都没命中时不带过滤查全库。
 * 理由是宁可多带点噪声让重排去压，也不要因为分类判定粗糙而漏掉正确答案。
 *
 * <p>本类是单例、可能被并发调用，所以「这次该按哪个分类过滤」来自
 * {@link Query#context()} 而不是本类的字段。
 */
@Slf4j
public class PgVectorDocumentRetriever implements DocumentRetriever {

    private final VectorStore vectorStore;

    /** 两条链路共用的参数：重排开关、阈值、条数，以及分类字段名。 */
    private final RagProperties ragProperties;

    private final PgVectorProperties pgVectorProperties;

    /**
     * 重排模型。可以为 null（重排被关掉时装配的就是 null），
     * 这时检索退化成「只做向量粗排」。
     *
     * <p>依赖的是 {@link RerankModel} 接口而不是具体的 {@code DashScopeRerankModel}，
     * 和这里依赖 {@code VectorStore} 而不是 {@code PgVectorStore} 是同一个理由：
     * 换一个重排服务只需要换装配，而且单元测试可以塞一个几行的假实现进来，
     * 不必真的去构造一个 DashScope 客户端。
     */
    @Nullable
    private final RerankModel rerankModel;

    public PgVectorDocumentRetriever(VectorStore vectorStore,
                                     RagProperties ragProperties,
                                     PgVectorProperties pgVectorProperties,
                                     @Nullable RerankModel rerankModel) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.pgVectorProperties = pgVectorProperties;
        this.rerankModel = rerankModel;
    }

    @Override
    public List<Document> retrieve(Query query) {
        SearchRequest.Builder searchRequest = SearchRequest.builder()
                .query(query.text())
                .topK(pgVectorProperties.getCoarseTopK())
                // 默认 0.0 = 粗排不裁，把「像不像」的判断交给重排模型，
                // 因为余弦相似度的绝对值在不同问题之间并不可比
                .similarityThreshold(pgVectorProperties.getSimilarityThreshold());

        Filter.Expression filter = buildFilter(query);
        if (filter != null) {
            searchRequest.filterExpression(filter);
        }

        List<Document> candidates = vectorStore.similaritySearch(searchRequest.build());
        if (CollectionUtils.isEmpty(candidates)) {
            log.debug("[pgvector] 粗排没召回任何切片：{}", query.text());
            return List.of();
        }

        List<Document> reranked = rerank(query.text(), candidates);
        log.debug("[pgvector] 粗排 {} 条 → 精排 {} 条：{}", candidates.size(), reranked.size(), query.text());
        return reranked;
    }

    /**
     * 按路由结果构造分类过滤条件。
     *
     * @return 恰好命中一个已知分类时返回过滤表达式；其余情况返回 {@code null} 表示查全库
     */
    @Nullable
    private Filter.Expression buildFilter(Query query) {
        Object raw = query.context().get(KnowledgeRouter.CATEGORIES_KEY);
        if (!(raw instanceof List<?> categories) || categories.size() != 1) {
            return null;
        }

        String category = String.valueOf(categories.get(0));

        // 分类名来自路由的关键词表，而过滤用的字段名与取值必须和写入端一致。
        // 对不上时直接查全库，而不是拿一个查不到东西的条件去查——
        // 否则表现是「知识库突然什么都检索不到」，比多带点无关切片难查得多
        boolean known = ragProperties.getRouter().getCategories().stream()
                .anyMatch(item -> category.equals(item.getValue()));
        if (!known) {
            log.warn("[pgvector] 配置里没有分类「{}」，退回全库检索", category);
            return null;
        }

        // 字段名取自配置而不是硬编码 classification：用户可以改 filter-key，
        // 但如果只改一边（写入端写的是改后的、检索端还在用硬编码的），
        // 过滤就会永远查不到任何东西且不报错
        String filterKey = ragProperties.getRouter().getFilterKey();
        log.debug("[pgvector] 只查分类「{}」（字段 {}）", category, filterKey);
        return new FilterExpressionBuilder().eq(filterKey, category).build();
    }

    /**
     * 精排：交给 DashScope 的 Rank 模型重新打分。
     *
     * <p>跳过精排的三种情况——重排被关掉、没有重排模型、候选只有一条（没什么可排的）——
     * 一律按粗排顺序截断，保证这个方法的返回条数语义恒定。
     */
    private List<Document> rerank(String question, List<Document> candidates) {
        int topN = ragProperties.getRerankTopN();

        if (rerankModel == null || !ragProperties.isEnableReranking() || candidates.size() <= 1) {
            return candidates.stream().limit(topN).toList();
        }

        RerankResponse response = rerankModel.call(new RerankRequest(question, candidates));
        if (response == null || CollectionUtils.isEmpty(response.getResults())) {
            // 重排服务抖动不该让整条检索失败：退回粗排结果，至少还有东西可用
            log.warn("[pgvector] 重排没有返回结果，退回粗排顺序");
            return candidates.stream().limit(topN).toList();
        }

        List<Document> reranked = response.getResults().stream()
                .filter(result -> result != null
                        && result.getScore() != null
                        && result.getScore() >= ragProperties.getRerankMinScore())
                .sorted(Comparator.comparingDouble(DocumentWithScore::getScore).reversed())
                .limit(topN)
                // 重排返回的是原始 Document 对象（按序号回查输入列表），metadata 不丢
                .map(DocumentWithScore::getOutput)
                .toList();

        if (reranked.isEmpty()) {
            log.debug("[pgvector] 重排后没有切片达到阈值 {}，按空结果处理", ragProperties.getRerankMinScore());
        }
        return reranked;
    }
}
