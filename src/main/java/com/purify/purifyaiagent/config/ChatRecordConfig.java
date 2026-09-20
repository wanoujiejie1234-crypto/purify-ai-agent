package com.purify.purifyaiagent.config;

import com.purify.purifyaiagent.chat.ChatRecordRepository;
import com.purify.purifyaiagent.chat.ChatSessionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 聊天记录的装配。
 *
 * <p>注入的是容器里的 {@link JdbcTemplate}，也就是 MySQL 那个——pgvector 链路用的
 * JdbcTemplate 刻意没有注册成 Bean（见 {@code PgVectorRagConfig}），所以这里按类型注入
 * 不会拿错，和 {@code ToolConfig#userProfileRepository} 是同一个理由。
 *
 * <p>建表脚本在 {@code db/chat-record-schema-mysql.sql}，由 {@code spring.sql.init} 在启动时执行。
 * 它和 {@code SPRING_AI_CHAT_MEMORY} 的分工写在那个脚本的注释里：
 * 那个是喂给模型的对话记忆，这个是给人查的聊天记录。
 */
@Configuration
public class ChatRecordConfig {

    @Bean
    public ChatRecordRepository chatRecordRepository(JdbcTemplate jdbcTemplate) {
        return new ChatRecordRepository(jdbcTemplate);
    }

    /**
     * 会话列表。和聊天记录用同一个 MySQL JdbcTemplate，但管的是另一张表
     * （{@code chat_session}，脚本在 {@code db/chat-session-schema-mysql.sql}）：
     * 那个存「一轮问答」，这个存「一个会话」。
     */
    @Bean
    public ChatSessionRepository chatSessionRepository(JdbcTemplate jdbcTemplate) {
        return new ChatSessionRepository(jdbcTemplate);
    }
}
