package com.purify.purifyaiagent.advisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ReReadingAdvisor} 的单元测试：不起 Spring、不调模型。
 *
 * <p>重点是「改写文本不能顺手把媒体附件扔掉」。
 * 这个 bug 很隐蔽：模型不会报错，只是收不到图，然后按提示词里的兜底话术回答「看不清」，
 * 从日志上也看不出异常——所以用测试把它钉死。
 */
class ReReadingAdvisorTest {

    @Test
    @DisplayName("改写多模态消息：追加指令的同时保留图片")
    void keepsMediaWhenRewriting() {
        Media image = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] {1, 2, 3}));
        UserMessage userMessage = UserMessage.builder()
                .text("这张图里是什么颜色？")
                .media(image)
                .metadata(Map.of("messageFormat", "image"))
                .build();

        ChatClientRequest rewritten = captureRewritten(new Prompt(List.of(userMessage)));
        UserMessage result = (UserMessage) rewritten.prompt().getInstructions().get(0);

        assertTrue(result.getText().startsWith("这张图里是什么颜色？"), "原文应保留在最前面");
        assertTrue(result.getText().length() > userMessage.getText().length(), "应追加了 Re-Reading 指令");
        assertEquals(1, result.getMedia().size(), "图片被改写弄丢了，模型会看不到图");
        assertSame(image, result.getMedia().get(0), "保留的应该是原来那个 Media 实例");
        // 只断言我们自己塞的那一项：AbstractMessage 会额外往 metadata 里写 messageType
        assertEquals("image", result.getMetadata().get("messageFormat"), "metadata 同样不能丢");
    }

    @Test
    @DisplayName("改写纯文本消息：行为不变")
    void rewritesTextOnlyMessage() {
        UserMessage userMessage = new UserMessage("每天走一万步够吗？");

        ChatClientRequest rewritten = captureRewritten(new Prompt(List.of(userMessage)));
        UserMessage result = (UserMessage) rewritten.prompt().getInstructions().get(0);

        assertTrue(result.getText().startsWith("每天走一万步够吗？"));
        assertTrue(result.getText().length() > userMessage.getText().length(), "应追加了 Re-Reading 指令");
        assertTrue(result.getMedia().isEmpty());
    }

    @Test
    @DisplayName("只改最后一条用户消息，系统消息和历史消息不动")
    void onlyRewritesLastUserMessage() {
        SystemMessage system = new SystemMessage("你是「轻语」。");
        UserMessage history = new UserMessage("我身高 175。");
        UserMessage current = new UserMessage("那我该吃多少蛋白质？");

        ChatClientRequest rewritten = captureRewritten(new Prompt(List.of(system, history, current)));
        List<org.springframework.ai.chat.messages.Message> instructions = rewritten.prompt().getInstructions();

        assertSame(system, instructions.get(0), "系统消息不应被改动");
        assertEquals("我身高 175。", instructions.get(1).getText(), "历史消息不应被追加指令");
        assertTrue(instructions.get(2).getText().length() > current.getText().length(), "最后一条用户消息应被改写");
    }

    /** 跑一次 adviseCall，返回传给下一环的那个 request。 */
    private static ChatClientRequest captureRewritten(Prompt prompt) {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientRequest[] captured = new ChatClientRequest[1];
        when(chain.nextCall(any())).thenAnswer(invocation -> {
            captured[0] = invocation.getArgument(0);
            return mock(ChatClientResponse.class);
        });

        new ReReadingAdvisor().adviseCall(ChatClientRequest.builder().prompt(prompt).build(), chain);

        assertNotNull(captured[0], "Advisor 应当把改写后的请求继续往下传");
        return captured[0];
    }
}
