package com.purify.purifyaiagent.rag;

import com.purify.purifyaiagent.agent.AgentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 一次知识库检索的完整动作：<b>路由 → 检索 → 重编号</b>。
 *
 * <p>这段逻辑原本只有 Advisor 用得上，所以和 {@code ChatClientRequest} 绑在一起也看不出问题。
 * 现在多了三个调用方——智能体的开场预检索、智能体的 {@code knowledgeSearch} 工具、
 * 以及 {@code /api/rag/search} 自检接口——它们手里都没有 {@code ChatClientRequest}，
 * 却有同样的需求。在那三处各抄一遍就会出现第三、第四份副本，而副本漂移的表现是
 * 「同一个问题在不同入口召回的东西不一样」，不报任何错，只能靠人比对代码。
 * 这与 {@link RagPrompts} 当初被抽出来是同一个理由。
 *
 * <p><b>它不负责渲染。</b>把切片拼成文本有两套模板（轻语一套、智能体一套，
 * 差别见 {@link RagPrompts#AGENT_REFERENCE} 的注释），但拼装方式只有一种
 * （{@link RagPrompts#DOCUMENT_FORMATTER}）。所以这里只把编号 {@code index_id} 编好交出去——
 * 编号必须在格式化之前完成，两个调用方各自编号就等于两份实现。
 *
 * <p><b>它不做降级。</b>检索失败原样抛出，由调用方决定是「跳过这一轮」还是「报错」：
 * 轻语那边检索失败不该把对话搞砸（见 {@link RagRetrieval} 的注释），
 * 而自检接口恰恰需要把异常原样暴露出来，好让人看见失败原因。两种取向无法在一个方法里兼顾。
 */
@Slf4j
public final class KnowledgeSearch {

    /** 单个切片在日志里最多回显多少字符。只是给人看个大概，不必完整。 */
    private static final int LOG_EXCERPT_LENGTH = 40;

    private final DocumentRetriever retriever;

    private final KnowledgeRouter router;

    private final boolean routerEnabled;

    public KnowledgeSearch(DocumentRetriever retriever, KnowledgeRouter router, boolean routerEnabled) {
        this.retriever = retriever;
        this.router = router;
        this.routerEnabled = routerEnabled;
    }

    /**
     * 对一个问题做一次检索。
     *
     * @param question 用户这一轮的原始提问
     */
    public Result search(String question) {
        long start = System.currentTimeMillis();

        KnowledgeRouter.Decision decision = routerEnabled
                ? router.route(question)
                : KnowledgeRouter.Decision.all();

        if (!decision.retrieve()) {
            // 路由判定不查：连向量都不算。这里打 INFO 而不是 DEBUG，是因为
            // 「知识库好像没工作」最常见的两个原因之一就是它，而 DEBUG 在生产默认不输出，
            // 日志里一片安静，看起来和「检索了但没召回」一模一样
            log.info("[RAG] 与知识库无关，跳过检索：{}", question);
            return new Result(question, decision, List.of(), System.currentTimeMillis() - start);
        }

        Query query = toQuery(question, decision);
        List<Document> documents = retriever.retrieve(query);

        // 编号是拼提示词用的（[1] 【文档名】…），必须在格式化之前打好。
        // 放在这里而不是各调用方，是因为「编号从 1 开始、与列表顺序一致」这件事
        // 一旦有两份实现就会漂移，而漂移的表现是模型引用到的材料对不上号
        for (int i = 0; i < documents.size(); i++) {
            documents.get(i).getMetadata().put("index_id", i + 1);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[RAG] 「{}」→ 命中 {} 条，耗时 {}ms（分类：{}）{}",
                question, documents.size(), elapsed,
                decision.categories().isEmpty() ? "全库" : decision.categories(),
                summarize(documents));

        return new Result(question, decision, List.copyOf(documents), elapsed);
    }

    /**
     * 由「问题 + 路由判定」构造这次检索用的 {@link Query}。
     *
     * <p><b>只在真的要检索时调用。</b>spring-ai-rag 的 {@code Query} 构造器上有一句
     * {@code Assert.hasText(text, ...)}，空文本会直接抛 {@code IllegalArgumentException}。
     * 而空文本本来是被明确当作「不查」处理的（见 {@link KnowledgeRouter#route} 对空问题的分支），
     * 所以先判跳过、再建 Query 的顺序不能反——反了的话，那句「安静地跳过检索」
     * 就变成了一个把整轮对话打成 500 的异常。
     *
     * <p>检索与 augment 各需要一个 Query 对象，但两者都只由 {@code question} 和
     * {@code decision} 推出来（{@code Query} 是不可变的），所以这里与 {@link Result#toQuery()}
     * 共用同一个构造点，不存在「两处写法漂移」的问题。
     */
    private static Query toQuery(String question, KnowledgeRouter.Decision decision) {
        return Query.builder()
                .text(question)
                .context(decision.categories().isEmpty() ? Map.of()
                        : Map.of(KnowledgeRouter.CATEGORIES_KEY, decision.categories()))
                .build();
    }

    /**
     * 一次检索的结果。
     *
     * @param question   送检的文本。提示词模板里的 {@code {query}}、日志、工具回显都用它
     * @param decision   路由判定。<b>必须留着</b>：调用方要区分「压根没查」和「查了没命中」——
     *                   这两件事对用户完全不同（一个是问题不该问知识库，一个是知识库里没有），
     *                   压成一个空列表就再也分不出来了
     * @param documents  召回并已编好 {@code index_id} 的切片
     * @param elapsedMs  这次检索花掉的时间，含向量化与重排两次远程调用
     */
    public record Result(String question, KnowledgeRouter.Decision decision,
                         List<Document> documents, long elapsedMs) {

        /**
         * 拼进用户消息时用的 Query。
         *
         * <p><b>只在真的要改写用户消息时调用</b>——{@code RagRetrieval} 走到这一步时
         * {@code documents} 必然非空，也就必然经历过一次成功的检索，因此这里的文本一定非空
         * （空文本在 {@link #search} 里就被路由判成跳过了，压根走不到这）。
         * 反过来，如果在「跳过了」的分支上调用它，空文本会让 {@code Query} 的构造器抛异常。
         */
        public Query toQuery() {
            return KnowledgeSearch.toQuery(question, decision);
        }

        /**
         * 渲染成给<b>智能体</b>看的参考资料文本；没召回材料时返回空串，调用方据此跳过注入。
         *
         * <p>空结果返回空串而不是「知识库没查到」这句话，是有意的：把这句话塞进 Prompt,
         * 会诱导模型对用户说「知识库里没有相关内容」——而智能体还有联网搜索和别的工具，
         * 它该做的是接着查，不是当场拒答。「没查到」这件事由 {@link AgentEvent#retrieval}
         * 告诉<b>用户</b>，不告诉模型。
         */
        public String renderAgentReference() {
            if (documents.isEmpty()) {
                return "";
            }
            return RagPrompts.AGENT_REFERENCE.render(Map.of(
                    "context", RagPrompts.DOCUMENT_FORMATTER.apply(documents),
                    "query", question()));
        }

        /**
         * 给用户看的一句话摘要，走 {@link AgentEvent#retrieval} 发到前端。
         *
         * <p>只放条数和文档名，不放切片原文：SSE 是逐条推送的，把原文塞进来会把流刷爆，
         * 而用户在对话界面上本来也不需要读原文——要原文有日志和 {@code /api/rag/search}。
         */
        public String summary() {
            if (!decision.retrieve()) {
                return "知识库检索：与知识库无关，未发起检索";
            }
            if (documents.isEmpty()) {
                return "知识库检索「%s」：命中 0 条".formatted(question());
            }
            String sources = documents.stream()
                    .map(document -> document.getMetadata().get("doc_name"))
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .distinct()
                    .collect(Collectors.joining("、"));
            return "知识库检索「%s」：命中 %d 条（%s）· 耗时 %dms"
                    .formatted(question(), documents.size(), sources, elapsedMs);
        }
    }

    /** 日志里回显切片的来源与开头，够认出是哪几片就行。 */
    private static String summarize(List<Document> documents) {
        if (documents.isEmpty()) {
            return "";
        }
        return documents.stream()
                .map(document -> "[%s] %s".formatted(
                        document.getMetadata().get("doc_name"),
                        excerpt(document.getText())))
                .collect(Collectors.joining(" | "));
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= LOG_EXCERPT_LENGTH
                ? text
                : text.substring(0, LOG_EXCERPT_LENGTH) + "…";
    }
}
