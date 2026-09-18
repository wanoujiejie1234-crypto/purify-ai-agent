package com.purify.purifyaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 通义千问连接配置。
 *
 * <p>前缀必须是 {@code spring.ai.dashscope}——这是 Spring AI Alibaba 官方认定的前缀，
 * 写成 {@code dashscope} 的话字段一个都绑不上，但也不会报错，属于会静默失效的坑。
 *
 * <p>注意这里只放「连接级」配置。模型名在
 * {@code spring.ai.dashscope.chat.options.model}，超时时间也在 chat.options 下，
 * 不在本前缀内，因此不在这里重复声明。
 */
@Data
@Component
@ConfigurationProperties(prefix = "spring.ai.dashscope")
public class DashScopeProperties {

    private String apiKey;
    private String baseUrl = "https://dashscope.aliyuncs.com";
}
