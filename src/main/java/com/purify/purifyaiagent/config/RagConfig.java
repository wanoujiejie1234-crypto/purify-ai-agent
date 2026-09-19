package com.purify.purifyaiagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import com.purify.purifyaiagent.rag.KnowledgeBaseAdvisor;
import com.purify.purifyaiagent.rag.KnowledgeRouter;
import com.purify.purifyaiagent.rag.RagPrompts;
import com.purify.purifyaiagent.rag.RoutingDocumentRetriever;
import com.purify.purifyaiagent.rag.RoutingKnowledgeBaseAdvisor;
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
 * <p>本类受两个条件共同控制：{@code purify.rag.enabled} 且
 * {@code purify.rag.store=bailian}（<b>带 {@code matchIfMissing}，不写 store 时默认走这条</b>，
 * 保证这次的改动对既有行为零影响）。两个条件都写在类上——{@code @ConditionalOnProperty}
 * 在 Spring Boot 3.5 是可重复标注的。
 */
@Configuration
@ConditionalOnProperty(prefix = "purify.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "purify.rag", name = "store", havingValue = "bailian", matchIfMissing = true)
public class RagConfig {

    /**
     * 知识库检索器。
     *
     * <p>实现的是 Spring AI 的 {@code DocumentRetriever} 接口，所以它不绑定百炼——
     * 哪天换成别的知识库，只换这个 Bean，Advisor 和 SlimApp 都不用动。
     *
     * <p>这里建的是「一路全库 + 每类一路」共 N+1 个检索器：它们只是同一个知识库配了
     * 不同的过滤条件，本身不发请求，真正发请求的是被选中的那一个。
     */
    @Bean
    public DocumentRetriever bailianDocumentRetriever(DashScopeApi ragDashScopeApi, RagProperties ragProperties) {
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
     * <p><b>Bean 名与 pgvector 链路刻意保持一致</b>：两条链路互斥，正常只会有一个。
     * 万一将来条件写错导致两个同时命中，Boot 默认禁止 Bean 定义覆盖，
     * 会在启动期直接抛 {@code BeanDefinitionOverrideException}——比留到注入阶段
     * 报含糊的 {@code NoUniqueBeanDefinitionException} 更容易定位。
     */
    @Bean
    public KnowledgeBaseAdvisor knowledgeBaseRetrievalAdvisor(
            DocumentRetriever bailianDocumentRetriever,
            RagProperties ragProperties,
            KnowledgeRouter knowledgeRouter) {
        return new RoutingKnowledgeBaseAdvisor(bailianDocumentRetriever,
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
