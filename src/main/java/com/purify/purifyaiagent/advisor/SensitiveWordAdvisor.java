package com.purify.purifyaiagent.advisor;

import com.purify.purifyaiagent.exception.SensitiveWordException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 敏感词拦截 Advisor：命中敏感词时直接抛出异常，不再调用模型。
 *
 * <p>健康瘦身场景下用户可能提到催吐、厌食、减肥药等高风险话题，
 * 这类问题不应该交给模型自由发挥，而应该由应用层直接兜底引导到专业医疗渠道。
 *
 * <p>实现要点：只校验最后一条用户消息后短路返回（抛异常），
 * 属于「拦截型」Advisor，与只改请求的 {@link ReReadingAdvisor} 形成对照。
 * 流式链路不直接抛异常，而是返回 {@code Flux.error(...)}，
 * 保证无论链条何时被订阅，错误都能以 onError 信号正常传播。
 */
@Slf4j
public class SensitiveWordAdvisor implements CallAdvisor, StreamAdvisor {

    private final Set<String> sensitiveWords;
    private final String replyMessage;
    private final boolean enabled;
    private final int order;

    public SensitiveWordAdvisor(Collection<String> sensitiveWords, String replyMessage, boolean enabled) {
        this(sensitiveWords, replyMessage, enabled, AdvisorOrders.SENSITIVE_WORD);
    }

    public SensitiveWordAdvisor(Collection<String> sensitiveWords, String replyMessage, boolean enabled, int order) {
        this.sensitiveWords = new LinkedHashSet<>(sensitiveWords == null ? List.of() : sensitiveWords);
        this.replyMessage = replyMessage;
        this.enabled = enabled;
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String hit = match(lastUserText(request));
        if (hit == null) {
            return chain.nextCall(request);
        }
        log.warn("[SensitiveWordAdvisor] 命中敏感词 [{}]，已拦截本次请求，不调用模型", hit);
        throw new SensitiveWordException(hit, replyMessage);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String hit = match(lastUserText(request));
        if (hit == null) {
            return chain.nextStream(request);
        }
        log.warn("[SensitiveWordAdvisor] 命中敏感词 [{}]，已拦截本次流式请求，不调用模型", hit);
        return Flux.error(new SensitiveWordException(hit, replyMessage));
    }

    /** 返回命中的敏感词，没有命中返回 null。 */
    private String match(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return null;
        }
        for (String word : sensitiveWords) {
            if (text.contains(word)) {
                return word;
            }
        }
        return null;
    }

    /** 取 Prompt 中最后一条用户消息的文本。 */
    private static String lastUserText(ChatClientRequest request) {
        List<Message> instructions = request.prompt().getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i) instanceof UserMessage userMessage) {
                return userMessage.getText();
            }
        }
        return null;
    }

    @Override
    public String getName() {
        return "SensitiveWordAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }
}
