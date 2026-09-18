package com.purify.purifyaiagent.rag;

import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrievalAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
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
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.dashscope.common.DashScopeApiConstants.RETRIEVED_DOCUMENTS;

/**
 * 带路由的知识库检索 Advisor：在官方 {@link DashScopeDocumentRetrievalAdvisor} 的基础上
 * 加了两件事——<b>该不该查</b>和<b>该查哪一类</b>。
 *
 * <p>继承而不是重写，是为了白拿父类的「引用标注」收尾逻辑（{@code after}）和它
 * 往上下文里塞检索结果的约定，这里只重写 {@code before}。
 *
 * <p>相比父类多出来的两个行为：
 * <ol>
 *   <li><b>不相关的问题直接放行</b>：路由判定不用查库时不发检索请求，用户消息原样交给模型。
 *       问候、闲聊、与吃/动/药都无关的问题因此零检索开销。</li>
 *   <li><b>没召回切片时也直接放行</b>：父类在这种情况会走
 *       {@code ContextualQueryAugmenter} 的「空上下文」分支，把用户消息整个替换成
 *       「该问题超出知识库范围」，模型于是对着一句「你好」也一本正经地回「我无法回答」。
 *       这里改成按普通对话处理——检索没帮上忙，不该反过来把对话搞砸。</li>
 * </ol>
 *
 * <p>检索本身交给 {@link RoutingDocumentRetriever}：路由结果放在
 * {@link Query#context()} 里传下去，由它决定用哪一个过滤条件。
 */
@Slf4j
public class RoutingKnowledgeBaseAdvisor extends DashScopeDocumentRetrievalAdvisor {

    /**
     * 切片拼进 Prompt 的格式。
     *
     * <p>必须和父类里的那份保持一致：模型的提示词是按这个格式调的，
     * 改了格式等于换了提示词。父类那份是私有的，这里只能照抄一份。
     */
    private static final Function<List<Document>, String> DOCUMENT_FORMATTER = documents -> documents.stream()
            .map(document -> """
                    [%s] 【文档名】%s
                    【标题】%s
                    【正文】%s
                    """.formatted(document.getMetadata().get("index_id"), document.getMetadata().get("doc_name"),
                    document.getMetadata().get("title"), document.getText()))
            .collect(Collectors.joining(System.lineSeparator()));

    private final DocumentRetriever retriever;

    private final QueryAugmenter queryAugmenter;

    private final KnowledgeRouter router;

    private final boolean routerEnabled;

    public RoutingKnowledgeBaseAdvisor(DocumentRetriever retriever, PromptTemplate userTextAdvise,
                                       boolean enableReference, int order,
                                       KnowledgeRouter router, boolean routerEnabled) {
        super(retriever, userTextAdvise, enableReference, order);
        this.retriever = retriever;
        this.queryAugmenter = ContextualQueryAugmenter.builder()
                .promptTemplate(userTextAdvise)
                .documentFormatter(DOCUMENT_FORMATTER)
                .build();
        this.router = router;
        this.routerEnabled = routerEnabled;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, @Nullable AdvisorChain advisorChain) {
        String question = chatClientRequest.prompt().getUserMessage().getText();

        KnowledgeRouter.Decision decision = routerEnabled ? router.route(question) : KnowledgeRouter.Decision.all();

        if (!decision.retrieve()) {
            log.debug("[RAG] 与知识库无关，跳过检索：{}", question);
            // 父类的 after() 会无条件读这个键，跳过时也得放一个空结果进去，
            // 否则收尾阶段拿到的是 null
            return chatClientRequest.mutate().context(RETRIEVED_DOCUMENTS, Map.of()).build();
        }

        Query query = Query.builder()
                .text(question)
                .context(decision.categories().isEmpty() ? Map.of()
                        : Map.of(KnowledgeRouter.CATEGORIES_KEY, decision.categories()))
                .build();

        List<Document> documents = retriever.retrieve(query);

        Map<String, Object> context = new HashMap<>(chatClientRequest.context());
        Map<String, Document> documentMap = new HashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            int indexId = i + 1;
            // index_id 是编号不是百炼的切片 ID，纯粹给上面的模板和引用标注用
            document.getMetadata().put("index_id", indexId);
            documentMap.put("[%d]".formatted(indexId), document);
        }
        context.put(RETRIEVED_DOCUMENTS, documentMap);

        if (documents.isEmpty()) {
            log.debug("[RAG] 知识库没召回切片，按普通对话处理：{}", question);
            return chatClientRequest.mutate().context(context).build();
        }

        Query augmentedQuery = this.queryAugmenter.augment(query, documents);
        log.debug("[RAG] 召回 {} 条切片，已拼进用户消息", documents.size());

        return chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().augmentUserMessage(augmentedQuery.text()))
                .context(context)
                .build();
    }
}
