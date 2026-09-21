package com.purify.purifyaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 敏感词配置，对应 application.yml 中的 {@code purify.sensitive-word.*}。
 *
 * <p>之所以把词表放到配置里而不是硬编码，是因为不同业务线的高危词表不同，
 * 上线后调整词表不应该需要重新打包。
 */
@Data
@Component
@ConfigurationProperties(prefix = "purify.sensitive-word")
public class SensitiveWordProperties {

    /** 是否开启敏感词拦截。 */
    private boolean enabled = true;

    /** 命中任意一个词即拦截。 */
    private List<String> words = new ArrayList<>();

    /** 拦截后返回给用户的引导话术（中文，也是英文没配时的兜底）。 */
    private String replyMessage = "这个话题我没办法帮你，建议咨询专业医生。";

    /**
     * 英文的引导话术。留空表示沿用 {@link #replyMessage}。
     *
     * <p>为什么不放进 {@code messages*.properties}：它是**产品策略**的一部分，
     * 和上面那份词表配套（改了词、往往也要改话术）。放进资源包的话，改一句话术
     * 就得找到对应的那个键名，而这个词表本身又是配置项——两个地方管一件事更容易漏。
     */
    private String replyMessageEn = "";
}
