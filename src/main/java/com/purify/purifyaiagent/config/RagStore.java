package com.purify.purifyaiagent.config;

/**
 * {@code purify.rag.store} 这个开关的「坐标」常量：属性前缀 + 两个合法取值。
 *
 * <p><b>为什么要有这个类</b>：这个开关决定「装配哪一条知识库链路」，而被它门控的装配点
 * 分散在三个地方——{@link RagConfig}（百炼链路的检索器与 Advisor）、
 * {@link PgVectorRagConfig}（本地向量库与 Advisor）、以及 {@code KnowledgeBaseController}
 * （文档上传接口）。三处的口径必须一致：只要有一处写成别的值，就会出现
 * 「Bean 建起来了、上传接口却 404」这种自相矛盾的状态。
 *
 * <p>以前这个一致性由类型系统保证（三处都指向同一个条件类字面量）。删掉那个条件类之后，
 * 如果三处各写各的字符串字面量，编译器就再也帮不上忙了。所以把取值收敛到这里，
 * 让三处继续引用同一个常量——{@code String} 是编译期常量，可以合法地放进注解属性。
 *
 * <p><b>想再加第三个取值（比如恢复多路合并）时请注意</b>：一旦条件从「等于某个值」
 * 变成「等于某几个值之一」，{@code @ConditionalOnProperty} 就表达不了了，得改用
 * {@code AnyNestedCondition} 之类的复合条件。那时有一个静默的坑：自定义条件如果声明了
 * {@code ConfigurationPhase.PARSE_CONFIGURATION}，会被用在 {@code @Bean} 方法上时
 * <b>当成匹配直接放行</b>（{@code ConditionEvaluator} 只比较阶段是否相等），
 * 于是条件形同虚设却什么都不报。要写复合条件，阶段必须选 {@code REGISTER_BEAN}。
 * 本仓库最怕的就是「条件没生效但什么都不报」，这一点务必留意。
 */
public final class RagStore {

    private RagStore() {
    }

    /** 属性前缀，对应 yml 里的 {@code purify.rag}。 */
    public static final String PREFIX = "purify.rag";

    /**
     * 百炼云知识库：切片、向量、索引、重排都在百炼侧托管。
     *
     * <p>也是<b>缺省值</b>——不写 {@code store} 时走的就是这一条，保证既有部署的行为不变。
     */
    public static final String BAILIAN = "bailian";

    /** 本地 pgvector：文档、切片、向量都在本地的 PostgreSQL 里。 */
    public static final String PGVECTOR = "pgvector";
}
