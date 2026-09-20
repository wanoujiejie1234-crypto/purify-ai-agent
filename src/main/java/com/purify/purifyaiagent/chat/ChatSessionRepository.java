package com.purify.purifyaiagent.chat;

import com.purify.purifyaiagent.model.ChatSessionItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话列表的读写：侧边栏的那一份数据。
 *
 * <p>与 {@link ChatRecordRepository} 分工：那个管「一轮问答存一行」（会话的内容），
 * 这个管「这个会话属于谁、叫什么、什么时候聊的」（会话本身）。一张表管一件事。
 *
 * <p>和项目里其他仓储一样写成普通类（依赖走构造器、由 Config 装配成 Bean），
 * 方便单独 new 出来测。
 *
 * <p><b>读写失败的取向与 {@code ChatRecordRepository} 保持一致</b>：
 * {@link #touch} 是对话的副产品，失败只记日志不往外抛（和那边的 {@code save} 一样）；
 * {@link #list} / {@link #rename} / {@link #delete} 是用户主动发起的请求，
 * 失败就该让它抛出去变成 500——把「数据库连不上」降级成「你没有历史会话」，
 * 用户会以为自己之前的对话凭空消失了。
 */
@Slf4j
public class ChatSessionRepository {

    /** 标题最多留多少个字符。列表里一行放不下更长的，而它只是个标签。 */
    private static final int TITLE_LENGTH = 60;

    /** 建会话行。标题只在首次插入时写入，重复对话不覆盖——用户改过的名字不能被下一句话冲掉。 */
    private static final String INSERT_SQL = """
            INSERT INTO chat_session (conversation_id, user_id, entry, title, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at)
            """;

    private static final String SELECT_SQL = """
            SELECT conversation_id, title, created_at, updated_at
            FROM chat_session
            WHERE user_id = ? AND entry = ?
            ORDER BY updated_at DESC
            """;

    private static final String RENAME_SQL = """
            UPDATE chat_session SET title = ?, updated_at = ? WHERE conversation_id = ? AND user_id = ?
            """;

    /**
     * 一次把整行还原成 {@link ChatSessionItem}，不掺方法入参——
     * 这样「这一行读出来是什么」只由数据库决定（同 {@code ChatRecordRepository} 的做法）。
     */
    private static final RowMapper<ChatSessionItem> ROW_MAPPER = (resultSet, rowNum) -> new ChatSessionItem(
            resultSet.getString("conversation_id"),
            resultSet.getString("title"),
            resultSet.getTimestamp("created_at").toLocalDateTime(),
            resultSet.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public ChatSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记一次「这个会话发生过对话」：没有就建、有就更新最近时间。
     *
     * <p>用一条 {@code INSERT ... ON DUPLICATE KEY UPDATE} 而不是「先查再插」：
     * 后者在并发下会两个请求都查不到、都去插，第二个撞主键报错。而这个方法是在
     * 每次对话开始时调用的，本来就可能并发。
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} 只更新时间、<b>不更新标题</b>：
     * 用户手动改过名字之后，下一句话不该把它冲回「首轮提问的截断」。
     *
     * <p><b>写失败不往外抛</b>，理由同 {@code ChatRecordRepository#save}：
     * 记账是这次对话的副产品，而对话本身已经跑起来了。因为记不上会话就让用户
     * 发不出这条消息，是把主次搞反了——大不了这条会话不在侧边栏里出现。
     */
    public void touch(String conversationId, String userId, ChatEntry entry, String firstQuestion) {
        try {
            LocalDateTime now = LocalDateTime.now();
            jdbcTemplate.update(INSERT_SQL,
                    conversationId,
                    userId,
                    entry.name(),
                    titleOf(firstQuestion),
                    Timestamp.valueOf(now),
                    Timestamp.valueOf(now));
        }
        catch (RuntimeException exception) {
            log.error("[ChatSession] 会话行写库失败，本轮对话不受影响：conversationId={} 原因={}",
                    conversationId, exception.getMessage());
        }
    }

    /** 某个用户在某个入口下的全部会话，按最近使用倒序。 */
    public List<ChatSessionItem> list(String userId, ChatEntry entry) {
        return jdbcTemplate.query(SELECT_SQL, ROW_MAPPER, userId, entry.name());
    }

    /**
     * 改名。只能改自己的会话：SQL 里带上 {@code user_id} 条件，
     * 传别人的 conversationId 影响行数为 0，等价于「这个会话不存在」——
     * 不额外报错，免得变成一个「这个 ID 是存在的，只是不属于你」的信息泄露口子。
     *
     * @return 是否真的改到了。false 表示会话不存在或不属于这个用户
     */
    public boolean rename(String conversationId, String userId, String title) {
        int updated = jdbcTemplate.update(RENAME_SQL,
                titleOf(title), Timestamp.valueOf(LocalDateTime.now()), conversationId, userId);
        return updated > 0;
    }

    /**
     * 删除会话：连同它的聊天记录一起。
     *
     * <p>删 {@code chat_record} 是必须的——留着的话，这个会话在侧边栏消失了，
     * 但它的内容还躺在库里，用户以为删干净了。
     *
     * <p><b>不删对话记忆</b>（{@code SPRING_AI_CHAT_MEMORY}），也删不了：
     * 那个仓储由 Spring AI 管，而智能体的记忆干脆在进程内。这没有害处——
     * 前端「新会话」永远生成一个新的 chatId，被删掉的那个 ID 不会再被用到，
     * 记忆读不到也就等于不存在。
     *
     * @return 是否删到了一个会话。false 表示会话不存在或不属于这个用户
     */
    public boolean delete(String conversationId, String userId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM chat_session WHERE conversation_id = ? AND user_id = ?",
                conversationId, userId);
        if (deleted > 0) {
            jdbcTemplate.update("DELETE FROM chat_record WHERE conversation_id = ?", conversationId);
        }
        return deleted > 0;
    }

    /**
     * 从首轮提问里截一个标题出来。
     *
     * <p>换行要先压成空格：用户第一句话常常是粘贴进来的一段多行文本，
     * 原样截断会让侧边栏那一行竖着长出去。
     */
    static String titleOf(String question) {
        if (!StringUtils.hasText(question)) {
            return "新会话";
        }
        String flattened = question.replaceAll("\\s+", " ").trim();
        return flattened.length() <= TITLE_LENGTH
                ? flattened
                : flattened.substring(0, TITLE_LENGTH) + "…";
    }
}
