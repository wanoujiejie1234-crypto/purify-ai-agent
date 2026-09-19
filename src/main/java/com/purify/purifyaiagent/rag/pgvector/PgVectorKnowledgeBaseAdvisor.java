package com.purify.purifyaiagent.rag.pgvector;

import com.purify.purifyaiagent.rag.KnowledgeBaseAdvisor;
import com.purify.purifyaiagent.rag.KnowledgeRouter;
import com.purify.purifyaiagent.rag.RagPrompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 pgvector 链路的检索 Advisor：在每次模型调用前先路由、再查本地向量库，
 * 把命中的切片拼进用户消息。
 *
 * <p>行为和百炼那条链路的 {@code RoutingKnowledgeBaseAdvisor} <b>逐条对齐</b>，
 * 因为两者对用户而言应当是同一件事（换个知识库不该换掉对话的手感）：
 * <ol>
 *   <li><b>不相关的问题直接放行</b>：路由判定不用查库时连向量都不算，用户消息原样交给模型。
 *       问候、闲聊、与吃/动/药都无关的问题因此零检索开销。</li>
 *   <li><b>没召回切片时也直接放行</b>：不调用 augment（{@code ContextualQueryAugmenter}
 *       在空上下文时会把用户消息整个替换成「该问题超出知识库范围」），
 *       免得模型对着一句「你好」也一本正经地回「我无法回答」。</li>
 * </ol>
 *
 * <p><b>与百炼链路的差异——引用标注</b>：百炼的 Advisor 继承自官方实现，
 * 白拿了父类的 {@code after()} 来做 {@code <ref>[1]</code> 标注；本地链路没有这个父类，
 * {@code after()} 是直通的。也就是说 {@code purify.rag.enable-reference=true}
 * 在 {@code store=pgvector} 下<b>不生效</b>，装配时会对这个组合打一条 WARN。
 */
@Slf4j
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

    private final DocumentRetriever retriever;

    private final QueryAugmenter queryAugmenter;

    private final KnowledgeRouter router;

    private final boolean routerEnabled;

    private final int order;

    public PgVectorKnowledgeBaseAdvisor(DocumentRetriever retriever,
                                        PromptTemplate userTextAdvise,
                                        int order,
                                        KnowledgeRouter router,
                                        boolean routerEnabled) {
        this.retriever = retriever;
        this.queryAugmenter = ContextualQueryAugmenter.builder()
                .promptTemplate(userTextAdvise)
                .documentFormatter(RagPrompts.DOCUMENT_FORMATTER)
                .build();
        this.order = order;
        this.router = router;
        this.routerEnabled = routerEnabled;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, @Nullable AdvisorChain advisorChain) {
        String question = request.prompt().getUserMessage().getText();

        KnowledgeRouter.Decision decision = routerEnabled ? router.route(question) : KnowledgeRouter.Decision.all();

        if (!decision.retrieve()) {
            log.debug("[RAG] 与知识库无关，跳过检索：{}", question);
            return request.mutate().context(RETRIEVED_DOCUMENTS, Map.of()).build();
        }

        Query query = Query.builder()
                .text(question)
                .context(decision.categories().isEmpty() ? Map.of()
                        : Map.of(KnowledgeRouter.CATEGORIES_KEY, decision.categories()))
                .build();

        List<Document> documents = retriever.retrieve(query);

        Map<String, Object> context = new HashMap<>(request.context());
        Map<String, Document> documentMap = new HashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            int indexId = i + 1;
            // index_id 是编号不是数据库里的切片 ID，纯粹给提示词模板用
            document.getMetadata().put("index_id", indexId);
            documentMap.put("[%d]".formatted(indexId), document);
        }
        context.put(RETRIEVED_DOCUMENTS, documentMap);

        if (documents.isEmpty()) {
            log.debug("[RAG] 知识库没召回切片，按普通对话处理：{}", question);
            return request.mutate().context(context).build();
        }

        Query augmentedQuery = this.queryAugmenter.augment(query, documents);
        log.debug("[RAG] 召回 {} 条切片，已拼进用户消息", documents.size());

        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(augmentedQuery.text()))
                .context(context)
                .build();
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
