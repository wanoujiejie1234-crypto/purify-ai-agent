package com.purify.purifyaiagent.advisor;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** `Locale.getLanguage()` 的两个取值。写成常量免得两处各写一遍字符串。 */
    private static final String LANGUAGE_ZH = "zh";
    private static final String LANGUAGE_EN = "en";

    private final Set<String> sensitiveWords;

    /**
     * 命中之后该说的话，**按语言各存一份**（键是语言标签，如 {@code "zh"} / {@code "en"}）。
     *
     * <p>这句话是要原样展示给用户的，所以它得跟着界面语言走。但它不在
     * {@code messages*.properties} 里——那是**配置项**（{@code purify.sensitive-word.reply-message}），
     * 产品同学要能直接改，改完重启即生效，不该被塞进一个和外键绑死的资源包里。
     * 代价是两份文案各存一处，改的时候要记着两边都改。
     */
    private final Map<String, String> replyMessages;

    /** 一份都没配时的兜底（也用于语言既不是中文也不是英文的情况）。 */
    private final String fallbackReply;

    private final boolean enabled;

    public SensitiveWordChecker(Collection<String> sensitiveWords,
                                String replyMessage,
                                String replyMessageEn,
                                boolean enabled) {
        this.sensitiveWords = new LinkedHashSet<>(sensitiveWords == null ? List.of() : sensitiveWords);
        this.fallbackReply = replyMessage;
        Map<String, String> messages = new LinkedHashMap<>();
        // 英文没配就退回中文那份：宁可让英文用户看到一句中文的引导话术，
        // 也不要让他看到一个空的拒绝（那看起来像服务坏了）
        messages.put(LANGUAGE_ZH, replyMessage);
        messages.put(LANGUAGE_EN, StringUtils.hasText(replyMessageEn) ? replyMessageEn : replyMessage);
        this.replyMessages = Map.copyOf(messages);
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

    /**
     * 命中之后该回给用户的话。轻语把它包进异常，智能体把它当作 run 的终态输出。
     *
     * @param locale 当前请求的语言。两个调用方都**必须显式传**：
     *               轻语那条链路在请求线程上（{@code LocaleContextHolder} 是准的），
     *               智能体那条跑在 Reactor 线程上（只能用带下来的那个）。做成统一签名，
     *               两边就不会各写一套、也就不会「一条链路翻了、另一条没翻」。
     */
    public String replyMessage(Locale locale) {
        return replyMessages.getOrDefault(languageOf(locale), fallbackReply);
    }

    private static String languageOf(Locale locale) {
        return locale == null ? LANGUAGE_ZH : locale.getLanguage();
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
