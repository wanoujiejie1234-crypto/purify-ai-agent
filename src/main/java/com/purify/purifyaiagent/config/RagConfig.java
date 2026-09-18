package com.purify.purifyaiagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrievalAdvisor;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import com.purify.purifyaiagent.rag.KnowledgeRouter;
import com.purify.purifyaiagent.rag.RoutingDocumentRetriever;
import com.purify.purifyaiagent.rag.RoutingKnowledgeBaseAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云知识库 RAG 装配：把百炼上的知识库（「瘦身大师」）接成 Advisor 链上的一环。
 *
 * <p>整条链路是这样串起来的：
 * <pre>
 *   DashScopeApi                        发 HTTP 的客户端，只用到它的 restClient
 *     └── DashScopeDocumentRetriever    按知识库名字检索，返回命中的切片
 *           └── RoutingDocumentRetriever   按分类分发到上面某一个检索器
 *                 └── RoutingKnowledgeBaseAdvisor
 *                                      before 阶段先路由，再检索，最后把切片拼进用户消息
 * </pre>
 *
 * <p><b>为什么本地不建向量库/不装 Embedding 模型</b>：切片与向量都存在百炼，
 * 检索也是一次远程调用，所以 {@code spring-ai-starter-vector-store-*} 和
 * {@code spring-ai-starter-model-*} 这些依赖一个都不需要加。
 *
 * <p>整个类受 {@code purify.rag.enabled} 控制：关掉时这几个 Bean 都不创建，
 * {@code SlimApp} 通过 {@link ObjectProvider} 拿不到就把这一环跳过，应用照常启动。
 */
@Configuration
@ConditionalOnProperty(prefix = "purify.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagConfig {

    /**
     * 拼给模型的用户消息模板。
     *
     * <p>{@code {context}} 和 {@code {query}} 两个占位符一个都不能少：
     * 构造 {@code ContextualQueryAugmenter} 时会做校验，缺了直接抛异常；
     * 反过来，模板里多写的花括号也会被当成占位符而报「变量未替换」。
     */
    private static final PromptTemplate RAG_USER_TEXT_ADVISE = new PromptTemplate("""
            # 知识库
            下面是知识库中检索到的材料，请优先依据它们回答。

            要求：
            1. 材料里有答案时，就按材料说，不要自行发挥；
            2. 材料里没有答案时，直接说明知识库中没有相关内容，再给一般性的健康建议；
            3. 不要提「根据材料」「根据上下文」这类话，直接给结论。

            $$材料：
            {context}

            问题：{query}

            答案：
            """);

    /**
     * 专供检索使用的 DashScopeApi。
     *
     * <p>模型自动配置里那个 DashScopeApi 是在方法内部 new 出来的局部对象，容器里取不到，
     * 所以这里按同样的连接参数另建一个。它只用 restClient（检索是普通 HTTP 接口），
     * 不参与任何模型调用，因此和对话链路上的那个 API 客户端互不影响。
     */
    @Bean
    public DashScopeApi ragDashScopeApi(DashScopeProperties dashScopeProperties,
                                        RagProperties ragProperties,
                                        ObjectProvider<RestClient.Builder> restClientBuilder,
                                        ObjectProvider<WebClient.Builder> webClientBuilder) {
        return DashScopeApi.builder()
                .apiKey(dashScopeProperties.getApiKey())
                .baseUrl(dashScopeProperties.getBaseUrl())
                // 空字符串等于不传，不会多带一个 Workspace 请求头
                .workSpaceId(ragProperties.getWorkspaceId())
                .restClientBuilder(restClientBuilder.getIfAvailable(RestClient::builder))
                .webClientBuilder(webClientBuilder.getIfAvailable(WebClient::builder))
                .build();
    }

    /** 关键词路由：判定「要不要查、查哪一类」，纯字符串匹配，不产生任何远程开销。 */
    @Bean
    public KnowledgeRouter knowledgeRouter(RagProperties ragProperties) {
        return new KnowledgeRouter(ragProperties);
    }

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
     * <p>返回类型写成父类 {@link DashScopeDocumentRetrievalAdvisor}：
     * 调用方（{@code SlimApp}）只当它是个检索 Advisor，不需要知道里面多了路由这件事。
     */
    @Bean
    public DashScopeDocumentRetrievalAdvisor knowledgeBaseRetrievalAdvisor(
            DocumentRetriever bailianDocumentRetriever,
            RagProperties ragProperties,
            KnowledgeRouter knowledgeRouter) {
        return new RoutingKnowledgeBaseAdvisor(bailianDocumentRetriever,
                RAG_USER_TEXT_ADVISE,
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
