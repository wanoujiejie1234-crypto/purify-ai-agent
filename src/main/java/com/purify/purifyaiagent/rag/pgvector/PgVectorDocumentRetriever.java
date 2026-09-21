package com.purify.purifyaiagent.rag.pgvector;

import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.purify.purifyaiagent.config.PgVectorProperties;
import com.purify.purifyaiagent.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 本地 pgvector 检索器 —— 流程图右半边「文档过滤和检索」那一段。
 *
 * <p>一次 {@link #retrieve} 走四步：
 * <ol>
 *   <li><b>向量粗排</b>：把问题向量化（{@code VectorStore} 内部完成），在 pgvector 里做
 *       相似度搜索，带上分类过滤条件，捞出 {@code coarse-top-k} 条；</li>
 *   <li><b>关键词检索</b>：按字面精确匹配再捞一路（见 {@link PgKeywordSearcher}），
 *       补向量路在专有名词上的短板；</li>
 *   <li><b>RRF 融合</b>：两路按<b>名次</b>合成一个候选集（见 {@link RrfFusion}）；</li>
 *   <li><b>精排</b>：把候选交给 DashScope 的 Rank 模型打分，按分数过滤、倒序、
 *       截到 {@code rerank-top-n} 条。</li>
 * </ol>
 *
 * <p><b>关键词那一路是可以没有的。</b>构造时传 {@code null}，或者运行时探测到
 * pg_bigm 不可用（见 {@link PgKeywordSearcher#status()}），整条链路的行为就
 * <b>逐字节等于</b>加这一路之前的样子——纯向量粗排 + 精排。
 * 这不是顺手得到的好处，而是这一路唯一安全的降级方式：它的 SQL 不调用任何
 * pg_bigm 函数，所以扩展没装时查询不会报错、只会静默全表扫，靠 catch 异常根本发现不了。
 *
 * <p><b>分类过滤的语义与百炼那条链路保持一致</b>（见 {@code RoutingDocumentRetriever}）：
 * 恰好命中一个分类才带过滤条件，命中多个或一个都没命中时不带过滤查全库。
 * 理由是宁可多带点噪声让重排去压，也不要因为分类判定粗糙而漏掉正确答案。
 * <b>判定只做一次，两条路共用</b>（见 {@link ClassificationFilter}）——
 * 两路按不同的分类去查是这里最隐蔽的错法，合起来的结果既不报错也说不清是怎么来的。
 *
 * <p>本类是单例、可能被并发调用，所以「这次该按哪个分类过滤」来自
 * {@link Query#context()} 而不是本类的字段。
 */
@Slf4j
public class PgVectorDocumentRetriever implements DocumentRetriever {

    /**
     * 重排分数写进切片元数据用的键。
     *
     * <p>只在自检接口和日志里用，不参与提示词拼装（{@code DOCUMENT_FORMATTER} 不读它）。
     * 存在的理由：调 {@code rerank-min-score} 时最想知道「被丢掉的那几条差多少分」，
     * 而分数原本在过滤那一步之后就被扔了。
     */
    public static final String META_SCORE = "rerank_score";

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

    /**
     * 关键词检索那一路。
     *
     * <p>可以是 {@code null}——没装配，或者刻意不传（纯向量模式）。那时整条链路
     * 与加这一路之前完全一致，这不是巧合而是设计要求：它是降级路径，必须零风险。
     */
    @Nullable
    private final PgKeywordSearcher keywordSearcher;

    public PgVectorDocumentRetriever(VectorStore vectorStore,
                                     RagProperties ragProperties,
                                     PgVectorProperties pgVectorProperties,
                                     @Nullable RerankModel rerankModel,
                                     @Nullable PgKeywordSearcher keywordSearcher) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.pgVectorProperties = pgVectorProperties;
        this.rerankModel = rerankModel;
        this.keywordSearcher = keywordSearcher;
    }

    @Override
    public List<Document> retrieve(Query query) {
        // 分类过滤**只判定一次**，两条路共用。各判各的话，漂移的表现是
        // 同一次检索里两条路按不同的分类在查——结果既不报错也说不清是怎么来的
        ClassificationFilter filter = ClassificationFilter.decide(query, ragProperties);

        long vectorStart = System.currentTimeMillis();
        List<Document> vectorHits = vectorSearch(query, filter);
        long vectorMs = System.currentTimeMillis() - vectorStart;

        long keywordStart = System.currentTimeMillis();
        List<Document> keywordHits = keywordSearch(query, filter);
        long keywordMs = System.currentTimeMillis() - keywordStart;

        if (vectorHits.isEmpty() && keywordHits.isEmpty()) {
            // 打 INFO 而不是 DEBUG：「知识库好像没工作」最常见的原因就是表里没东西或
            // 过滤条件把结果全筛掉了，而 DEBUG 在生产默认不输出——日志里一片安静，
            // 看起来和「压根没查」一模一样，这正是排查时最想分清的两种情形
            log.info("[pgvector] 两路都没召回切片（向量 {}ms / 关键词 {}ms，分类过滤 {}）：{}",
                    vectorMs, keywordMs, filter.isPresent() ? filter.value() : "无", query.text());
            return List.of();
        }

        // 注意这里**不能**在向量路为空时提前返回：向量 0 条、关键词 5 条是完全可能的
        // （问的正好是一个生僻专有名词），提前返回会把关键词那一路的成果静默丢掉
        List<Document> candidates = RrfFusion.fuse(
                List.of(new RrfFusion.Arm("vector", vectorHits), new RrfFusion.Arm("keyword", keywordHits)),
                pgVectorProperties.getRrf().getK(),
                pgVectorProperties.getRrf().getTopK());

        warnIfArmsDoNotOverlap(vectorHits, keywordHits, candidates.size());

        long rerankStart = System.currentTimeMillis();
        List<Document> reranked = rerank(query.text(), candidates);

        // 一次一行，把三段耗时分开报：向量粗排是本地的、关键词检索是一次数据库查询、
        // 精排是一次远程模型调用，「检索很慢」时先怀疑哪一段，看这一行就够了
        log.info("[pgvector] 向量 {} 条（{}ms）+ 关键词 {} 条（{}ms）"
                        + "→ 融合去重后 {} 条 → 精排 {} 条（{}ms）：{}",
                vectorHits.size(), vectorMs, keywordHits.size(), keywordMs,
                candidates.size(), reranked.size(), System.currentTimeMillis() - rerankStart,
                query.text());
        return reranked;
    }

    /**
     * 两路都召回了东西、却一条都不重叠时告警。
     *
     * <p>这是 RRF 最隐蔽的失效方式：去重靠 {@code Document.getId()}，而两路的 id
     * 来自不同的地方（向量路是表主键、关键词路也是表主键，正常必然重叠）。
     * 一旦对不上，RRF 就<b>退化成简单拼接</b>——结果仍然「看起来正常」，
     * 只是融合这一步白做了，没有任何别的迹象。
     */
    private static void warnIfArmsDoNotOverlap(List<Document> vectorHits,
                                               List<Document> keywordHits,
                                               int fusedCount) {
        if (vectorHits.isEmpty() || keywordHits.isEmpty()) {
            return;
        }
        if (fusedCount >= vectorHits.size() + keywordHits.size()) {
            log.warn("[pgvector] 向量路 {} 条与关键词路 {} 条完全没有重叠——两边的 "
                            + "Document id 可能不是同一套。对不上的话 RRF 会退化成简单拼接，"
                            + "融合这一步就白做了",
                    vectorHits.size(), keywordHits.size());
        }
    }

    /** 向量那一路：相似度粗排 + 分类过滤。 */
    private List<Document> vectorSearch(Query query, ClassificationFilter filter) {
        SearchRequest.Builder searchRequest = SearchRequest.builder()
                .query(query.text())
                .topK(pgVectorProperties.getCoarseTopK())
                // 默认 0.0 = 粗排不裁，把「像不像」的判断交给重排模型，
                // 因为余弦相似度的绝对值在不同问题之间并不可比
                .similarityThreshold(pgVectorProperties.getSimilarityThreshold());
        filter.toSpringAiFilter().ifPresent(searchRequest::filterExpression);
        return vectorStore.similaritySearch(searchRequest.build());
    }

    /** 关键词那一路。没装配这个协作者时安静地返回空，等价于纯向量模式。 */
    private List<Document> keywordSearch(Query query, ClassificationFilter filter) {
        if (keywordSearcher == null) {
            return List.of();
        }
        return keywordSearcher.search(query.text(), filter, pgVectorProperties.getKeyword().getTopK());
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
                .map(PgVectorDocumentRetriever::stampScore)
                .toList();

        // 把「被阈值挡掉的那几条差多少分」也报出来：调 rerank-min-score 的时候，
        // 这个数才是决定往上调还是往下调的依据。只报存活的那几条，等于让人靠猜
        if (log.isInfoEnabled()) {
            log.info("[pgvector] 重排得分（阈值 {}，取前 {}）：存活 {} 条{}",
                    ragProperties.getRerankMinScore(), topN, reranked.size(),
                    droppedScores(response, reranked.size()));
        }
        return reranked;
    }

    /**
     * 把重排分数写进切片的元数据。
     *
     * <p>写 metadata 是安全的：{@code RagPrompts.DOCUMENT_FORMATTER} 只读
     * index_id / doc_name / title / text 四个键，这个分数不会因此泄露给模型。
     * 它服务于自检接口与日志——「这几条为什么被判为不相关」是有价值的排查信息。
     */
    private static Document stampScore(DocumentWithScore scored) {
        scored.getOutput().getMetadata().put(META_SCORE, scored.getScore());
        return scored.getOutput();
    }

    /** 格式化「没达到阈值的那几条各是多少分」，一条都没有时返回空串。 */
    private static String droppedScores(RerankResponse response, int kept) {
        List<Double> scores = response.getResults().stream()
                .filter(result -> result != null && result.getScore() != null)
                .map(result -> result.getScore().doubleValue())
                .sorted(Comparator.reverseOrder())
                .toList();
        if (scores.size() <= kept) {
            return "";
        }
        return "，未入选的 " + (scores.size() - kept) + " 条得分 "
                + scores.subList(kept, scores.size()).stream()
                .map(score -> "%.3f".formatted(score))
                .collect(Collectors.joining("、"));
    }
}
