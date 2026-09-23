package com.purify.purifyaiagent.rag.pgvector;

import com.purify.purifyaiagent.config.RagProperties;
import com.purify.purifyaiagent.rag.KnowledgeCategories;
import com.purify.purifyaiagent.rag.KnowledgeRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * 「这一次检索该按哪个分类过滤」——判定一次，两条检索路各自翻译成自己的方言。
 *
 * <p><b>存在的理由</b>：加上关键词那一路之后，同一个判定要在两处生效——
 * 向量路把它翻成 Spring AI 的 {@link Filter.Expression}，关键词路把它翻成 SQL 的
 * {@code metadata->>'字段' = ?}。判定逻辑（尤其是「恰好命中一个已知分类才带过滤」
 * 这条和它背后的一串取舍）只能写一份：两处各写一份的话，漂移的表现是
 * <b>同一次检索里两条路按不同的分类在查</b>——一路只查食物热量、另一路查了全库，
 * 合起来的结果既不报错也说不清是怎么来的。
 *
 * <p>两类，见 {@link #decide}：
 * <ul>
 *   <li>{@link #none()} —— 不带过滤，查全库；</li>
 *   <li>{@code (field, value)} —— 按 {@code field = value} 等值过滤。</li>
 * </ul>
 *
 * @param field 元数据字段名，来自 {@code purify.rag.router.filter-key}；不带过滤时为 {@code null}
 * @param value 分类值；不带过滤时为 {@code null}
 */
@Slf4j
public record ClassificationFilter(@Nullable String field, @Nullable String value) {

    /** 不带过滤条件：查全库。 */
    public static ClassificationFilter none() {
        return new ClassificationFilter(null, null);
    }

    /**
     * 由「这次检索的 Query + 分类目录」判定该不该带分类过滤。
     *
     * <p>判定规约（这一段整体搬自原来的 {@code PgVectorDocumentRetriever#buildFilter}，
     * 注释一并带过来）：
     * <ul>
     *   <li><b>只有恰好命中一个分类时才带过滤。</b>命中多个时不带过滤、查全库——
     *       因为带过滤和不带过滤都是一次请求，开销一样，而一次查询最多只能按
     *       一个字段的一个值过滤（等值查询不支持一个字段配多个值）。
     *       退化成全库虽然多带了点无关切片，但重排那一关会把它们压下去，
     *       代价远小于「为多分类再发一次请求」。</li>
     *   <li><b>分类名不在分类目录里时不带过滤。</b>它来自路由的关键词表，而过滤用的
     *       字段名与取值必须和写入端一致；对不上时直接查全库，而不是拿一个查不到
     *       东西的条件去查——否则表现是「知识库突然什么都检索不到」，
     *       比多带点无关切片难查得多。
     *       注意「目录」不只是 yml 里那几项：用户自建的类型也在里面
     *       （见 {@code KnowledgeCategories}），所以自建类型这一步不会误判成未知。</li>
     * </ul>
     */
    public static ClassificationFilter decide(Query query,
                                              KnowledgeCategories knowledgeCategories,
                                              RagProperties ragProperties) {
        Object raw = query.context().get(KnowledgeRouter.CATEGORIES_KEY);
        if (!(raw instanceof List<?> categories) || categories.size() != 1) {
            return none();
        }

        String category = String.valueOf(categories.get(0));
        if (!knowledgeCategories.isKnown(category)) {
            log.warn("[pgvector] 分类目录里没有分类「{}」，退回全库检索", category);
            return none();
        }

        // 字段名取自配置而不是硬编码 classification：用户可以改 filter-key，
        // 但如果只改一边（写入端写的是改后的、检索端还在用硬编码的），
        // 过滤就会永远查不到任何东西且不报错
        String filterKey = ragProperties.getRouter().getFilterKey();
        log.debug("[pgvector] 只查分类「{}」（字段 {}）", category, filterKey);
        return new ClassificationFilter(filterKey, category);
    }

    /** 要不要带过滤。 */
    public boolean isPresent() {
        return field != null && value != null;
    }

    /** 向量路用的方言：Spring AI 的过滤表达式。 */
    public Optional<Filter.Expression> toSpringAiFilter() {
        if (!isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new FilterExpressionBuilder().eq(field, value).build());
    }

    /**
     * 关键词路用的方言：一段拼好的 SQL 谓词，形如 {@code c.metadata->>'classification' = ?}。
     *
     * <p>字段名要拼进 SQL，所以这里过一遍标识符防线（与写入端同一个校验）。
     * 值<b>不</b>拼进来，它走参数，见 {@link #toSqlArgument()}。
     *
     * @param tableAlias 表别名，与调用方 FROM 子句里用的那一个对上
     */
    public Optional<String> toSqlPredicate(String tableAlias) {
        if (!isPresent()) {
            return Optional.empty();
        }
        String key = PgVectorSql.safeMetadataKey(field, "purify.rag.router.filter-key");
        return Optional.of(tableAlias + ".metadata->>'" + key + "' = ?");
    }

    /**
     * 关键词路用的参数值。
     *
     * <p>与 {@link #toSqlPredicate} <b>成对使用</b>：谓词里放了一个 {@code ?}，
     * 这里就必须给出对应的那一个值，且必须按调用方拼 SQL 的顺序加进参数列表。
     */
    public Optional<Object> toSqlArgument() {
        return isPresent() ? Optional.of(value) : Optional.empty();
    }
}
