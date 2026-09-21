package com.purify.purifyaiagent.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@code user} 表的读写。
 *
 * <p>和项目里其他仓储一样是普通类（依赖走构造器，由 {@code AuthConfig} 装配成 Bean），
 * 方便单独 new 出来测。这里只做存取，<b>不做业务判断</b>——密码比对、验证码校验、
 * 事务边界都在 {@code AuthService} 里。这样每个方法都对应一两条 SQL，
 * 出问题只要看是哪条语句。
 *
 * <p><b>表名 {@code user} 是 MySQL 的内置函数名</b>，所有语句里一律加反引号。
 * 不带也能跑，但读代码时容易看错，而且一旦哪天有人写了个列名叫 {@code user}
 * 就真的会出问题。
 *
 * <p><b>登录查询带上 {@code is_deleted = 0}</b>，而 {@link #findByUsernameIncludingDeleted}
 * 是唯一不带这个条件的——理由见那个方法。
 */
@Slf4j
public class UserRepository {

    /**
     * 一次把整行还原成 {@link UserAccount}，不掺方法入参——
     * 这样「这一行读出来是什么」只由数据库决定（同 {@code ChatRecordRepository} 的做法）。
     *
     * <p>{@code status} 存的是 {@code TINYINT}，用 {@code getInt} 读没问题：
     * 它是 {@code NOT NULL DEFAULT 1}，不存在「NULL 被读成 0」的风险。
     * 而 {@code nickname} 这类可空列必须走 {@code getString}，它本来就会把 NULL 读成 null。
     */
    private static final RowMapper<UserAccount> ROW_MAPPER = (resultSet, rowNum) -> new UserAccount(
            resultSet.getLong("id"),
            resultSet.getString("username"),
            resultSet.getString("password"),
            resultSet.getString("nickname"),
            resultSet.getString("avatar"),
            resultSet.getString("email"),
            resultSet.getString("phone"),
            UserRole.parse(resultSet.getString("role")),
            resultSet.getInt("status") == 1,
            resultSet.getInt("is_deleted") == 1,
            resultSet.getObject("last_login_time", LocalDateTime.class));

    /** 查一行的公共列清单。抽出来是为了让三条查询的列顺序和 ROW_MAPPER 永远一致。 */
    private static final String COLUMNS = """
            id, username, password, nickname, avatar, email, phone,
            role, status, is_deleted, last_login_time
            """;

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按用户名查一个<b>可用</b>的账号，供登录使用。
     *
     * <p>这里就过滤掉 {@code is_deleted = 0}，而不是查出来再在 Java 里判：
     * 被删的账号在鉴权链路里根本不该存在，越早挡掉越不容易在某个分支里被漏掉。
     * 而「已删除」和「不存在」对登录来说本来就是同一个结果。
     *
     * <p>不加 {@code status} 条件：被禁用的账号要能查出来才能给用户
     * 「账号已被禁用」这句提示（比「用户名或密码不正确」有用得多）。
     * 放行与否由 {@link UserAccount#canLogin()} 判断。
     */
    public Optional<UserAccount> findForLogin(String username) {
        return queryOne("SELECT " + COLUMNS + " FROM `user` WHERE username = ? AND is_deleted = 0", username);
    }

    /**
     * 按用户名查，<b>不过滤软删</b>。
     *
     * <p>整个系统里只有 {@code RootAgentInitializer} 用它，而它必须看见被软删的行：
     * 如果有人把 {@code root_agent} 删了，而种子是「查不到就插一条新的」，
     * 那就会在唯一索引上撞车（用户名还在，只是 {@code is_deleted = 1}），
     * 启动直接失败。看见了才能走「把它复活」那条路，见那个类的说明。
     */
    public Optional<UserAccount> findByUsernameIncludingDeleted(String username) {
        return queryOne("SELECT " + COLUMNS + " FROM `user` WHERE username = ?", username);
    }

    /**
     * 按邮箱查，供找回密码使用。
     *
     * <p>假定邮箱唯一（{@code uk_user_email}），所以至多一行。**不要**把它改成
     * 「随便取第一行」——重复邮箱意味着「改谁的密码」没有答案，那是个必须被修掉的数据问题，
     * 不是一个可以用 {@code LIMIT 1} 绕过去的查询问题。
     */
    public Optional<UserAccount> findByEmail(String email) {
        return queryOne("SELECT " + COLUMNS + " FROM `user` WHERE email = ? AND is_deleted = 0", email);
    }

    /**
     * 用户名是否已被占用。
     *
     * <p><b>不过滤软删</b>：唯一索引是不管 {@code is_deleted} 的，被软删的用户名照样占着位置。
     * 这里要是过滤了，就会出现「查着没占用、插进去报重复键」这种自相矛盾的表现。
     * 检验的标准永远是「唯一索引怎么说」，不是「我觉得它还在不在」。
     */
    public boolean usernameExists(String username) {
        return count("SELECT COUNT(*) FROM `user` WHERE username = ?", username) > 0;
    }

    /** 邮箱是否已被占用。同样不过滤软删，理由同上。 */
    public boolean emailExists(String email) {
        return count("SELECT COUNT(*) FROM `user` WHERE email = ?", email) > 0;
    }

    /**
     * 插入一个新账号，返回自增主键。
     *
     * <p>{@code password} 传进来时必须已经是 BCrypt 哈希——这个类不碰明文密码，
     * 也不做哈希。哈希是可测试的纯函数，放在 {@code AuthService} 里比藏在这里看得见。
     *
     * <p>用户名或邮箱撞唯一索引时抛 {@link DuplicateKeyException}，<b>不在这里吞掉、
     * 也不转成业务异常</b>：调用方需要它在事务里往上冒，好让整个注册回滚
     * （包括那步「验证码已消费」）。转成别的类型会丢掉这个语义。
     */
    public Long insert(String username, String passwordHash, String email, String nickname, UserRole role) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(connection -> {
            // Statement.RETURN_GENERATED_KEYS：不写它的话 GeneratedKeyHolder 拿不到主键，
            // 表现是插入成功、返回值却是 null
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO `user` (username, password, nickname, email, role, status, is_deleted,
                                        create_time, update_time)
                    VALUES (?, ?, ?, ?, ?, 1, 0, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, nickname);
            statement.setString(4, email);
            statement.setString(5, role.name());
            statement.setTimestamp(6, Timestamp.valueOf(now));
            statement.setTimestamp(7, Timestamp.valueOf(now));
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 改密码。
     *
     * <p><b>不会让已签发的令牌失效</b>——令牌是无状态的，服务端没有吊销名单。
     * 也就是说改完密码后，之前那个令牌在过期前仍然能用。这是本方案的已知取舍，
     * 见 {@code JwtService} 的说明；真要收紧就得缩短有效期或引入吊销名单。
     */
    public void updatePassword(Long userId, String passwordHash) {
        int updated = jdbcTemplate.update(
                "UPDATE `user` SET password = ? WHERE id = ?", passwordHash, userId);
        log.info("[User] 已重置密码：userId={} 影响 {} 行", userId, updated);
    }

    /**
     * 换头像。
     *
     * <p>{@code avatar} 列一直就在表里（{@code auth-schema-mysql.sql} 建表时带的），
     * 只是到今天为止从来没有人写过它——所以这个方法是这条链路上唯一的新增，
     * 不需要任何 DDL 变更。
     *
     * <p>返回影响行数，调用方可以据此判断「这个用户还在不在」。
     */
    public int updateAvatar(Long userId, String avatarUrl) {
        int updated = jdbcTemplate.update(
                "UPDATE `user` SET avatar = ? WHERE id = ?", avatarUrl, userId);
        log.info("[User] 已更新头像：userId={} 影响 {} 行", userId, updated);
        return updated;
    }

    /**
     * 记录这次登录的时间和来源 IP。
     *
     * <p>失败只记日志不上抛：这两个字段是审计信息，不是登录的必要条件。
     * 为了写不上一个时间戳让一次成功的登录变成 500，是把主次搞反了
     * （同 {@code ChatRecordRepository#save} 的取向）。
     */
    public void touchLogin(Long userId, String ip) {
        try {
            jdbcTemplate.update("UPDATE `user` SET last_login_time = ?, last_login_ip = ? WHERE id = ?",
                    Timestamp.valueOf(LocalDateTime.now()), ip, userId);
        }
        catch (RuntimeException exception) {
            log.error("[User] 记录登录信息失败，本次登录不受影响：userId={} 原因={}", userId, exception.getMessage());
        }
    }

    /**
     * 把种子账号恢复成「超级用户且可用」：重置密码、解禁（{@code status = 1}）、
     * 撤销软删（{@code is_deleted = 0}）、<b>并把角色改回 {@code SUPER}</b>。
     *
     * <p>给 {@code RootAgentInitializer} 用。合成一条 UPDATE 而不是四个方法，
     * 是因为这四件事在种子账号上永远要一起做——拆开的话，
     * 「复活了但密码还是旧的」这种中间状态会真实出现。
     *
     * <p><b>角色那一项不能省。</b>种子的意义是「保证系统永远进得去管理端」，
     * 而进不进得去只取决于角色。只把账号解禁、不改角色的话，
     * 一个被降级成 NORMAL 的 {@code root_agent} 会照常登录成功——
     * 然后就卡在那儿，什么都管不了，也没有任何地方能把它改回来。
     */
    public void reviveAsSuper(Long userId, String passwordHash) {
        jdbcTemplate.update("""
                UPDATE `user` SET password = ?, role = 'SUPER', status = 1, is_deleted = 0 WHERE id = ?
                """, passwordHash, userId);
    }

    /** 按 id 查（含软删），供种子和测试使用。 */
    public Optional<UserAccount> findById(Long id) {
        return queryOne("SELECT " + COLUMNS + " FROM `user` WHERE id = ?", id);
    }

    private Optional<UserAccount> queryOne(String sql, Object argument) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, argument));
        }
        catch (EmptyResultDataAccessException exception) {
            // queryForObject 查不到时抛异常而不是返回 null，这是 Spring 的历史包袱。
            // 转成 Optional 让调用方用 ifPresent/empty 表达，比每个调用点都 try 一次清楚
            return Optional.empty();
        }
    }

    private int count(String sql, Object argument) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, argument);
        return value == null ? 0 : value;
    }

    /**
     * 邮箱归一化：小写 + 去空格。**整个系统里只在这一处定义。**
     *
     * <p>{@code A@x.com} 和 {@code a@x.com} 在收信上是同一个地址。不归一的话，
     * 注册时 {@code A@x.com}、找回密码时用户顺手打了 {@code a@x.com}，
     * 两次查的是不同的行，表现是「验证码发了但提示邮箱没注册」——
     * 一个没有任何线索指向根因的故障。
     *
     * <p>写成静态方法是为了让 {@code VerifyCodeRepository} 也调它，
     * 而不是两边各写一遍 {@code trim().toLowerCase()}：那种重复迟早会有一边被漏改。
     */
    public static String normalizeEmail(String email) {
        return StringUtils.hasText(email) ? email.trim().toLowerCase() : null;
    }
}
