package com.purify.purifyaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.Map;

/**
 * 按分类分发的检索器：同一个知识库，按元数据 {@code classification} 切成几路来查。
 *
 * <p>持有一组「一个分类对应一个检索器」的映射，每个检索器的检索参数完全一样，
 * 只差一个预置好的 {@code search_filters}。查哪一路由调用方通过
 * {@link KnowledgeRouter#CATEGORIES_KEY} 放进 {@link Query#context()} 决定。
 *
 * <p><b>只有恰好命中一个分类时才真正带过滤</b>：命中多个分类时不带过滤、查全库。
 * 因为带过滤和不带过滤都是一次远程调用，开销一样，为多分类再发一次请求等于把
 * 省下来的开销又还回去了；而一次请求最多只能按一个字段的一个值过滤
 * （等值查询不支持一个字段配多个值）。退化成全库虽然多带了点无关切片，
 * 但重排序那一关会把它们压下去，代价远小于多发一次请求。
 *
 * <p><b>那组映射在构造时就定死了，只可能是 yml 里配的那几项。</b>本地 pgvector 链路上
 * 用户自建的类型不在这里，所以走到这一路时会「找不到对应检索器、退回全库」——
 * 那是安全的降级（宁可多带噪声，也不要因为一个查不到东西的条件而漏掉答案）；
 * 而自建类型真正能被检索到，靠的是本地那条链路的
 * {@code ClassificationFilter} 从分类目录里认出它。百炼链路没有自建类型这个概念，
 * 分类只能在百炼控制台上打，所以那边不需要这一层。
 *
 * <p>context 里没有 {@link KnowledgeRouter#CATEGORIES_KEY} 时（比如路由没开、
 * 或者有人直接调这个 Bean）一律走全库检索，行为和加路由之前完全一致。
 */
@Slf4j
public class RoutingDocumentRetriever implements DocumentRetriever {

    /** 不带任何过滤条件的检索器，兜底用。 */
    private final DocumentRetriever allRetriever;

    /** 分类名 → 预置了该分类过滤条件的检索器。 */
    private final Map<String, DocumentRetriever> categoryRetrievers;

    public RoutingDocumentRetriever(DocumentRetriever allRetriever, Map<String, DocumentRetriever> categoryRetrievers) {
        this.allRetriever = allRetriever;
        this.categoryRetrievers = Map.copyOf(categoryRetrievers);
    }

    @Override
    public List<Document> retrieve(Query query) {
        Object raw = query.context().get(KnowledgeRouter.CATEGORIES_KEY);

        if (raw instanceof List<?> categories && categories.size() == 1) {
            DocumentRetriever retriever = categoryRetrievers.get(String.valueOf(categories.get(0)));
            if (retriever != null) {
                log.debug("[RAG路由] 只查分类「{}」", categories.get(0));
                return retriever.retrieve(query);
            }
            // 配置里的分类名和路由给出的对不上，属于配置写错；退回全库而不是查空，
            // 免得表现为「知识库突然什么都查不到」
            log.warn("[RAG路由] 没有为分类「{}」配置检索器，退回全库检索", categories.get(0));
        }

        return allRetriever.retrieve(query);
    }
}
