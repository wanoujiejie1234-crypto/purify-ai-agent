package com.purify.purifyaiagent.config;

import com.purify.purifyaiagent.resource.ResourceRecorder;
import com.purify.purifyaiagent.resource.UserResourceRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 资料库的装配。
 *
 * <p>注入的是容器里的 {@link JdbcTemplate}，也就是 MySQL 那个——pgvector 链路用的
 * JdbcTemplate 刻意没有注册成 Bean（见 {@code PgVectorRagConfig}），
 * 所以这里按类型注入不会拿错。和 {@code ChatRecordConfig}、{@code ToolConfig} 同一个理由。
 *
 * <p>建表脚本在 {@code db/user-resource-schema-mysql.sql}，由 {@code spring.sql.init}
 * 在启动时执行；「为什么不去正则扫回答，而是让工具在产出时自己记一笔」写在那个脚本的注释里。
 *
 * <p>{@link ResourceRecorder} 被三个工具引用（生成 PDF、下载资源、写文件），
 * 所以它必须是容器里唯一那一个 Bean——工具是手工 new 出来的，
 * 在 {@code ToolConfig} 里各 new 一个 recorder 的话，将来它一旦有了状态
 * （比如批量写入的缓冲），三个工具就会各持一份，行为对不上。
 */
@Configuration
public class ResourceConfig {

    @Bean
    public UserResourceRepository userResourceRepository(JdbcTemplate jdbcTemplate) {
        return new UserResourceRepository(jdbcTemplate);
    }

    @Bean
    public ResourceRecorder resourceRecorder(UserResourceRepository userResourceRepository) {
        return new ResourceRecorder(userResourceRepository);
    }
}
