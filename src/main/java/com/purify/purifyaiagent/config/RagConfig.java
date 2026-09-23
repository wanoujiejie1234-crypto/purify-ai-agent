package com.purify.purifyaiagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import com.purify.purifyaiagent.rag.KnowledgeBaseAdvisor;
import com.purify.purifyaiagent.rag.KnowledgeCategories;
import com.purify.purifyaiagent.rag.KnowledgeRouter;
import com.purify.purifyaiagent.rag.RagPrompts;
import com.purify.purifyaiagent.rag.RoutingDocumentRetriever;
import com.purify.purifyaiagent.rag.RoutingKnowledgeBaseAdvisor;
import com.purify.purifyaiagent.rag.StaticKnowledgeCategories;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 百炼「云知识库」链路：把百炼上的知识库（「瘦身大师」）接成 Advisor 链上的一环。
 *
 * <p>整条链路是这样串起来的：
 * <pre>
 *   DashScopeApi                        发 HTTP 的客户端（在 RagCommonConfig 里）
 *     └── DashScopeDocumentRetriever    按知识库名字检索，返回命中的切片
 *           └── RoutingDocumentRetriever   按分类分发到上面某一个检索器
 *                 └── RoutingKnowledgeBaseAdvisor
 *                                      before 阶段先路由，再检索，最后把切片拼进用户消息
 * </pre>
 *
 * <p><b>这条链路本地不做向量化</b>：切片与向量都存在百炼，检索也是一次远程调用，
 * 所以不需要 {@code spring-ai-starter-vector-store-*} 之类的依赖。
 * 想改成「文档、切片、向量都在本地 PostgreSQL 里」的话，把
 * {@code purify.rag.store} 改成 {@code pgvector}，装配会切到
 * {@link PgVectorRagConfig}——两条链路产出的都是 {@link KnowledgeBaseAdvisor}，
 * 上层（{@code SlimApp}）不需要知道下面换过。
 *
 * <p><b>装配条件</b>：{@code purify.rag.enabled} 且 {@code purify.rag.store} 是
 * {@link RagStore#BAILIAN}（或压根不写）。整个类一起装配、一起不装配——本类产出的检索器
 * 和 Advisor 都属于这一条链路，没有需要单独取舍的部分。
 *
 * <p>{@code matchIfMissing = true} 只出现在这一条链路上，不能挪到 pgvector 去：
 * 不写 store 时要退回百炼，而不是把本地向量库也一起拉起来。
 */
@Configuration
@ConditionalOnProperty(prefix = "purify.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = RagStore.PREFIX, name = "store",
        havingValue = RagStore.BAILIAN, matchIfMissing = true)
public class RagConfig {

    /**
     * 分类目录。百炼链路上只有 yml 里那一份——切片在云端，本地没有一张能查出
     * 「已经用过哪些分类」的表，写入端也不在本项目里（文档和控制台里的打标都在百炼侧）。
     * 详见 {@link StaticKnowledgeCategories} 的类注释。
     *
     * <p><b>Bean 名与 pgvector 链路那个刻意一致</b>（{@code knowledgeCategories}），
     * 理由同下面两个 Bean：两条链路的装配条件在类级互斥，容器里永远只有一个。
     */
    @Bean
    public KnowledgeCategories knowledgeCategories(RagProperties ragProperties) {
        return new StaticKnowledgeCategories(ragProperties);
    }

    /**
     * 知识库检索器。
     *
     * <p>实现的是 Spring AI 的 {@code DocumentRetriever} 接口，所以它不绑定百炼——
     * 哪天换成别的知识库，只换这个 Bean，Advisor 和 SlimApp 都不用动。
     *
     * <p>这里建的是「一路全库 + 每类一路」共 N+1 个检索器：它们只是同一个知识库配了
     * 不同的过滤条件，本身不发请求，真正发请求的是被选中的那一个。
     *
     * <p><b>Bean 名与 pgvector 链路那个刻意保持一致</b>（都叫
     * {@code knowledgeBaseDocumentRetriever}），理由同下面的
     * {@link #knowledgeBaseRetrievalAdvisor}：两条链路的装配条件在类级互斥，
     * 容器里永远只有一个。起同一个名字之后，共用方 {@code RagCommonConfig} 就能
     * <b>按名字注入</b>，不必再依赖「恰好只剩一个候选」这个隐式前提——
     * 后者在 IDE 里会被报成「more than one bean of DocumentRetriever type」，
     * 因为它不解析 {@code @ConditionalOnProperty}，看不出那两个 Bean 永远不会同时存在。
     */
    @Bean
    public DocumentRetriever knowledgeBaseDocumentRetriever(DashScopeApi ragDashScopeApi,
                                                            RagProperties ragProperties) {
        // 名字为空的话检索接口会拿空名字去换 pipeline_id，只能拿到 404，
        // 报错信息远不如在这里直接说清楚
        Assert.hasText(ragProperties.getIndexName(),
                "purify.rag.index-name 不能为空：请填写百炼控制台里的知识库名称");

        Map<String, DocumentRetriever> categoryRetrievers = new LinkedHashMap<>();
        ragProperties.getRouter().getCategories().forEach(category -> categoryRetrievers.put(category.getValue(),
                new DashScopeDocumentRetriever(ragDashScopeApi, options(ragProperties, category.getValue()))));

        return new RoutingDocumentRetriever(
                new DashScopeDocumentRetriever(ragDashScopeApi, options(ragProperties, null)),
                categoryRetrievers);
    }

    /**
     * 检索 Advisor：在每次模型调用前先路由、再查知识库，把命中的切片拼进用户消息。
     *
     * <p>order 必须显式传。不传的话这个类会退化成默认的 0，
     * 和 {@code SensitiveWordAdvisor} 撞在一起，谁先执行就取决于排序是否稳定了。
     *
     * <p><b>返回类型写成 {@link KnowledgeBaseAdvisor} 而不是具体类</b>是有意的：
     * {@code SlimApp} 是按这个接口类型注入的，而 Spring 判断一个 {@code @Bean} 能不能
     * 匹配上用的是<b>方法声明的返回类型</b>（不会为了匹配去实例化它）。返回类型写成
     * {@code DashScopeDocumentRetrievalAdvisor} 的话匹配不上，{@code SlimApp} 里的
     * {@code ifAvailable} 会安静地跳过——表现就是「RAG 没生效，但什么都不报」，
     * 属于最难查的一类故障。
     *
     * <p>Bean 名与 pgvector 链路那个刻意保持一致（都叫
     * {@code knowledgeBaseRetrievalAdvisor}）。两条链路的装配条件在类级就是互斥的
     * （store 不可能同时等于 bailian 和 pgvector），所以容器里永远只有一个，
     * {@code SlimApp} 按 {@link KnowledgeBaseAdvisor} 类型取也就不会撞上
     * {@code NoUniqueBeanDefinitionException}。
     */
    @Bean
    public KnowledgeBaseAdvisor knowledgeBaseRetrievalAdvisor(
            DocumentRetriever knowledgeBaseDocumentRetriever,
            RagProperties ragProperties,
            KnowledgeRouter knowledgeRouter) {
        return new RoutingKnowledgeBaseAdvisor(knowledgeBaseDocumentRetriever,
                RagPrompts.USER_TEXT_ADVISE,
                ragProperties.isEnableReference(),
                ragProperties.getOrder(),
                knowledgeRouter,
                ragProperties.getRouter().isEnabled());
    }

    /**
     * 组装检索参数。
     *
     * @param category 分类名；传 null 表示不带过滤条件，查全库
     */
    private DashScopeDocumentRetrieverOptions options(RagProperties ragProperties, String category) {
        DashScopeDocumentRetrieverOptions.Builder builder = DashScopeDocumentRetrieverOptions.builder()
                .withIndexName(ragProperties.getIndexName())
                .withDenseSimilarityTopK(ragProperties.getDenseSimilarityTopK())
                .withSparseSimilarityTopK(ragProperties.getSparseSimilarityTopK())
                .withEnableRewrite(ragProperties.isEnableRewrite())
                .withEnableReranking(ragProperties.isEnableReranking())
                .withRerankModelName(ragProperties.getRerankModelName())
                .withRerankMinScore(ragProperties.getRerankMinScore())
                .withRerankTopN(ragProperties.getRerankTopN());

        if (category != null) {
            // 过滤条件是「子分组」的数组，子分组之间是 AND，分组内是「字段: 值」的等值匹配。
            // 字段名来自配置（默认 classification），值就是分类名本身——两者都要求与
            // 控制台里打标时写的完全一致，写错了过滤不出任何东西，但不会报错。
            builder.withSearchFilters(List.of(Map.of(ragProperties.getRouter().getFilterKey(), category)));
        }

        return builder.build();
    }
}
