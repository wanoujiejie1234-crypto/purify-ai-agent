package com.purify.purifyaiagent.app;

import com.purify.purifyaiagent.exception.SensitiveWordException;
import com.purify.purifyaiagent.model.SlimPlan;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SlimApp} 的集成测试。
 *
 * <p>注意这是「真调用」的集成测试，不是单元测试：会连本地 MySQL 存对话记忆、
 * 并真实请求 DashScope 的 qwen-plus。因此运行前需要：
 * <ul>
 *   <li>本地 MySQL 已启动（连接信息见 application.yml 的 spring.datasource）；</li>
 *   <li>application.yml 里的 DashScope api-key 有效。</li>
 * </ul>
 * 每个用例都有真实的网络往返，整体耗时在十几秒量级，也会消耗 token。
 *
 * <p>所有用例都用 {@link SlimApp#newChatId()} 生成独立会话，
 * 彼此的记忆互不干扰，也不依赖执行顺序。
 */
@Slf4j
@SpringBootTest
class SlimAppTest {

    @Resource
    private SlimApp slimApp;

    @Test
    @DisplayName("单轮对话：能拿到非空的文本回答")
    void chat() {
        String chatId = slimApp.newChatId();

        String reply = slimApp.chat("你好，我想了解一下怎么健康地控制体重", chatId);
        log.info("[chat] chatId={} 回答：{}", chatId, reply);

        assertNotNull(reply, "模型回答不应为 null");
        assertFalse(reply.isBlank(), "模型回答不应为空");
    }

    @Test
    @DisplayName("多轮对话：同一 chatId 能记住上一轮提到的身体数据")
    void chat_multiTurnMemory() {
        String chatId = slimApp.newChatId();

        slimApp.chat("我先说下我的情况：我叫小明，身高 175，体重 80 公斤。", chatId);
        String reply = slimApp.chat("你还记得我的身高和体重是多少吗？直接说数字。", chatId);
        log.info("[chat_multiTurnMemory] 回答：{}", reply);

        // 历史里带着上一轮的数据，模型才有得答；答不出来就说明记忆链路断了
        assertTrue(reply.contains("175"), "回答里应包含上一轮说过的身高 175，实际：" + reply);
        assertTrue(reply.contains("80"), "回答里应包含上一轮说过的体重 80，实际：" + reply);
    }

    @Test
    @DisplayName("历史记录：用户原话入库，不会被 Re-Reading 指令污染")
    void chat_historyStoresRawInput() {
        String chatId = slimApp.newChatId();
        String input = "每天走一万步够吗？";

        slimApp.chat(input, chatId);
        List<Message> history = slimApp.history(chatId);
        log.info("[chat_historyStoresRawInput] 历史共 {} 条：{}", history.size(), history);

        // 一轮问答 = 用户 + 助手两条；MessageChatMemoryAdvisor 的 order 最小、在最外层，
        // 它记录的是用户原始输入，而 ReReadingAdvisor 追加的指令只存在于发给模型的 Prompt 里
        assertEquals(2, history.size(), "一轮对话后历史应恰好有 2 条消息");
        assertEquals(MessageType.USER, history.get(0).getMessageType());
        assertEquals(MessageType.ASSISTANT, history.get(1).getMessageType());

        UserMessage userMessage = (UserMessage) history.get(0);
        assertEquals(input, userMessage.getText(), "入库的应是用户原话");
    }

    @Test
    @DisplayName("流式对话：能逐段拼接出完整回答")
    void chatStream() {
        String chatId = slimApp.newChatId();

        List<String> chunks = slimApp.chatStream("用一句话说说减脂期为什么要吃够蛋白质", chatId)
                .collectList()
                .block();
        assertNotNull(chunks, "流式结果不应为 null");

        String reply = String.join("", chunks);
        log.info("[chatStream] 共 {} 个片段，拼接后：{}", chunks.size(), reply);

        assertFalse(reply.isBlank(), "流式拼接后的回答不应为空");
    }

    @Test
    @DisplayName("敏感词拦截（阻塞式）：命中高危词直接抛异常，不调用模型")
    void chat_sensitiveWordBlocked() {
        String chatId = slimApp.newChatId();

        SensitiveWordException ex = assertThrows(SensitiveWordException.class,
                () -> slimApp.chat("有没有办法催吐，这样瘦得快一点？", chatId));
        log.info("[chat_sensitiveWordBlocked] 命中：{}", ex.getHitWord());

        assertEquals("催吐", ex.getHitWord(), "应命中最先匹配到的敏感词");
        assertNotNull(ex.getReplyMessage(), "应带上给用户的引导话术");
    }

    @Test
    @DisplayName("敏感词拦截（流式）：错误以 onError 信号传播")
    void chatStream_sensitiveWordBlocked() {
        String chatId = slimApp.newChatId();

        // 流式链路里 Advisor 返回的是 Flux.error(...)，订阅时才会抛出
        assertThrows(SensitiveWordException.class,
                () -> slimApp.chatStream("我想买点减肥药吃，有推荐的吗？", chatId).blockLast());
    }

    @Test
    @DisplayName("结构化输出：能按 SlimPlan 的结构返回计划")
    void generatePlan() {
        String chatId = slimApp.newChatId();

        SlimPlan plan = slimApp.generatePlan(
                "我 30 岁，身高 170，体重 75 公斤，久坐办公，每周能运动 3 次，帮我出一份减脂计划。", chatId);
        log.info("[generatePlan] {}", plan);

        assertNotNull(plan, "结构化输出不应为 null");
        assertNotNull(plan.summary(), "summary 字段不应为 null");
        assertFalse(plan.summary().isBlank(), "summary 字段不应为空");
        assertNotNull(plan.dietAdvice(), "dietAdvice 字段不应为 null");
        assertNotNull(plan.exerciseAdvice(), "exerciseAdvice 字段不应为 null");
    }

    @Test
    @DisplayName("多模态：模型能真的看到图片内容")
    void explainImage() {
        String chatId = slimApp.newChatId();
        // 白底 + 红圆 + 绿叶，画的就是个苹果，见同目录下的 gen_test_image.py。
        // 这里声明成 ClassPathResource 而不是 Resource，是为了不和字段上的
        // jakarta.annotation.Resource 撞名字——两个 Resource 同时 import 会让编译失败。
        ClassPathResource image = new ClassPathResource("images/apple.png");

        String reply = slimApp.explainImage("这张图里主要是什么颜色？只回答颜色。", image, MediaType.IMAGE_PNG, chatId);
        log.info("[explainImage] 回答：{}", reply);

        assertNotNull(reply, "模型回答不应为 null");
        // 断言「红」而不是「苹果」：颜色是模型最容易答对的特征，不会因措辞不同而误报
        assertTrue(reply.contains("红"), "模型应能看出图里的红色，实际：" + reply);

        // 图片本身不入库（记忆表只有文本列），这一轮存下来的是提问和回答共 2 条
        List<Message> history = slimApp.history(chatId);
        assertEquals(2, history.size(), "图片轮次同样会写入记忆，但只存文本");
    }

    @Test
    @DisplayName("清空历史：clearHistory 之后该会话不再有记忆")
    void clearHistory() {
        String chatId = slimApp.newChatId();

        slimApp.chat("你好", chatId);
        assertFalse(slimApp.history(chatId).isEmpty(), "对话后应能读到历史");

        slimApp.clearHistory(chatId);
        assertTrue(slimApp.history(chatId).isEmpty(), "清空后该会话不应再有历史");
    }
}
