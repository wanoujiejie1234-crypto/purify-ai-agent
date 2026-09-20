package com.purify.purifyaiagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.purify.purifyaiagent.rag.KnowledgeRouter;
import com.purify.purifyaiagent.rag.KnowledgeSearch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Locale;

/**
 * 知识库两条链路共用的装配。
 *
 * <p>这两个 Bean 原本在 {@code RagConfig} 里，和百炼专有的检索器/Advisor 混在一起。
 * 加了本地 pgvector 链路之后，它们同样被 pgvector 侧需要——{@code ragDashScopeApi}
 * 要拿去建 Embedding 模型和重排模型，{@code knowledgeRouter} 是两条链路共同的路由。
 * 留在 {@code RagConfig} 里的话会被 {@code store=bailian} 的条件挡掉，
 * 于是拆出来单放，只受 {@code purify.rag.enabled} 控制。
 *
 * <p>本类不产生任何远程调用，也不连库：关掉 RAG 时这两个 Bean 不创建，
 * 其余功能不受影响。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "purify.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagCommonConfig {

    /**
     * 供 RAG 链路使用的 DashScopeApi。
     *
     * <p>模型自动配置里那个 DashScopeApi 是在方法内部 new 出来的局部对象，容器里取不到，
     * 所以这里按同样的连接参数另建一个。RAG 链路只用到它的 restClient
     * （检索、向量化、重排都是普通 HTTP 接口），它不参与任何对话模型调用，
     * 因此和对话链路上的那个 API 客户端互不影响。
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
     * 一次检索的入口：路由 → 检索 → 编号。
     *
     * <p>轻语那边由 Advisor 内部自己组装一份（见 {@code RagRetrieval} 的构造器），
     * 这个 Bean 是给另外两个调用方的：{@code PurifyManus} 的开场预检索，
     * 和 {@code /api/rag/search} 自检接口。它们都拿不到 {@code ChatClientRequest}。
     *
     * <p><b>为什么可以安全地按类型注入 {@link DocumentRetriever}</b>：它由
     * {@code RagConfig} 或 {@code PgVectorRagConfig} 按 {@code store} 产出，
     * 而 store 是只有两个取值的枚举、写错在绑定期就抛异常（见 {@code RagProperties.Store}），
     * 所以「RAG 开着」必然对应恰好一个检索器，不存在两个候选。
     * 这与 {@code SlimApp} 按 {@code KnowledgeBaseAdvisor} 注入是同一个口径。
     */
    @Bean
    public KnowledgeSearch knowledgeSearch(DocumentRetriever retriever,
                                           KnowledgeRouter knowledgeRouter,
                                           RagProperties ragProperties) {
        // 启动体检：把「检索到底按什么参数在跑」一次打全。
        // 原先这些值散在三个配置类里，而最该被看见的两个（rerank-top-n、rerank-min-score）
        // 从来没有被打过——「知识库没召回东西」的排查全靠这两个数，
        // 不念出来就得去翻 yml，而翻的时候还未必知道该翻哪一项
        log.info("[RAG] 检索已装配：store={}，路由={}（{} 个分类，未命中时{}），"
                        + "重排={}，阈值={}，最终条数={}",
                ragProperties.getStore().name().toLowerCase(Locale.ROOT),
                ragProperties.getRouter().isEnabled() ? "开" : "关",
                ragProperties.getRouter().getCategories().size(),
                ragProperties.getRouter().isQueryAllWhenUnmatched() ? "查全库" : "跳过",
                ragProperties.isEnableReranking() ? ragProperties.getRerankModelName() : "关",
                ragProperties.getRerankMinScore(),
                ragProperties.getRerankTopN());

        return new KnowledgeSearch(retriever, knowledgeRouter, ragProperties.getRouter().isEnabled());
    }
}
