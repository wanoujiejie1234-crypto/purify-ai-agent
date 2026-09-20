package com.purify.purifyaiagent.config;

import com.purify.purifyaiagent.advisor.AdvisorOrders;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 配置，对应 application.yml 中的 {@code purify.rag.*}。
 *
 * <p>知识库有<b>两条链路</b>，用 {@link #store} 选走哪一条（两者只能选一）：
 * <ul>
 *   <li>{@link Store#BAILIAN} —— 百炼云知识库。文档解析、切片、向量化、建索引、
 *       重排序全部由百炼托管，本地不建向量库、不装 Embedding 模型，
 *       只负责把提问发给检索接口、把命中的切片拼进 Prompt。</li>
 *   <li>{@link Store#PGVECTOR} —— 本地自建。文档由上传接口进来，本地切片、
 *       调 DashScope 的 Embedding 接口向量化、存进 PostgreSQL 的 pgvector 扩展，
 *       检索时本地算相似度再交给重排模型精排。配置在
 *       {@code purify.rag.pgvector.*}（见 {@link PgVectorProperties}）。</li>
 * </ul>
 *
 * <p>两条链路共用的东西放在本类里：路由（{@code router.*}）、重排开关与阈值
 * （{@code enable-reranking} / {@code rerank-min-score} / {@code rerank-top-n}）、
 * Advisor 顺序（{@code order}）。只有百炼专有的参数（知识库名、workspace、
 * 百炼平台的重排模型名）才留在本类中由百炼链路单独使用。
 *
 * <p>之所以把这些参数从代码搬到 yml，是因为调 RAG 效果基本就是在调召回：
 * 召回太少答不全、太多又会被噪声带偏，这些数字需要边试边改，不该每次重新打包。
 */
@Data
@Component
@ConfigurationProperties(prefix = "purify.rag")
public class RagProperties {

    /**
     * 走哪一条知识库链路。
     *
     * <p>用枚举而不是字符串，是为了拿到「配错就在启动期报错」这个行为：
     * {@code store: pgvectr} 这种拼写错误会让 Spring 在绑定期抛 {@code BindException}，
     * 而不是几条链路都不装配、RAG 悄悄消失（那是最难查的一种故障）。
     *
     * <p><b>大小写是宽松的，这一条曾经被写反过，别再改回去</b>：枚举绑定本身不在乎大小写，
     * 而配置类上的 {@code @ConditionalOnProperty} 内部用的是
     * {@code havingValue.equalsIgnoreCase(实际值)}（见
     * {@code OnPropertyCondition#isMatch}），所以 {@code store: PGVECTOR} 和 {@code pgvector}
     * 的行为完全一样。曾经有注释说「配置类拿原始字符串比对、大小写写错会导致谁都不装配」，
     * 那是错的——照它去排查会找一个根本不存在的故障。
     *
     * <p>真正配错（比如 {@code pgvectr}）仍然会在绑定期抛异常，上面那条还成立。
     */
    public enum Store {

        /** 百炼云知识库：切片与向量都在云端，本地不做向量化。 */
        BAILIAN,

        /** 本地 pgvector：文档、切片、向量都在本地的 PostgreSQL 里。 */
        PGVECTOR
    }

    /**
     * 是否启用知识库检索。
     *
     * <p>关掉后 Advisor 链上只是少一环，普通对话、看图、结构化输出都不受影响，
     * 可以当作「检索接口出问题时」的应急开关。
     *
     * <p>{@code store=pgvector} 时这个开关还有第二层意义：pgvector 链路是<b>启动期</b>
     * 就要连 PostgreSQL 建表/校验的，数据库连不上会让整个应用起不来。
     * 这时把 {@code enabled} 关掉（或退回 {@code store=bailian}）就是恢复启动的逃生口。
     */
    private boolean enabled = true;

    /**
     * 走哪一条链路，默认百炼——保证不写这一项时的行为与加它之前完全一致。
     *
     * <p>取值：{@code bailian} 或 {@code pgvector}（大小写不敏感，见 {@link Store}）。
     * 写成别的值（包括曾经支持过的 {@code both}）会在启动期抛绑定异常，而不是静默失效——
     * 两条链路要么装配、要么起不来，不存在「悄悄不查知识库」这种中间状态。
     */
    private Store store = Store.BAILIAN;

    /**
     * 百炼控制台里创建好的知识库名称，必须一字不差（本项目的知识库叫「瘦身大师」）。
     *
     * <p>检索是「按名字找索引」：先拿名字换 pipeline_id，再拿 pipeline_id 去检索。
     * 名字写错不会在启动时报错——{@code DashScopeDocumentRetriever} 是懒加载的，
     * 要到第一次提问才抛出 {@code DashScopeException: Index:xxx NotExist}。
     */
    private String indexName;

    /**
     * 百炼业务空间 ID，通过请求头 {@code X-DashScope-WorkSpace} 下发。
     *
     * <p>主账号的默认空间可以留空；只有知识库建在子业务空间、而 API Key 属于另一个空间时才需要填。
     */
    private String workspaceId;

    /** 向量检索召回条数，上限 100。 */
    private int denseSimilarityTopK = 100;

    /** 关键词（稀疏）检索召回条数，与向量召回条数之和不超过 200。 */
    private int sparseSimilarityTopK = 100;

    /** 是否开启多轮改写：开启后检索服务会结合对话历史改写 query，指代消解更准。 */
    private boolean enableRewrite = false;

    /** 是否开启重排序。召回质量更好，代价是每次检索多一次模型调用。 */
    private boolean enableReranking = true;

    /** 重排序模型名。 */
    private String rerankModelName = "gte-rerank-hybrid";

    /** 重排序相似度阈值，低于它的切片直接丢弃；调高可以压掉不相关内容。 */
    private float rerankMinScore = 0.01f;

    /** 重排序后最终拼进 Prompt 的切片条数。 */
    private int rerankTopN = 5;

    /**
     * 是否开启引用标注（让模型在回答里输出 {@code <ref>[1]</ref>} 并能解析回原文）。
     *
     * <p>默认关闭，不是为了省事：开启后 Advisor 的收尾逻辑里有一句
     * {@code ChatCompletionFinishReason.valueOf(result.getMetadata().getFinishReason())}，
     * 而流式响应的 finishReason 可能是 null，会直接抛 NPE 把整条流打断。
     * 要用的话建议只在阻塞式接口上开。
     */
    private boolean enableReference = false;

    /**
     * 检索 Advisor 在链上的顺序。
     *
     * <p>必须比 {@link AdvisorOrders#SENSITIVE_WORD} 大：命中敏感词就该当场拦掉，
     * 没必要再去查一次知识库；又要比 {@link AdvisorOrders#RE_READING} 小，
     * 让提示词改写发生在检索之后、日志之前。
     */
    private int order = AdvisorOrders.KNOWLEDGE_BASE_RETRIEVAL;

    /**
     * 检索路由：先判断这个问题该不该查知识库、该查哪一类，再决定发不发检索请求。
     *
     * <p>不做路由的话，每问一句（包括「你好」）都要跑一次向量召回加一次重排序模型调用，
     * 而这些开销里绝大部分是白花的。
     */
    private Router router = new Router();

    @Data
    public static class Router {

        /** 是否启用路由。关掉就退回「每个问题都查全库」的老行为。 */
        private boolean enabled = true;

        /**
         * 做过滤用的元数据字段名，要和百炼控制台里给切片打的字段名一字不差。
         *
         * <p>本项目的知识库用 {@code classification} 把切片分成三类。
         */
        private String filterKey = "classification";

        /**
         * 分类表。写成列表而不是 {@code Map<分类名, 关键词>}，是为了不让中文出现在 yml 的
         * <b>键</b>上——Spring Boot 对属性名的合法字符有限制，中文键有绑不上的风险，
         * 而中文放在值里没有任何问题。
         *
         * <p>顺序即「同时命中多个分类时」拼装材料的顺序。
         */
        private List<Category> categories = new ArrayList<>();

        /**
         * 一个分类都没命中时怎么办。
         *
         * <p>{@code false}（默认）：这个问题用不着知识库，直接跳过检索——问候、闲聊、
         * 与瘦身无关的问题都归到这一类，是最省资源的行为。
         *
         * <p>{@code true}：退回「不带过滤查全库」。像「我想瘦十斤」这种没写明吃/动/药
         * 的问题会被前者跳过，如果发现这类问题答得不好，调成 true 即可。
         */
        private boolean queryAllWhenUnmatched = false;
    }

    /** 一个知识库分类：分类名 + 命中它的关键词。 */
    @Data
    public static class Category {

        /**
         * 分类名，必须与百炼控制台里 {@code filter-key} 那个字段的取值一字不差。
         *
         * <p>它有两个用途：作为检索时的过滤条件值，以及日志里的可读标签。
         */
        private String value;

        /** 提问里包含任意一个词就认为属于该分类。纯字符串包含匹配，不区分大小写。 */
        private List<String> keywords = new ArrayList<>();
    }
}
