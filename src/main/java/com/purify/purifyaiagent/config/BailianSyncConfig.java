package com.purify.purifyaiagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.purify.purifyaiagent.i18n.MessageResolver;
import com.purify.purifyaiagent.rag.bailian.BailianConsoleClient;
import com.purify.purifyaiagent.rag.bailian.BailianKbSyncService;
import com.purify.purifyaiagent.rag.pgvector.PgVectorIndexService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 「百炼知识库 → 本地 pgvector 同步」的装配。
 *
 * <p><b>装配条件与 {@code KnowledgeBaseController} / {@code PgVectorRagConfig} 逐字一致</b>
 * （{@code enabled=true} 且 {@code store=pgvector}），四处都引用 {@link RagStore} 里的常量。
 *
 * <p><b>为什么不学 {@code RagController} 那样只跟 {@code enabled}、不跟 {@code store}</b>：
 * 那条先例的论证是「检索自检在走百炼链路时同样需要」，而这里不成立——这个功能的
 * <i>写</i>那一半（{@code indexPreChunked}）本来就只有 pgvector 链路才有，
 * 只放读的那一半等于人为造出「能列清单、一份都同步不了」的中间态。而且知识库管理页
 * 整页四张卡片全都带这个条件，只让其中一张在 {@code store=bailian} 时还能用，
 * 用户看到的是「一张能点、四张 404」，比整页不可用更难理解。
 *
 * <p>本质上，这个功能是<b>往本地向量库灌数据的第二个入口</b>，和
 * {@code POST /api/knowledge/documents} 是同一类东西，只是数据来源不同。
 * 同类的东西就该有同一个装配条件。
 *
 * <p><b>没有 {@code purify.rag.bailian.enabled} 开关</b>：少一个开关就少一处会不一致的地方。
 * 「配置配齐了没有」是运行时的事（见 {@code BailianKbSyncService#status()}），
 * 不是装配期的事——AK 没填不该让这张卡片消失，那只会让人来问「功能呢」。
 */
@Configuration
@ConditionalOnProperty(prefix = "purify.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = RagStore.PREFIX, name = "store", havingValue = RagStore.PGVECTOR)
public class BailianSyncConfig {

    /**
     * 管控面客户端。
     *
     * <p>业务空间 ID 在这里解析一次并定下来，而不是让客户端每次去问两个配置类：
     * 回落规则（本功能的优先，没配用 RAG 那条）只在这一处出现，
     * 将来要改成别的口径也只有一个地方要看。
     *
     * <p>知识库 ID 的回落不一样，它是个 {@code Supplier}——因为它要发一次 HTTP
     * 才能换到，不能在装配期做。详见 {@code BailianConsoleClient#indexIdFallback}。
     * {@code ragDashScopeApi} 参数名与 {@code RagCommonConfig} 里的 Bean 名一致，
     * 这样万一容器里多出一个 DashScopeApi，按名字解析也能拿到 RAG 这条链路的那个。
     */
    @Bean
    public BailianConsoleClient bailianConsoleClient(BailianKbProperties bailianKbProperties,
                                                     RagProperties ragProperties,
                                                     DashScopeApi ragDashScopeApi) {
        String workspaceId = StringUtils.hasText(bailianKbProperties.getWorkspaceId())
                ? bailianKbProperties.getWorkspaceId()
                : ragProperties.getWorkspaceId();
        return new BailianConsoleClient(bailianKbProperties, workspaceId,
                () -> ragDashScopeApi.getPipelineIdByName(ragProperties.getIndexName()));
    }

    /**
     * 同步编排。
     *
     * <p>{@code ragDashScopeApi} 参数名和 {@code RagCommonConfig} 里的 Bean 名一致是有意的：
     * 项目里只要这一个 {@code DashScopeApi} Bean，但万一将来多出一个，
     * 按名字解析能保证拿到的还是 RAG 这条链路用的那个。它只被自检接口用来对拍 pipeline_id。
     */
    @Bean
    public BailianKbSyncService bailianKbSyncService(BailianConsoleClient bailianConsoleClient,
                                                     BailianKbProperties bailianKbProperties,
                                                     PgVectorIndexService pgVectorIndexService,
                                                     RagProperties ragProperties,
                                                     PgVectorProperties pgVectorProperties,
                                                     MessageResolver messageResolver,
                                                     DashScopeApi ragDashScopeApi) {
        return new BailianKbSyncService(bailianConsoleClient, bailianKbProperties, pgVectorIndexService,
                ragProperties, pgVectorProperties, messageResolver, ragDashScopeApi);
    }
}
