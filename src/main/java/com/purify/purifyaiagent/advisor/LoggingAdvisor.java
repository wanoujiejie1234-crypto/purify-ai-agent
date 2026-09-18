package com.purify.purifyaiagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.stream.Collectors;

/**
 * 自定义日志 Advisor：记录每次模型调用的会话 ID、完整 Prompt、响应内容与耗时。
 *
 * <p>内置的 {@code SimpleLoggerAdvisor} 只能打印固定格式，这里自定义的目的是：
 * <ul>
 *   <li>把 conversationId 一并打出来，方便排查「记忆串会话」问题；</li>
 *   <li>同时覆盖阻塞（{@link CallAdvisor}）与流式（{@link StreamAdvisor}）两条链路；</li>
 *   <li>流式场景在流结束时输出一次汇总，避免刷屏。</li>
 * </ul>
 *
 * <p>注意：本类实现的是 1.0.0 的 {@link CallAdvisor} / {@link StreamAdvisor} 接口，
 * 而不是已废弃的 {@code BaseAdvisor}。
 */
@Slf4j
public class LoggingAdvisor implements CallAdvisor, StreamAdvisor {

    /** 日志里单条消息的最大长度，防止超长 Prompt 刷爆日志。 */
    private static final int MAX_TEXT_LENGTH = 500;

    private final int order;

    public LoggingAdvisor() {
        this(AdvisorOrders.LOGGING);
    }

    public LoggingAdvisor(int order) {
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String conversationId = conversationId(request);
        long start = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug("[LoggingAdvisor] >>> 发起调用 | conversationId={} | prompt={}",
                    conversationId, describePrompt(request));
        }

        ChatClientResponse response = chain.nextCall(request);

        log.info("[LoggingAdvisor] <<< 调用完成 | conversationId={} | 耗时={}ms | reply={}",
                conversationId, System.currentTimeMillis() - start, text(response));
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String conversationId = conversationId(request);
        long start = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug("[LoggingAdvisor] >>> 发起流式调用 | conversationId={} | prompt={}",
                    conversationId, describePrompt(request));
        }

        // 流式响应是分片返回的，这里把分片拼起来，等流结束再打一条汇总日志
        StringBuilder reply = new StringBuilder();
        return chain.nextStream(request)
                .doOnNext(response -> reply.append(text(response)))
                .doOnComplete(() -> log.info("[LoggingAdvisor] <<< 流式完成 | conversationId={} | 耗时={}ms | reply={}",
                        conversationId, System.currentTimeMillis() - start, reply))
                .doOnError(error -> log.warn("[LoggingAdvisor] <<< 流式异常 | conversationId={} | 耗时={}ms | error={}",
                        conversationId, System.currentTimeMillis() - start, error.getMessage()));
    }

    @Override
    public String getName() {
        return "LoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    /**
     * 从上下文取会话 ID。这里的 key 就是 {@link ChatMemory#CONVERSATION_ID}，
     * 由调用方通过 {@code .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))} 传入。
     */
    private static String conversationId(ChatClientRequest request) {
        Object value = request.context().get(ChatMemory.CONVERSATION_ID);
        return value == null ? ChatMemory.DEFAULT_CONVERSATION_ID : value.toString();
    }

    private static String describePrompt(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .map(message -> message.getMessageType() + ": " + abbreviate(message.getText()))
                .collect(Collectors.joining(" || "));
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH) + "...(已截断)";
    }

    /** 安全地从响应里取出文本，兼容空响应、工具调用等没有文本内容的情况。 */
    private static String text(ChatClientResponse response) {
        if (response == null) {
            return "";
        }
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            return "";
        }
        Generation generation = chatResponse.getResult();
        if (generation == null || generation.getOutput() == null) {
            return "";
        }
        String text = generation.getOutput().getText();
        return text == null ? "" : text;
    }
}
