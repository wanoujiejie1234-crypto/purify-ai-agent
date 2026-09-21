package com.purify.purifyaiagent.resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * {@code user_resource} 表的读写。
 *
 * <p>和项目里其他仓储一样是普通类（依赖走构造器，由 {@code ResourceConfig} 装配成 Bean），
 * 只做存取、不做业务判断——「谁的资源」「能不能删」都在 controller 那一层。
 *
 * <p>{@code userId} 是**十进制字符串**（{@code user.id} 的字符串形式），
 * 和 {@code user_profile} / {@code chat_session} 保持一致。这里不校验它是不是数字：
 * 老数据里有浏览器 UUID 这种非数字的值，卡死反而会把它们漏掉。
 */
@Slf4j
public class UserResourceRepository {

    /**
     * 一次把整行还原成 {@link UserResource}。
     *
     * <p>可空的列一律走 {@code getObject(..., Class)} 而不是 {@code getLong}/{@code getInt}：
     * 后者会把 SQL NULL 读成 0，于是「大小未知」会变成「0 字节」，
     * 而 0 字节的文件看起来像产出失败了。和 {@code UserProfileRepository} 同一个理由。
     *
     * <p>读不出来的 kind 得到 null 而不是抛异常——库是可以手工改的，
     * 一行坏数据不该让整个列表查不出来。
     */
    private static final RowMapper<UserResource> ROW_MAPPER = (resultSet, rowNum) -> new UserResource(
            resultSet.getLong("id"),
            resultSet.getString("user_id"),
            resultSet.getString("conversation_id"),
            ResourceKind.parse(resultSet.getString("kind")),
            resultSet.getString("title"),
            resultSet.getString("url"),
            resultSet.getString("storage_key"),
            resultSet.getObject("size_bytes", Long.class),
            resultSet.getString("mime_type"),
            resultSet.getString("source_url"),
            resultSet.getObject("created_at", java.time.LocalDateTime.class));

    private static final String COLUMNS = """
            id, user_id, conversation_id, kind, title, url, storage_key,
            size_bytes, mime_type, source_url, created_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public UserResourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记一条产出，返回带 id 的完整记录。
     *
     * <p><b>失败只记日志、不往外抛。</b>调用它的是「生成 PDF 成功」「下载成功」这些
     * 已经做完的动作——归档是附加动作，为了记不上一条账而让用户以为整个操作失败了，
     * 是把主次搞反了（同 {@code ChatRecordRepository#save} 的取向）。
     * 代价是偶尔会漏记一条，用户重做一次就能补上。
     */
    public Optional<UserResource> insert(UserResource resource) {
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO user_resource
                            (user_id, conversation_id, kind, title, url, storage_key,
                             size_bytes, mime_type, source_url, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, resource.userId());
                statement.setString(2, resource.conversationId());
                statement.setString(3, resource.kind() == null ? null : resource.kind().name());
                statement.setString(4, resource.title());
                statement.setString(5, resource.url());
                statement.setString(6, resource.storageKey());
                if (resource.sizeBytes() == null) {
                    statement.setNull(7, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(7, resource.sizeBytes());
                }
                statement.setString(8, resource.mimeType());
                statement.setString(9, resource.sourceUrl());
                statement.setTimestamp(10, Timestamp.valueOf(resource.createdAt()));
                return statement;
            }, keyHolder);

            Number key = keyHolder.getKey();
            if (key == null) {
                return Optional.empty();
            }
            return Optional.of(new UserResource(key.longValue(), resource.userId(),
                    resource.conversationId(), resource.kind(), resource.title(), resource.url(),
                    resource.storageKey(), resource.sizeBytes(), resource.mimeType(),
                    resource.sourceUrl(), resource.createdAt()));
        } catch (RuntimeException exception) {
            log.error("[Resource] 归档失败（不影响本次产出本身）：user={} title={} 原因={}",
                    resource.userId(), resource.title(), exception.getMessage());
            return Optional.empty();
        }
    }

    /** 某个用户的产出，最近的在前。 */
    public List<UserResource> findByUser(String userId, int limit) {
        return jdbcTemplate.query("""
                SELECT %s FROM user_resource
                WHERE user_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """.formatted(COLUMNS), ROW_MAPPER, userId, limit);
    }

    /**
     * 按 id 和归属查一条。
     *
     * <p><b>归属条件写在 SQL 里，不是查出来再比较。</b>分成两步的话，
     * 「查到了但不属于你」这个中间状态会真实存在，而这个状态一旦有人顺手写成
     * 403 而不是 404，就成了一个「这个 id 存不存在」的探测器——
     * 和 {@code chat.SessionAccess} 的处理是同一个理由。
     */
    public Optional<UserResource> findOwned(String userId, long id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT %s FROM user_resource WHERE id = ? AND user_id = ?
                    """.formatted(COLUMNS), ROW_MAPPER, id, userId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    /**
     * 删一条。返回真正删掉的行数。
     *
     * <p>同样带 {@code user_id} 条件：删别人的行和「这条不存在」应该是同一个结果。
     */
    public int deleteOwned(String userId, long id) {
        return jdbcTemplate.update(
                "DELETE FROM user_resource WHERE id = ? AND user_id = ?", id, userId);
    }
}
