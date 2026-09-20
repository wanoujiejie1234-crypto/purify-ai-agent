package com.purify.purifyaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次知识库检索的完整动作：<b>路由 → 检索 → 重编号 → 拼进用户消息</b>。
 *
 * <p>两条链路（百炼云知识库 / 本地 pgvector）的 Advisor 都把这套动作委托给本类，
 * 自己只负责在 {@code before} 里调一下。
 *
 * <p><b>为什么要抽这个类</b>：这段逻辑原先在 {@link RoutingKnowledgeBaseAdvisor} 和
 * {@code PgVectorKnowledgeBaseAdvisor} 里各有一份，两者逐字相同，只有上下文键不一样。
 * 「两条链路对用户而言应当是同一件事」（换个知识库不该换掉对话的手感），
 * 所以它们理应共用同一份实现——两份拷贝迟早会漂移，而漂移的表现是
 * 「同一个问题在两条链路上答得不一样」，极难排查。这与 {@link RagPrompts} 抽提示词
 * 是同一个理由。
 *
 * <p><b>为什么不是共同父类</b>：{@link RoutingKnowledgeBaseAdvisor} 必须继承
 * {@code DashScopeDocumentRetrievalAdvisor} 才能白拿父类的引用标注收尾逻辑，
 * Java 只有单继承，塞不进第二个父类。所以这里用组合而不是继承。
 *
 * <p>本类只被 Advisor 持有，不作为 Bean 注册。
 */
@Slf4j
public final class RagRetrieval {

    /** 路由 → 检索 → 编号。三条链路（两个 Advisor、智能体、自检接口）共用同一份实现。 */
    private final KnowledgeSearch knowledgeSearch;

    private final QueryAugmenter queryAugmenter;

    /** 检索结果写进 {@code ChatClientRequest.context()} 用的键，取值理由见构造器。 */
    private final String documentsKey;

    /**
     * @param retriever      实际执行检索的那一个，两条链路各给自己路的
     * @param queryAugmenter 把召回的切片拼进用户消息的改写器，两条链路共用
     *                       {@link RagPrompts#USER_TEXT_ADVISE} 构造
     * @param router         判断「该不该查、该查哪一类」，纯字符串匹配
     * @param documentsKey   检索结果放进上下文用的键。<b>这个参数两条链路取值不同，
     *                       是故意的，不要顺手统一</b>：
     *                       <ul>
     *                         <li>百炼链路传 {@code DashScopeApiConstants.RETRIEVED_DOCUMENTS}——
     *                             它继承的官方父类，{@code after()} 里会无条件读这个键来做
     *                             {@code <ref>[1]</ref>} 引用标注，传别的键引用标注就废了。</li>
     *                         <li>pgvector 链路传 {@code PgVectorKnowledgeBaseAdvisor.RETRIEVED_DOCUMENTS}——
     *                             那条链路没有引用标注这一步，借用百炼的键只会让人误以为
     *                             在这里引用标注也是通的。</li>
     *                       </ul>
     */
    public RagRetrieval(DocumentRetriever retriever,
                        QueryAugmenter queryAugmenter,
                        KnowledgeRouter router,
                        boolean routerEnabled,
                        String documentsKey) {
        // 构造签名保持不变：两个 Advisor 的装配处因此一行都不用改。
        // 传进来的还是原始的三个依赖，组装成 KnowledgeSearch 是本类内部的事
        this.knowledgeSearch = new KnowledgeSearch(retriever, router, routerEnabled);
        this.queryAugmenter = queryAugmenter;
        this.documentsKey = documentsKey;
    }

    /**
     * 就地改写请求：把检索到的材料拼进用户消息。
     *
     * <p>两条「直接放行」的路径是有意为之，不是漏判：
     * <ol>
     *   <li><b>路由判定不用查库</b>：连向量都不算，用户消息原样交给模型。
     *       问候、闲聊、与吃/动/药都无关的问题因此零检索开销。</li>
     *   <li><b>没召回切片</b>：不调用 augment。{@code ContextualQueryAugmenter} 在空上下文时
     *       会把用户消息整个替换成「该问题超出知识库范围」，模型于是对着一句「你好」
     *       也一本正经地回「我无法回答」。检索没帮上忙，不该反过来把对话搞砸。</li>
     * </ol>
     */
    public ChatClientRequest before(ChatClientRequest request) {
        String question = request.prompt().getUserMessage().getText();

        // 路由、检索、编号都在 KnowledgeSearch 里，和智能体那条链路共用同一份实现。
        // 本方法剩下的部分是 Advisor 独有的：把结果写进 context、把材料拼进用户消息
        KnowledgeSearch.Result result;
        try {
            result = knowledgeSearch.search(question);
        }
        catch (RuntimeException exception) {
            // 检索失败只降级、不往上抛。往上抛的后果不是「这轮没有参考资料」，
            // 而是整条对话变成一句「服务暂时出了点问题，请稍后再试。」——
            // 而这条降级路径不是理论情况：pgvector 的连接池把 initializationFailTimeout
            // 设成了 -1，数据库连不上时应用照常启动，失败正好推迟到第一次检索。
            // 为了一个可选的知识库搭上整个对话，是把主次搞反了。
            //
            // 空 Map 而不是 null：百炼那条链路的父类 after() 会无条件读这个键
            log.warn("[RAG] 检索失败，本轮按无参考资料处理：{}", exception.getMessage());
            return request.mutate().context(documentsKey, Map.of()).build();
        }

        if (!result.decision().retrieve()) {
            // 百炼那条链路的父类 after() 会无条件读这个键，跳过时也得放一个空结果进去，
            // 否则收尾阶段拿到的是 null
            return request.mutate().context(documentsKey, Map.of()).build();
        }

        Map<String, Object> context = new HashMap<>(request.context());
        Map<String, Document> documentMap = new HashMap<>();
        List<Document> documents = result.documents();
        for (Document document : documents) {
            // 编号由 KnowledgeSearch 统一打好（见那里的注释），这里只按它建索引。
            // index_id 是编号，不是百炼的切片 ID、也不是数据库里的切片 ID，纯粹给提示词模板用
            documentMap.put("[%s]".formatted(document.getMetadata().get("index_id")), document);
        }
        context.put(documentsKey, documentMap);

        if (documents.isEmpty()) {
            log.debug("[RAG] 知识库没召回切片，按普通对话处理：{}", question);
            return request.mutate().context(context).build();
        }

        Query augmentedQuery = this.queryAugmenter.augment(result.toQuery(), documents);

        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(augmentedQuery.text()))
                .context(context)
                .build();
    }
}
