package com.purify.purifyaiagent.app;

import com.purify.purifyaiagent.config.RagProperties;
import com.purify.purifyaiagent.rag.pgvector.PgVectorIndexService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「对话确实走了 RAG」的集成测试。
 *
 * <p>{@link SlimApp} 的对话链路上本来就挂着知识库检索 Advisor
 * （{@code knowledgeBaseAdvisor.ifAvailable(advisors::add)}），所以这个测试不是去验
 * 「有没有接上」，而是验「接上之后真的在起作用」——这两件事的区别在于：
 * Advisor 装上了但检索永远返回空、或者切片根本没进到 Prompt 里，接口照样能答得头头是道，
 * 只是答的全是模型自己的记忆。
 *
 * <p><b>验证手法</b>：往知识库里塞一条<b>现实中不存在</b>的事实（「轻语减脂法」要求每天
 * 摄入 137 克蛋白质），再拿它提问。这个数字模型不可能凭空知道，只有在回答里看到它，
 * 才能说明切片确实被检索出来、并且拼进了发给模型的 Prompt。断言「回答非空」是证明不了这件事的。
 *
 * <p>用 {@code store=pgvector} 跑，是因为只有本地向量库才能在测试里自己灌数据；
 * 百炼那条链路的切片在控制台里，测试没法造。
 *
 * <p>运行前需要：PostgreSQL 可达且已执行 {@code db/pgvector-schema-postgresql.sql}
 * （或 {@code initialize-schema=true} 且账号有建扩展权限）、DashScope api-key 有效。
 * 用例自己造数据、用完自己删，不依赖库里原本有什么。
 */
@Slf4j
@SpringBootTest(properties = "purify.rag.store=pgvector")
class SlimAppRagTest {

    /** 测试文档的来源标识，起得特别一点，方便用完按它删干净。 */
    private static final String TEST_SOURCE = "轻语减脂法-测试文档.md";

    private static final String TEST_CLASSIFICATION = "食物热量";

    /**
     * 编造的数字。真模型不可能知道「轻语减脂法」是什么，更不可能知道它要求 137 克蛋白质——
     * 回答里出现它，唯一的解释就是切片被检索到并拼进了 Prompt。
     */
    private static final String ONLY_IN_KNOWLEDGE_BASE = "137";

    private static final String TEST_CONTENT = """
            # 轻语减脂法

            ## 蛋白质摄入标准

            轻语减脂法规定，执行期间每天的蛋白质摄入量固定为 137 克，不随体重调整。
            这个数字是本方法的硬性要求，与其他减脂方案都不一样。
            """;

    @Resource
    private SlimApp slimApp;

    @Resource
    private PgVectorIndexService pgVectorIndexService;

    @Resource
    private RagProperties ragProperties;

    @Test
    @DisplayName("对话走 RAG：模型答出了只存在于知识库里的数字")
    void chat_answerComesFromKnowledgeBase() {
        assertEquals(RagProperties.Store.PGVECTOR, ragProperties.getStore(),
                "本用例需要本地向量库才能造数据；store 不是 pgvector 的话装配的会是另一条链路");

        String chatId = slimApp.newChatId();

        try {
            // 1. 造一条只存在于知识库里的事实
            pgVectorIndexService.index(TEST_SOURCE,
                    new ByteArrayResource(TEST_CONTENT.getBytes(StandardCharsets.UTF_8)) {
                        @Override
                        public String getFilename() {
                            return TEST_SOURCE;
                        }
                    },
                    TEST_CLASSIFICATION);

            // 2. 走正常对话链路提问。问句里的「蛋白质」会让关键词路由判定为「食物热量」，
            //    于是检索会带上分类过滤，正好命中刚上传的那份文档
            String reply = slimApp.chat("轻语减脂法要求每天摄入多少克蛋白质？", chatId);
            log.info("[chat_answerComesFromKnowledgeBase] chatId={} 回答：{}", chatId, reply);

            assertNotNull(reply, "模型回答不应为 null");
            assertFalse(reply.isBlank(), "模型回答不应为空");
            assertTrue(reply.contains(ONLY_IN_KNOWLEDGE_BASE),
                    "回答里没有出现只存在于知识库里的数字 " + ONLY_IN_KNOWLEDGE_BASE
                            + "，说明这次对话没走 RAG（或检索到了但没拼进 Prompt）。"
                            + "排查顺序：先看启动日志里「知识库=已接入/pgvector」，"
                            + "再看 LoggingAdvisor 打出的完整 Prompt 里有没有「# 知识库」段。实际回答：" + reply);

        } finally {
            // 用完删掉，不往知识库里留测试数据
            pgVectorIndexService.deleteBySource(TEST_SOURCE);
        }
    }
}
