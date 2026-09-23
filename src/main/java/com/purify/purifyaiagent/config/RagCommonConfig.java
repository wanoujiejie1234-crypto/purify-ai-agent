package com.purify.purifyaiagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.purify.purifyaiagent.rag.KnowledgeCategories;
import com.purify.purifyaiagent.rag.KnowledgeRouter;
import com.purify.purifyaiagent.rag.KnowledgeSearch;
import com.purify.purifyaiagent.rag.pgvector.KeywordArmStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
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

    /**
     * 关键词路由：判定「要不要查、查哪一类」，纯字符串匹配，不产生任何远程开销。
     *
     * <p>分类来自 {@link KnowledgeCategories} 而不是直接读 {@code ragProperties}：
     * 除了 yml 里那几个内置分类，还有用户在管理页上自建的类型（本地向量库链路会
     * 从库里把它们发现出来）。详见那个接口的类注释。
     */
    @Bean
    public KnowledgeRouter knowledgeRouter(RagProperties ragProperties,
                                           KnowledgeCategories knowledgeCategories) {
        return new KnowledgeRouter(ragProperties, knowledgeCategories);
    }

    /**
     * 一次检索的入口：路由 → 检索 → 编号。
     *
     * <p>轻语那边由 Advisor 内部自己组装一份（见 {@code RagRetrieval} 的构造器），
     * 这个 Bean 是给另外两个调用方的：{@code PurifyManus} 的开场预检索，
     * 和 {@code /api/rag/search} 自检接口。它们都拿不到 {@code ChatClientRequest}。
     *
     * <p><b>参数名必须叫 {@code knowledgeBaseDocumentRetriever}，不要改名。</b>
     * 两条链路（{@code RagConfig} / {@code PgVectorRagConfig}）产出的检索器起的是
     * 同一个 Bean 名，这里就是<b>按名字注入</b>的。
     *
     * <p>为什么非要如此：检索器由 {@code store} 决定，而 store 是只有两个取值的枚举、
     * 写错在绑定期就抛异常（见 {@code RagProperties.Store}），所以「RAG 开着」必然
     * 对应恰好一个检索器——<b>运行期按类型解析本来也是安全的</b>。
     * 问题出在 IDE：它不解析 {@code @ConditionalOnProperty}，看不出那两个 Bean
     * 永远不会同时存在，于是把这里报成「more than one bean of DocumentRetriever type」。
     * 名字对齐之后两边都安静了。这与 {@code knowledgeBaseRetrievalAdvisor} 是同一个口径。
     */
    @Bean
    public KnowledgeSearch knowledgeSearch(DocumentRetriever knowledgeBaseDocumentRetriever,
                                           KnowledgeRouter knowledgeRouter,
                                           KnowledgeCategories knowledgeCategories,
                                           RagProperties ragProperties,
                                           ObjectProvider<KeywordArmStatus> keywordArmStatus) {
        // 启动体检：把「检索到底按什么参数在跑」一次打全。
        // 原先这些值散在三个配置类里，而最该被看见的两个（rerank-top-n、rerank-min-score）
        // 从来没有被打过——「知识库没召回东西」的排查全靠这两个数，
        // 不念出来就得去翻 yml，而翻的时候还未必知道该翻哪一项。
        //
        // 分类数在这里读的是**目录**（内置 + 库里发现的），不是 yml 那一份：
        // 两者在有人传过自建类型之后就不一样了，而「路由认识几个分类」
        // 恰恰是排查「自建类型检索不到」的第一个数
        List<String> categories = knowledgeCategories.values();
        log.info("[RAG] 检索已装配：store={}，路由={}（{} 个分类：{}，未命中时{}），"
                        + "重排={}，阈值={}，最终条数={}，关键词路={}",
                ragProperties.getStore().name().toLowerCase(Locale.ROOT),
                ragProperties.getRouter().isEnabled() ? "开" : "关",
                categories.size(),
                categories,
                ragProperties.getRouter().isQueryAllWhenUnmatched() ? "查全库" : "跳过",
                ragProperties.isEnableReranking() ? ragProperties.getRerankModelName() : "关",
                ragProperties.getRerankMinScore(),
                ragProperties.getRerankTopN(),
                describeKeywordArm(keywordArmStatus.getIfAvailable()));

        return new KnowledgeSearch(knowledgeBaseDocumentRetriever, knowledgeRouter,
                ragProperties.getRouter().isEnabled());
    }

    /**
     * 关键词那一路的一行状态。没这个 Bean 时（百炼链路）返回「不适用」。
     *
     * <p><b>这一段存在的全部意义是「不让降级悄悄发生」。</b>关键词那一路的 SQL 不调用
     * 任何 pg_bigm 函数，所以扩展没装时它不会报错、只会静默整表扫描。
     * 把状态摆在这一行启动日志里，是让「它没在工作」这件事在启动那一刻就可见。
     */
    private static String describeKeywordArm(KeywordArmStatus status) {
        if (status == null) {
            return "不适用";
        }
        if (status.available()) {
            return "开";
        }
        return "关（" + status.reason() + " —— 退化成纯向量检索，详见本行上方的 WARN）";
    }
}
