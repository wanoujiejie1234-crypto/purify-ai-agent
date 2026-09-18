package com.purify.purifyaiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepositoryDialect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 对话记忆配置：把聊天记录持久化到本地 MySQL。
 *
 * <p>结构是 Spring AI 的两层设计：
 * <pre>
 *   ChatMemory（窗口策略，决定给模型看多少条历史）
 *        └── ChatMemoryRepository（存储实现，这里指到 MySQL）
 * </pre>
 *
 * <p>关于为什么要手写这两个 Bean，而不是全靠 starter 自动装配：
 * {@code JdbcChatMemoryRepositoryAutoConfiguration} 的声明顺序在
 * {@code ChatMemoryAutoConfiguration} 之后，而两者的 Repository Bean 都标注了
 * {@code @ConditionalOnMissingBean}，自动装配的结果会受加载顺序影响。
 * 在用户自己的 {@code @Configuration} 里显式声明，可以保证「一定走 MySQL，
 * 而不是退化成内存实现」——这一点在重启后记忆是否还在，是最容易暴露的。
 */
@Slf4j
@Configuration
public class ChatMemoryConfig {

    /**
     * 最多保留最近 20 条消息。
     *
     * <p>窗口太大会挤占 token 并稀释当前问题，太小又会丢上下文；
     * 20 条（约 10 轮问答）对健康咨询这类多轮场景比较合适。
     */
    private static final int MAX_MESSAGES = 20;

    @Bean
    public ChatMemoryRepository chatMemoryRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        JdbcChatMemoryRepositoryDialect dialect = JdbcChatMemoryRepositoryDialect.from(dataSource);
        log.info("[ChatMemory] 对话记忆仓储初始化：{}（数据源 {}）",
                dialect.getClass().getSimpleName(), dataSource.getClass().getSimpleName());
        return JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .dialect(dialect)
                .build();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(MAX_MESSAGES)
                .build();
    }
}
