package com.purify.purifyaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 联网搜索的配置，对应 application-local.yml 中的 {@code searchapi.*}。
 *
 * <p>密钥只放在 application-local.yml（被 gitignore 的 {@code *.local.yml} 挡住），
 * 进仓库的 application.yml 里只留一个空键位，说明「这里该填什么」。
 */
@Data
@Component
@ConfigurationProperties(prefix = "searchapi")
public class SearchApiProperties {

    /** searchapi.io 的 API Key。 */
    private String apiKey;

    public boolean isConfigured() {
        return StringUtils.hasText(apiKey);
    }
}
