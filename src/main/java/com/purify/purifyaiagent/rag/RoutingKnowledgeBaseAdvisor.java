package com.purify.purifyaiagent.rag;

import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrievalAdvisor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.lang.Nullable;

import static com.alibaba.cloud.ai.dashscope.common.DashScopeApiConstants.RETRIEVED_DOCUMENTS;

/**
 * 带路由的知识库检索 Advisor（百炼链路）：在官方 {@link DashScopeDocumentRetrievalAdvisor}
 * 的基础上加了两件事——<b>该不该查</b>和<b>该查哪一类</b>。
 *
 * <p>继承而不是重写，是为了白拿父类的「引用标注」收尾逻辑（{@code after}）和它
 * 往上下文里塞检索结果的约定，这里只重写 {@code before}。
 *
 * <p>相比父类多出来的两个行为（不相关的问题直接放行、没召回切片时也直接放行）
 * 见 {@link RagRetrieval#before}——那是两条链路共用的实现，本类只负责委托。
 *
 * <p>检索本身交给 {@link RoutingDocumentRetriever}：路由结果放在
 * {@link org.springframework.ai.rag.Query#context()} 里传下去，由它决定用哪一个过滤条件。
 *
 * <p>实现 {@link KnowledgeBaseAdvisor} 是为了让 {@code SlimApp} 能用同一个类型
 * 装上各条链路中当前生效的那一个，理由见该接口的注释。
 * 切片拼进 Prompt 的格式与提示词统一放在 {@link RagPrompts}。
 */
public class RoutingKnowledgeBaseAdvisor extends DashScopeDocumentRetrievalAdvisor implements KnowledgeBaseAdvisor {

    /**
     * 共享的检索步骤。
     *
     * <p>构造器里那个 {@code retriever} 会同时被 {@code super(...)} 和本字段各持一份，
     * 这不是冗余、<b>不要删掉 super 的那个参数</b>：父类的 {@code before()} 已被本类完全覆盖、
     * 确实用不到它，但 {@code super} 的构造器签名要求传，而且父类的其它初始化也依赖这个参数。
     */
    private final RagRetrieval retrieval;

    public RoutingKnowledgeBaseAdvisor(DocumentRetriever retriever, PromptTemplate userTextAdvise,
                                       boolean enableReference, int order,
                                       KnowledgeRouter router, boolean routerEnabled) {
        super(retriever, userTextAdvise, enableReference, order);
        this.retrieval = new RagRetrieval(retriever,
                ContextualQueryAugmenter.builder()
                        .promptTemplate(userTextAdvise)
                        .documentFormatter(RagPrompts.DOCUMENT_FORMATTER)
                        .build(),
                router,
                routerEnabled,
                // 必须用官方这个键：父类的 after() 靠它做引用标注
                RETRIEVED_DOCUMENTS);
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, @Nullable AdvisorChain advisorChain) {
        return retrieval.before(chatClientRequest);
    }
}
