package com.purify.purifyaiagent.advisor;

import com.purify.purifyaiagent.exception.SensitiveWordException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

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

    /**
     * 判据本身。本类只负责「拦下来之后怎么让调用链停下」（抛异常 / {@code Flux.error}），
     * 词表和话术都在 {@link SensitiveWordChecker} 里——智能体那条链路没有 Advisor，
     * 也要用同一份判据，所以不能留在本类里。
     */
    private final SensitiveWordChecker checker;

    private final int order;

    public SensitiveWordAdvisor(SensitiveWordChecker checker) {
        this(checker, AdvisorOrders.SENSITIVE_WORD);
    }

    public SensitiveWordAdvisor(SensitiveWordChecker checker, int order) {
        this.checker = checker;
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String hit = checker.match(lastUserText(request));
        if (hit == null) {
            return chain.nextCall(request);
        }
        log.warn("[SensitiveWordAdvisor] 命中敏感词 [{}]，已拦截本次请求，不调用模型", hit);
        throw new SensitiveWordException(hit, checker.replyMessage());
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String hit = checker.match(lastUserText(request));
        if (hit == null) {
            return chain.nextStream(request);
        }
        log.warn("[SensitiveWordAdvisor] 命中敏感词 [{}]，已拦截本次流式请求，不调用模型", hit);
        return Flux.error(new SensitiveWordException(hit, checker.replyMessage()));
    }

    /** 取 Prompt 中最后一条用户消息的文本。扫描范围与智能体那边共用一份实现，见 Checker 的注释。 */
    private static String lastUserText(ChatClientRequest request) {
        return SensitiveWordChecker.lastUserText(request.prompt().getInstructions());
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
