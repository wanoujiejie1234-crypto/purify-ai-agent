package com.purify.purifyaiagent.rag;

import com.purify.purifyaiagent.advisor.AdvisorOrders;
import com.purify.purifyaiagent.app.SlimApp;
import com.purify.purifyaiagent.config.RagProperties;
import com.purify.purifyaiagent.exception.SensitiveWordException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 百炼云知识库 RAG 的集成测试。
 *
 * <p>和 {@code SlimAppTest} 一样是「真调用」：会连本地 MySQL 存记忆、
 * 真实请求百炼的知识库检索接口和 qwen-plus。运行前确认：
 * <ul>
 *   <li>{@code purify.rag.enabled=true}，且 {@code purify.rag.index-name}
 *       与百炼控制台里的知识库名称一字不差（当前是「瘦身大师」）；</li>
 *   <li>application.yml 里的 DashScope api-key 有效，
 *       且该 Key 所属账号有权限访问这个知识库；</li>
 *   <li>知识库里确实有内容——空知识库会让检索直接返回 0 条。</li>
 * </ul>
 *
 * <p>用例是分层的：{@link #retrieve()} 和 {@link #retrieve_withCategoryFilter()} 只验
 * 「检索通不通、过滤生不生效」，{@link #chat_usesKnowledgeBase()} 验「检索结果有没有真的进到
 * Prompt 里」。出问题先跑前两个，能快速区分是知识库没配好，还是 Advisor 没接上。
 *
 * <p>路由判定本身不在这里测——那部分不连库也不调模型，
 * 放在 {@link KnowledgeRouterTest} 里单独跑，秒出结果。
 */
@Slf4j
@SpringBootTest
class BailianRagTest {

    /**
     * 这里声明成 Spring AI 的 {@code DocumentRetriever} 而不是具体的实现，
     * 一是容器里注册的就是这个接口，二是顺带验证了「换知识库实现不影响调用方」。
     */
    @Resource
    private DocumentRetriever bailianDocumentRetriever;

    @Resource
    private SlimApp slimApp;

    @Resource
    private RagProperties ragProperties;

    @Test
    @DisplayName("检索：能从百炼知识库召回切片，且切片带正文")
    void retrieve() {
        log.info("[retrieve] 知识库={}，召回参数：topN={} minScore={}",
                ragProperties.getIndexName(), ragProperties.getRerankTopN(), ragProperties.getRerankMinScore());

        List<Document> documents = bailianDocumentRetriever.retrieve(
                Query.builder().text("减脂期每天应该吃多少蛋白质？").build());
        log.info("[retrieve] 召回 {} 条切片", documents.size());
        // 只打元数据里确实存在的键：检索返回的 score 在转换时被丢掉了，打出来只会是 null
        documents.forEach(document -> log.info("[retrieve] 文档={} 标题={} 正文={}",
                document.getMetadata().get("doc_name"),
                document.getMetadata().get("title"),
                document.getText()));

        assertFalse(documents.isEmpty(),
                "知识库没召回任何切片：先确认 index-name 与百炼控制台一致、知识库非空、api-key 有访问权限");
        documents.forEach(document -> assertTrue(document.getText() != null && !document.getText().isBlank(),
                "切片正文不应为空，否则拼进 Prompt 的会是一堆空串"));
    }

    @Test
    @DisplayName("元数据过滤：带分类检索时，召回切片应只剩这一类")
    void retrieve_withCategoryFilter() {
        String filterKey = ragProperties.getRouter().getFilterKey();

        List<Document> documents = bailianDocumentRetriever.retrieve(Query.builder()
                .text("鸡胸肉多少大卡")
                .context(Map.of(KnowledgeRouter.CATEGORIES_KEY, List.of("食物热量")))
                .build());
        log.info("[retrieve_withCategoryFilter] 召回 {} 条切片，元数据：{}", documents.size(),
                documents.stream().map(Document::getMetadata).toList());

        assertFalse(documents.isEmpty(), "带分类过滤后一条都没召回：确认分类名与知识库里 classification 的取值一致");

        // 检索接口到底认不认 search_filters，只能靠返回的元数据反推：返回的切片如果
        // 清一色是「食物热量」，说明过滤生效了；如果混进别的分类，说明这个接口忽略了
        // 过滤条件——功能本身不受影响（照样能答），只是省不下召回的开销。
        boolean metadataPresent = documents.stream()
                .anyMatch(document -> document.getMetadata().containsKey(filterKey));
        if (!metadataPresent) {
            log.warn("[retrieve_withCategoryFilter] 返回的切片里没有 {} 字段，无法判断过滤是否生效", filterKey);
            return;
        }

        documents.forEach(document -> assertEquals("食物热量", document.getMetadata().get(filterKey),
                "过滤没生效：返回了别的分类的切片。若确认接口不支持 search_filters，"
                        + "把 purify.rag.router.enabled 关掉即可，行为会退回加路由之前"));
    }

    @Test
    @DisplayName("RAG 对话：回答非空，且日志里能看到拼进 Prompt 的知识库切片")
    void chat_usesKnowledgeBase() {
        String chatId = slimApp.newChatId();

        String reply = slimApp.chat("我目前身高170体重71kg,要减肥建议每天吃什么，怎么搭配", chatId);
        log.info("[chat_usesKnowledgeBase] chatId={} 回答：{}", chatId, reply);

        assertNotNull(reply, "模型回答不应为 null");
        assertFalse(reply.isBlank(), "模型回答不应为空");

        // 这条断言只能验「链路没断」，验不了「答得对不对」——知识库里到底有什么内容
        // 只有业务侧知道。检索质量请回看上面 retrieve() 打出的切片，以及 LoggingAdvisor
        // 打的完整 Prompt：里面应该能看到 # 知识库 段和召回的材料。
        // 另外这一轮问答共 2 条消息入库（用户提问 + 模型回答），图片不会入库、切片也不会。
        assertTrue(slimApp.history(chatId).size() >= 2, "一轮问答后应至少有 2 条消息入库");
    }

    @Test
    @DisplayName("闲聊不查知识库：检索为空时不会把用户的「你好」替换成「超出知识库范围」")
    void chat_chitchatSkipsKnowledgeBase() {
        String chatId = slimApp.newChatId();

        String reply = slimApp.chat("你好呀", chatId);
        log.info("[chat_chitchatSkipsKnowledgeBase] chatId={} 回答：{}", chatId, reply);

        // 真正要看的在日志里：这一轮不该出现「# 知识库」段，也不该出现
        // 「The user query is outside your knowledge base」这种被替换掉的用户消息。
        assertFalse(reply.isBlank(), "闲聊也应当有正常回复");
    }

    @Test
    @DisplayName("RAG 不干扰敏感词拦截：命中高危词时不查知识库、直接拦")
    void chat_sensitiveWordStillBlocks() {
        String chatId = slimApp.newChatId();

        // 敏感词 Advisor 的 order=0，检索 Advisor 的 order=5，
        // 所以命中的请求会在检索之前就被抛出去，不会白跑一次远程检索
        assertTrue(ragProperties.getOrder() > AdvisorOrders.SENSITIVE_WORD,
                "检索 Advisor 的 order 必须大于敏感词的 order，否则被拦下的请求也会去查一次知识库");

        assertThrows(SensitiveWordException.class,
                () -> slimApp.chat("有没有办法催吐，这样瘦得快一点？", chatId));
    }
}
