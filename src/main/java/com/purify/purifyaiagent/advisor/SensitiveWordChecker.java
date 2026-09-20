package com.purify.purifyaiagent.advisor;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 敏感词判据：词表 + 话术 + 「这句话命中了吗」。
 *
 * <p>从 {@link SensitiveWordAdvisor} 里提出来，因为现在有<b>两个</b>调用方：
 * 轻语的 Advisor 链，和 {@code PurifyManus} 的开场检查（智能体那条链路直接调
 * {@code ChatModel}，不走 ChatClient，也就没有任何 Advisor，只能自己查一次）。
 *
 * <p>判据只该有一份。两份拷贝一旦漂移，表现是「同一句话在轻语被拦下、在智能体那里放行了」——
 * 这类不一致不报任何错、不影响任何测试，只能靠人比对两处代码，是最难发现的一种漏洞。
 * 话术也存在这里而不是留在 Advisor 里：它是和词表配套的产品策略，
 * 分开放迟早出现「词改了、话术没改」。
 *
 * <p><b>只扫用户输入。</b>工具返回的内容（抓回的网页、搜索摘要）里出现「减肥药」「厌食」
 * 是常事，扫了会把一次正常查询变成误拦；模型输出也不扫（轻语同样没扫）。
 * 这条边界两边必须一致，否则又是一处会漂移的地方。
 */
public class SensitiveWordChecker {

    private final Set<String> sensitiveWords;

    private final String replyMessage;

    private final boolean enabled;

    public SensitiveWordChecker(Collection<String> sensitiveWords, String replyMessage, boolean enabled) {
        this.sensitiveWords = new LinkedHashSet<>(sensitiveWords == null ? List.of() : sensitiveWords);
        this.replyMessage = replyMessage;
        this.enabled = enabled;
    }

    /**
     * 检查一段文本。
     *
     * @return 命中的那个敏感词；没命中、没开开关、文本为空都返回 {@code null}
     */
    public String match(String text) {
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

    /** 命中之后该回给用户的话。轻语把它包进异常，智能体把它当作 run 的终态输出。 */
    public String replyMessage() {
        return replyMessage;
    }

    /**
     * 取消息列表里最后一条用户消息的文本。
     *
     * <p>两种入参的形态是一样的（都是 {@code List<Message>}），所以放在这里共用：
     * 轻语传 {@code request.prompt().getInstructions()}，智能体传 {@code run.messages()}。
     * 各自实现一遍的话，「取最后一条」这个约定就有两份，而它正是两个入口扫描范围一致的前提。
     *
     * @return 最后一条用户消息的文本；一条都没有时返回 {@code null}
     */
    public static String lastUserText(List<Message> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage userMessage) {
                return userMessage.getText();
            }
        }
        return null;
    }
}
