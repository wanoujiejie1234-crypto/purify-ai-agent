package com.purify.purifyaiagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.purify.purifyaiagent.rag.KnowledgeRouter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

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
}
