package com.purify.purifyaiagent.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 把 {@code user} 表缺的那三样补齐：{@code role} 列、用户名唯一索引、邮箱唯一索引。
 *
 * <p><b>为什么不在建表脚本里写</b>：那个脚本每次启动都会重跑（{@code spring.sql.init}
 * 的 {@code mode: always}），而 MySQL 8 没有 {@code ADD COLUMN IF NOT EXISTS}、
 * 也没有 {@code CREATE INDEX IF NOT EXISTS}。写一句无条件的 {@code ALTER}，
 * 第二次启动就会报「重复列」/「重复键名」，而脚本的执行是 fail-fast 的——
 * 应用从此起不来。全新库其实不受影响（{@code CREATE TABLE} 里已经带上了这三样），
 * 需要补的只有「库已经有一张早先建好的 {@code user} 表」这一种情形，
 * 而那恰恰是当前这台共享 MySQL 的状态。
 *
 * <p>所以判断只能自己做：先查 {@code information_schema} 看有没有，缺了才执行。
 * 这是这个项目里唯一一处运行时 DDL，独立成类而不是塞进 {@code RootAgentInitializer}——
 * 两者的失败含义完全不同：这里失败是「唯一性没保障」，那里失败是「没有超级用户」，
 * 日志上要能一眼分开。
 *
 * <p><b>顺序</b>：{@code @Order(1)}，必须早于 {@code RootAgentInitializer}（{@code @Order(2)}），
 * 因为种子的查询是按用户名查的，而唯一索引是「这个查询至多返回一行」的前提。
 * 两个都是 {@code ApplicationRunner}，在 {@code spring.sql.init} 之后才跑，表一定已经存在。
 *
 * <p>三件事都是**只加不改**：不删任何行、不改任何数据。唯一索引在已经存在重复值的库上
 * 会建失败，那条 {@code ALTER} 会抛异常——这是对的，重复的用户名/邮箱本来就该被人看见，
 * 而不是静默跳过。真要那样，日志里会直接说明该先清理哪一列。
 */
@Slf4j
@Order(1)
public class SchemaGuard implements ApplicationRunner {

    /** 唯一索引名。写死在这里而不是拼接，是为了让日志和 information_schema 里的名字对得上。 */
    private static final String UK_USERNAME = "uk_user_username";
    private static final String UK_EMAIL = "uk_user_email";

    private static final String ROLE_COLUMN = "role";

    private final JdbcTemplate jdbcTemplate;

    public SchemaGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        addRoleColumnIfMissing();
        addUniqueIndexIfMissing(UK_USERNAME, "username");
        addUniqueIndexIfMissing(UK_EMAIL, "email");
    }

    /**
     * 补 {@code role} 列。
     *
     * <p>加在 {@code username} 之后，纯粹是为了和建表脚本里的列顺序一致——
     * 位置对功能和性能都没有影响，但对不上时看表结构会以为漏了什么。
     */
    private void addRoleColumnIfMissing() {
        if (columnExists(ROLE_COLUMN)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE `user` ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'NORMAL'"
                + " COMMENT '角色：SUPER / NORMAL' AFTER username");
        log.info("[SchemaGuard] user 表补上了 role 列，存量账号一律是 NORMAL（超级用户由种子账号承担）");
    }

    /**
     * 补一个唯一索引。
     *
     * <p><b>不加 try/catch 吞掉建索引的失败</b>：唯一索引建不上只有一个原因——
     * 那一列已经有重复值。这时把异常吞掉、让应用带着「没有唯一性」的假设继续跑，
     * 后面每一条依赖它的逻辑（登录按用户名查、找回密码按邮箱查）都会变成不确定的行为，
     * 而且不会报错。让它抛出去、启动失败、日志里带着 MySQL 的原话，
     * 是这个问题唯一能被修掉的方式。
     */
    private void addUniqueIndexIfMissing(String indexName, String column) {
        if (indexExists(indexName)) {
            return;
        }
        try {
            // 表名和列名都是本类里的常量，不是外部输入，不存在注入面；
            // 唯一索引名同理。这里没有能参数化的地方（DDL 不支持 ? 占位符）
            jdbcTemplate.execute("ALTER TABLE `user` ADD UNIQUE KEY " + indexName + " (" + column + ")");
            log.info("[SchemaGuard] user 表补上了唯一索引 {}（{}）", indexName, column);
        }
        catch (RuntimeException exception) {
            log.error("[SchemaGuard] 给 user.{} 建唯一索引失败：{}。"
                            + "最常见的原因是这一列已经存在重复值——请先清理重复行再启动，"
                            + "不能带着「没有唯一性」继续跑：登录和找回密码都假定它是唯一的。"
                            + "查重复：SELECT {} FROM `user` GROUP BY {} HAVING COUNT(*) > 1",
                    column, exception.getMessage(), column, column);
            throw exception;
        }
    }

    /**
     * 这个列在不在。
     *
     * <p><b>库名用 SQL 的 {@code DATABASE()}，不在 Java 里算。</b>它是服务端对
     * 「当前会话连的是哪个库」的权威回答，且随连接走——连接池里的连接可能因为
     * 重连而切到别处，Java 侧缓存下来的那个名字不会跟着变。
     *
     * <p>那为什么不从连接串里解析？**因为这正是本类出过的那个 bug**：
     * 原来用 {@code url.lastIndexOf('/')} 取库名，而连接串里
     * {@code serverTimezone=Asia/Shanghai} 本身带一个斜杠，于是取到的是
     * {@code Shanghai&useSSL=false&...} 这种东西。库名错 → {@code information_schema}
     * 恒为 0 行 → 以为列不存在 → 去 ALTER 一个已经存在的列 →
     * 启动报 {@code Duplicate column name 'role'}，而错误信息完全不指向真正的原因。
     * 库名的合法字符集和 URL 的参数值高度重叠，任何「从 URL 里抠库名」的写法都是脆的。
     */
    private boolean columnExists(String column) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'user' AND column_name = ?
                """, Integer.class, column);
        return count != null && count > 0;
    }

    /** 这个索引在不在。库名的取法同 {@link #columnExists}。 */
    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'user' AND index_name = ?
                """, Integer.class, indexName);
        return count != null && count > 0;
    }
}
