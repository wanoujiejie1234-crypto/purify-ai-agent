package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.auth.RequireAdmin;
import com.purify.purifyaiagent.exception.ApiException;
import com.purify.purifyaiagent.model.RagSearchResult;
import com.purify.purifyaiagent.rag.KnowledgeSearch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 检索自检接口：回答「我这个问题，知识库到底能查出什么来」。
 *
 * <p><b>为什么不加在 {@code KnowledgeBaseController} 上</b>：那个类带着
 * {@code store=pgvector} 的类级条件（走百炼时上传接口本就不该存在），而且它管的是「写」
 * ——上传、删除。这个接口是只读自检，走百炼链路时同样需要。挂到那边去，
 * 把 {@code store} 切回 {@code bailian} 就会 404，而那正是最需要核对召回的时候。
 * 所以这里的条件<b>只跟 {@code purify.rag.enabled} 走，不跟 store 走</b>。
 *
 * <p>它的存在还有一个更直接的理由：用户反馈过「项目根本没有做任何在线检索工作」，
 * 而检索是悄悄发生的——命中也好、没查也好，从对话界面上看都是「模型开始回答了」。
 * 这个接口把中间状态暴露出来，让「它在工作」这件事可以被验证，而不是只能靠感觉。
 *
 * <p><b>{@link RequireAdmin}：它和 {@code KnowledgeBaseController} 同属知识库模块，
 * 权限也要一致。</b>虽然它只读，但它原样吐回知识库切片的正文和来源文件名——
 * 那是整站共用的语料，不是调用者自己的数据。既然「知识库模块只对超级用户开放」
 * 是需求定下来的规则，那么给这个接口留一个普通用户能读的口子就等于没做这条规则。
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequireAdmin
@ConditionalOnProperty(prefix = "purify.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagController {

    /** 单条切片回显的正文长度。自检是给人看的，整片贴出来只会让响应难看。 */
    private static final int EXCERPT_LENGTH = 200;

    private final KnowledgeSearch knowledgeSearch;

    public RagController(KnowledgeSearch knowledgeSearch) {
        this.knowledgeSearch = knowledgeSearch;
    }

    /**
     * 拿一个问题跑一次真实的检索，把过程和结果原样返回。
     *
     * <p>用法：
     * <pre>
     *   curl "http://localhost:8080/api/rag/search?q=一碗米饭的热量是多少千卡"
     * </pre>
     *
     * <p><b>这是只读操作</b>：不改任何状态、不写库。所以它可以被放心地反复调用，
     * 用来对照不同说法、不同参数下的召回差异。
     *
     * <p>检索失败<b>不兜</b>：这里就是要看失败原因（数据库连不上、模型 key 失效、
     * 维度配错……），把它降级成一句「没查到」等于把排查线索扔掉。
     * 这与对话链路「检索失败只降级」的取向相反，是有意的——那条链路要保对话，
     * 这条链路要保真相。
     */
    @GetMapping("/search")
    public RagSearchResult search(@RequestParam("q") String question) {
        if (!StringUtils.hasText(question)) {
            throw ApiException.invalidChatRequest("q（要检索的问题）不能为空");
        }

        KnowledgeSearch.Result result = knowledgeSearch.search(question);
        List<Document> documents = result.documents();

        log.info("[RAG自检] 「{}」：查={} 分类={} 命中={} 耗时={}ms",
                question, result.decision().retrieve(), result.decision().categories(),
                documents.size(), result.elapsedMs());

        return new RagSearchResult(
                question,
                result.decision().retrieve(),
                result.decision().categories(),
                documents.size(),
                result.elapsedMs(),
                toChunks(documents),
                // 没召回时给 null 而不是空串：调用方一眼能看出「没有东西会被拼进 Prompt」
                documents.isEmpty() ? null : result.renderAgentReference());
    }

    private static List<RagSearchResult.Chunk> toChunks(List<Document> documents) {
        return documents.stream()
                .map(document -> new RagSearchResult.Chunk(
                        intOf(document.getMetadata().get("index_id")),
                        textOf(document.getMetadata().get("doc_name")),
                        textOf(document.getMetadata().get("title")),
                        excerpt(document.getText()),
                        // 百炼那条链路没有重排分数这件东西（重排在云端做，本地拿不到），
                        // 所以这里允许为 null，而不是硬塞一个 0——0 分和「没有分数」是两回事
                        doubleOf(document.getMetadata().get("rerank_score"))))
                .toList();
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= EXCERPT_LENGTH ? text : text.substring(0, EXCERPT_LENGTH) + "…";
    }

    private static String textOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Double doubleOf(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
