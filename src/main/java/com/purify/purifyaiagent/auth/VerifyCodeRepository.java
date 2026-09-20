package com.purify.purifyaiagent.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@code verify_code} 表的读写。
 *
 * <p>这张表的写法有几处和项目里其他仓储不一样，都是被「验证码」这个场景逼出来的：
 *
 * <ul>
 *   <li>{@link #consume} 是<b>比较并交换</b>（CAS），不是「先查再改」。
 *       两个请求同时用同一个验证码注册时，CAS 保证只有一个能拿到 1；</li>
 *   <li>三处和「次数/时效」有关的判断（重发限流、尝试上限、过期）都在这里出结果，
 *       但<b>怎么处置由 {@code VerifyCodeService} 决定</b>——仓储只回答
 *       「最新那条是什么」和「我有没有成功地把它消费掉」；</li>
 *   <li>邮箱在这里统一归一化（调 {@link UserRepository#normalizeEmail}），
 *       因为写入和查询必须用同一个口径，分两处写迟早会漂移。</li>
 * </ul>
 *
 * <p>短信/邮箱验证码这类数据的标准归宿是 Redis（带 TTL 天然过期）。这个项目没有 Redis，
 * 为一张几十行的表引一个中间件不划算，所以用 MySQL + 手动清理——
 * 清理挂在发送流程里（见 {@code VerifyCodeService}），不引定时任务。
 */
@Slf4j
public class VerifyCodeRepository {

    private static final RowMapper<StoredCode> ROW_MAPPER = (resultSet, rowNum) -> new StoredCode(
            resultSet.getLong("id"),
            resultSet.getString("code"),
            resultSet.getObject("expires_at", LocalDateTime.class),
            resultSet.getInt("attempts"),
            resultSet.getInt("used") == 1,
            resultSet.getObject("created_at", LocalDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public VerifyCodeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 取某个 (邮箱, 用途) 下最新的一条。
     *
     * <p>按 id 倒序而不是 created_at：{@code DATETIME(3)} 在同毫秒内插入两条时无法区分先后，
     * 而 id 是严格递增的。这也是建表时把 id 放进索引的原因——这个查询每次发送和校验都要跑。
     *
     * <p><b>不用过滤 {@code used} 或 {@code expires_at}</b>：调用方需要看见「上一条已经用过了」
     * 和「上一条过期了」这两件事，它们对应的提示是不一样的（「请重新获取」/「验证码已过期」）。
     * 一查就过滤掉的话，两种情况都变成「没有验证码」，用户不知道自己该干什么。
     */
    public Optional<StoredCode> findLatest(String email, VerificationPurpose purpose) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT id, code, expires_at, attempts, used, created_at
                      FROM verify_code
                     WHERE email = ? AND purpose = ?
                     ORDER BY id DESC
                     LIMIT 1
                    """, ROW_MAPPER, UserRepository.normalizeEmail(email), purpose.name()));
        }
        catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    /**
     * 写入一条新验证码，并<b>删掉同一 (邮箱, 用途) 下的旧码</b>。
     *
     * <p>「重发即作废」这个语义就实现在这里，而且必须是删除而不是标记：
     * 留着旧行的话，{@link #findLatest} 取到的是新的那条，看起来也对，
     * 但库里会一直堆着历史验证码——每一条都还是一个可以用的密码。
     * 删掉才是真正的作废。
     *
     * <p>两步之间没有事务：并发重发时最坏的结果是留下一条多余的旧码，
     * 而它是旧码，用户手里的是新码，不影响正确性。
     */
    public void replace(String email, VerificationPurpose purpose, String code, LocalDateTime expiresAt) {
        String normalized = UserRepository.normalizeEmail(email);
        jdbcTemplate.update("DELETE FROM verify_code WHERE email = ? AND purpose = ?",
                normalized, purpose.name());
        jdbcTemplate.update("""
                INSERT INTO verify_code (email, purpose, code, expires_at, attempts, used, created_at)
                VALUES (?, ?, ?, ?, 0, 0, ?)
                """, normalized, purpose.name(), code, Timestamp.valueOf(expiresAt),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * 删掉某个 (邮箱, 用途) 下的验证码。给「信没发出去」的收尾用。
     *
     * <p>和 {@link #replace} 开头那句 DELETE 是同一个动作，但**不能合并**：
     * 那句的语义是「重发即作废」，这句的语义是「这次发送整个没发生」。
     * 合起来会让调用方得先插一条再删一条，读起来完全看不出在做什么。
     */
    public void removeLatest(String email, VerificationPurpose purpose) {
        jdbcTemplate.update("DELETE FROM verify_code WHERE email = ? AND purpose = ?",
                UserRepository.normalizeEmail(email), purpose.name());
    }

    /**
     * 把某条验证码的尝试次数 +1。返回加完之后的值。
     *
     * <p>自增交给 SQL 的 {@code attempts = attempts + 1}，而不是「读出来、加一、写回去」：
     * 后者在并发下会丢计数，而计数丢了就等于没有尝试上限——
     * 那是防爆破三条防线里唯一一条能挡住「慢慢试」的。
     *
     * <p>自增之后的读回值在并发下可能偏大（另一个请求刚好也加了），
     * 这只影响「还能试几次」这句提示的准确性，不影响放行判断本身。
     */
    public int incrementAttempts(long id) {
        jdbcTemplate.update("UPDATE verify_code SET attempts = attempts + 1 WHERE id = ?", id);
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT attempts FROM verify_code WHERE id = ?", Integer.class, id);
        return attempts == null ? 0 : attempts;
    }

    /**
     * 消费掉一条验证码，返回是否消费成功。
     *
     * <p><b>这是整个类里最关键的一句话。</b>条件里的 {@code used = 0} 让它变成一次 CAS：
     * 两个请求同时拿着同一个验证码注册，只有一个能拿到影响行数 1，另一个拿到 0
     * 从而知道自己来晚了。换成「先查 used 再 UPDATE」的写法，两个请求会都查到 0、
     * 都去更新、都认为自己成功了——一个验证码注册出两个账号。
     *
     * @return true 表示这次是我消费掉的；false 表示它已经被用过了
     */
    public boolean consume(long id) {
        int updated = jdbcTemplate.update(
                "UPDATE verify_code SET used = 1 WHERE id = ? AND used = 0", id);
        return updated > 0;
    }

    /**
     * 清理过期一天以上的记录。
     *
     * <p>留一天而不是立刻删：刚过期的码在用户重试时还能给出「验证码已过期」这句准确的提示，
     * 立刻删掉的话就只剩「没有验证码」，用户会以为是自己没收到。
     *
     * <p>挂在发送流程里顺手做，不引 {@code @Scheduled}：为了删几行数据引入一个定时线程池
     * 不划算，而这张表的写入量本来就是「每个用户每分钟最多一条」这个量级。
     * 清理失败只记日志——它是维护动作，不该让一次正常的发送失败。
     */
    public void deleteExpired() {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM verify_code WHERE expires_at < NOW() - INTERVAL 1 DAY");
            if (deleted > 0) {
                log.debug("[VerifyCode] 清理了 {} 条过期记录", deleted);
            }
        }
        catch (RuntimeException exception) {
            log.warn("[VerifyCode] 清理过期记录失败（不影响本次发送）：{}", exception.getMessage());
        }
    }

    /**
     * 库里的一条验证码。
     *
     * @param used 是否已被消费
     */
    public record StoredCode(long id,
                             String code,
                             LocalDateTime expiresAt,
                             int attempts,
                             boolean used,
                             LocalDateTime createdAt) {

        /** 是不是已经过期了。时间比较放在这里，免得每个调用点自己 {@code isBefore(now)}。 */
        public boolean isExpired() {
            return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
        }
    }
}
