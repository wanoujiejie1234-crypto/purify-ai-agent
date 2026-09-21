package com.purify.purifyaiagent.config;

import com.purify.purifyaiagent.advisor.LoggingAdvisor;
import com.purify.purifyaiagent.advisor.ReReadingAdvisor;
import com.purify.purifyaiagent.advisor.SensitiveWordAdvisor;
import com.purify.purifyaiagent.advisor.SensitiveWordChecker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自定义 Advisor 的装配。
 *
 * <p>Advisor 本身写成普通类（构造注入依赖、方便单元测试），
 * 由这里统一注册成 Bean，再在 {@code SlimApp} 里按需组装进 ChatClient。
 * 这样同一个 Advisor 可以被多个应用复用，也便于按应用裁剪。
 */
@Configuration
public class AdvisorConfig {

    /**
     * 敏感词判据。做成 Bean 而不是在下面那个 Advisor 里 new，是因为
     * {@code PurifyManus} 也要用同一份判据（它不走 Advisor 链），两边必须共用同一个实例。
     */
    @Bean
    public SensitiveWordChecker sensitiveWordChecker(SensitiveWordProperties properties) {
        return new SensitiveWordChecker(
                properties.getWords(),
                properties.getReplyMessage(),
                properties.getReplyMessageEn(),
                properties.isEnabled());
    }

    @Bean
    public SensitiveWordAdvisor sensitiveWordAdvisor(SensitiveWordChecker sensitiveWordChecker) {
        return new SensitiveWordAdvisor(sensitiveWordChecker);
    }

    @Bean
    public ReReadingAdvisor reReadingAdvisor() {
        return new ReReadingAdvisor();
    }

    @Bean
    public LoggingAdvisor loggingAdvisor() {
        return new LoggingAdvisor();
    }
}
