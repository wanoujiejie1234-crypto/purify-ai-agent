package com.purify.purifyaiagent.rag.pgvector;

import com.purify.purifyaiagent.rag.KnowledgeBaseAdvisor;
import com.purify.purifyaiagent.rag.KnowledgeRouter;
import com.purify.purifyaiagent.rag.RagPrompts;
import com.purify.purifyaiagent.rag.RagRetrieval;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.lang.Nullable;

/**
 * 本地 pgvector 链路的检索 Advisor：在每次模型调用前先路由、再查本地向量库，
 * 把命中的切片拼进用户消息。
 *
 * <p>检索动作本身（路由 → 检索 → 重编号 → 拼进用户消息）与百炼链路<b>共用同一份实现</b>，
 * 见 {@link RagRetrieval}——两条链路对用户而言应当是同一件事，换个知识库不该换掉对话的手感。
 *
 * <p><b>与百炼链路的差异只有一处——引用标注</b>：百炼的 Advisor 继承自官方实现，
 * 白拿了父类的 {@code after()} 来做 {@code <ref>[1]</code> 标注；本地链路没有这个父类，
 * {@code after()} 是直通的。也就是说 {@code purify.rag.enable-reference=true}
 * 在 {@code store=pgvector} 下<b>不生效</b>，装配时会对这个组合打一条 WARN。
 */
public class PgVectorKnowledgeBaseAdvisor implements KnowledgeBaseAdvisor {

    /**
     * 检索结果放进 {@code ChatClientRequest.context()} 用的键。
     *
     * <p>刻意不用百炼那个 {@code DashScopeApiConstants.RETRIEVED_DOCUMENTS}：
     * 那个键的意义是「喂给官方父类 {@code after()} 做引用标注」，本地链路没有这一步，
     * 借用它只会让人误以为引用标注在这里也是通的。这里放的是本链路自己的值，
     * 用途是调试与测试断言。
     */
    public static final String RETRIEVED_DOCUMENTS = "purify.rag.pgvector.retrieved_documents";

    /** 共享的检索步骤。 */
    private final RagRetrieval retrieval;

    private final int order;

    public PgVectorKnowledgeBaseAdvisor(DocumentRetriever retriever,
                                        PromptTemplate userTextAdvise,
                                        int order,
                                        KnowledgeRouter router,
                                        boolean routerEnabled) {
        this.retrieval = new RagRetrieval(retriever,
                ContextualQueryAugmenter.builder()
                        .promptTemplate(userTextAdvise)
                        .documentFormatter(RagPrompts.DOCUMENT_FORMATTER)
                        .build(),
                router,
                routerEnabled,
                // 用本链路自己的键，理由见上面常量的注释
                RETRIEVED_DOCUMENTS);
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, @Nullable AdvisorChain advisorChain) {
        return retrieval.before(request);
    }

    /** 直通。本地链路不做引用标注，理由见类注释。 */
    @Override
    public ChatClientResponse after(ChatClientResponse response, @Nullable AdvisorChain advisorChain) {
        return response;
    }

    @Override
    public int getOrder() {
        return order;
    }
}
