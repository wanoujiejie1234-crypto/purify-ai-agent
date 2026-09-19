package com.purify.purifyaiagent.profile;

import com.purify.purifyaiagent.model.ActivityLevel;
import com.purify.purifyaiagent.model.UserProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 用户画像的读写，落在 MySQL 的 {@code user_profile} / {@code user_profile_weight_history} 两张表。
 *
 * <p>这里只做存取，<b>不做业务判断</b>：合并新旧画像、判断体重有没有变化该不该记一笔，
 * 都在 {@code UserProfileTool} 里。这样这个类里的每个方法都对应一条 SQL，
 * 出问题只要看是哪条语句，不用顺着一堆 if 去猜。
 *
 * <p>SQL 用到的表由 {@code spring.sql.init} 在启动时按
 * {@code db/user-profile-schema-mysql.sql} 建好，所以这里不做任何建表或存在性检查。
 */
@Slf4j
public class UserProfileRepository {

    /**
     * 画像里最多带几条体重流水。
     *
     * <p>10 条够看出趋势了。带全部流水没意义：模型要的是「在降还是在涨」，
     * 不是每一笔明细，而明细每多一条就多占一份 token。
     */
    private static final int HISTORY_LIMIT = 10;

    private final JdbcTemplate jdbcTemplate;

    public UserProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 读画像。没建过档的用户返回 {@link Optional#empty()}，而不是一份空画像——两者含义不同。 */
    public Optional<UserProfile> find(String userId) {
        List<UserProfile> found = jdbcTemplate.query("""
                SELECT age, height_cm, weight_kg, goal, activity_level,
                       diet_preference, avoid_food, updated_at
                  FROM user_profile
                 WHERE user_id = ?
                """, (resultSet, rowNum) -> new UserProfile(
                // 这几列都可空，所以不能用 getInt / getDouble——它们把 NULL 读成 0，
                // 于是「没填身高」会变成「身高 0」，再往下 BMI 就废了
                resultSet.getObject("age", Integer.class),
                resultSet.getObject("height_cm", Double.class),
                resultSet.getObject("weight_kg", Double.class),
                resultSet.getString("goal"),
                ActivityLevel.parse(resultSet.getString("activity_level")),
                resultSet.getString("diet_preference"),
                resultSet.getString("avoid_food"),
                resultSet.getObject("updated_at", LocalDateTime.class)), userId);

        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * 整行写入画像，没有就新建。
     *
     * <p>用「{@code INSERT IGNORE} 占位 + {@code UPDATE} 赋值」两步，而不是
     * {@code INSERT ... ON DUPLICATE KEY UPDATE}：后者要靠 {@code VALUES()} 引用新值，
     * 而 {@code VALUES()} 在 MySQL 8.0.20 之后已经标记废弃，每次执行都往日志里吐一条警告；
     * 换成新的行别名写法又要求 8.0.19 以上。两步走没有版本要求，也不产生警告。
     *
     * <p>{@code INSERT IGNORE} 在这里只保证「这一行存在」，业务字段全交给 {@code UPDATE}——
     * 于是不管用户是第一次建档还是第 N 次更新，走的都是同一条语句。
     *
     * <p>两步之间没有事务：并发写同一个用户时理论上有极小概率互相覆盖，
     * 但这个场景是「一个人跟自己的一个会话说话」，不值得为它引入事务。
     *
     * @param profile 已经合并好的完整画像，不是增量
     */
    public void save(String userId, UserProfile profile) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT IGNORE INTO user_profile (user_id, created_at, updated_at) VALUES (?, ?, ?)",
                userId, now, now);

        int updated = jdbcTemplate.update("""
                UPDATE user_profile
                   SET age = ?, height_cm = ?, weight_kg = ?, goal = ?,
                       activity_level = ?, diet_preference = ?, avoid_food = ?, updated_at = ?
                 WHERE user_id = ?
                """,
                profile.age(),
                profile.heightCm(),
                profile.weightKg(),
                profile.goal(),
                profile.activityLevel() == null ? null : profile.activityLevel().name(),
                profile.dietPreference(),
                profile.avoidFood(),
                now,
                userId);
        log.debug("[UserProfile] 已写入画像：userId={} 影响 {} 行", userId, updated);
    }

    /** 读体重流水，新的在前。 */
    public List<UserProfile.WeightRecord> findWeightHistory(String userId) {
        return jdbcTemplate.query("""
                SELECT weight_kg, recorded_at
                  FROM user_profile_weight_history
                 WHERE user_id = ?
                 ORDER BY recorded_at DESC, id DESC
                 LIMIT ?
                """, (resultSet, rowNum) -> new UserProfile.WeightRecord(
                resultSet.getDouble("weight_kg"),
                resultSet.getObject("recorded_at", LocalDateTime.class)), userId, HISTORY_LIMIT);
    }

    /**
     * 往体重流水里追加一条。
     *
     * <p>这张表只增不改：删掉中间某一条，趋势就断了，也说不清当初记的是多少。
     * 「该不该记」由调用方判断，这里不查重。
     */
    public void appendWeight(String userId, double weightKg, LocalDateTime recordedAt) {
        jdbcTemplate.update("""
                INSERT INTO user_profile_weight_history (user_id, weight_kg, recorded_at)
                VALUES (?, ?, ?)
                """, userId, weightKg, recordedAt);
    }

    /**
     * 删掉某个用户的画像和全部流水。
     *
     * <p>给「忘记我」这类需求留的口子，同时也是测试用例的收尾手段：
     * 用例自己造的数据自己删，不给正式库留垃圾。
     */
    public void delete(String userId) {
        int profiles = jdbcTemplate.update("DELETE FROM user_profile WHERE user_id = ?", userId);
        int weights = jdbcTemplate.update("DELETE FROM user_profile_weight_history WHERE user_id = ?", userId);
        log.info("[UserProfile] 已删除用户数据：userId={} 画像 {} 行、体重流水 {} 行", userId, profiles, weights);
    }
}
