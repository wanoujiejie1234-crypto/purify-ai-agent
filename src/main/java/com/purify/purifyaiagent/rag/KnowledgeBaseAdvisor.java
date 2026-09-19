package com.purify.purifyaiagent.rag;

import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * 标记接口：只表示「这是知识库检索的那一环」。
 *
 * <p><b>为什么需要它</b>：知识库现在有两条链路——百炼云知识库（{@code store=bailian}）
 * 和本地 pgvector（{@code store=pgvector}）——它们各自产出一个 Advisor，
 * 类型完全不同。{@code SlimApp} 要把「当前生效的那一个」装进 Advisor 链，
 * 需要一个两条链路都能匹配上的公共类型。
 *
 * <p><b>为什么不能直接用 {@code BaseAdvisor} 注入</b>：{@code SensitiveWordAdvisor}、
 * {@code ReReadingAdvisor}、{@code LoggingAdvisor} 同样实现 {@code BaseAdvisor}，
 * 按它注入会有四个候选，直接 {@code NoUniqueBeanDefinitionException} 启动失败。
 * 所以必须是一个只被检索 Advisor 实现、别人都不实现的类型。
 *
 * <p>继承 {@code BaseAdvisor} 而不是 {@code Advisor}：两个实现本来就是 BaseAdvisor，
 * 声明成子类型不额外增加要求；而 BaseAdvisor 本身已经继承 Advisor，
 * 所以这个接口的实例照样能塞进 {@code ChatClient} 的 {@code List<Advisor>}。
 *
 * <p><b>两条链路的 Advisor 必须互斥</b>：它们都不带 {@code @Primary}，
 * 同时存在时 {@code ObjectProvider.getIfAvailable()} 会抛
 * {@code NoUniqueBeanDefinitionException}。互斥由两个配置类上的
 * {@code purify.rag.store} 条件保证（百炼那个带 {@code matchIfMissing}，pgvector 那个不带）。
 */
public interface KnowledgeBaseAdvisor extends BaseAdvisor {
}
