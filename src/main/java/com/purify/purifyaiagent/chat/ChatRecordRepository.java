package com.purify.purifyaiagent.chat;

import com.purify.purifyaiagent.model.ChatRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 聊天记录的读写入口：把一轮问答落到 MySQL 的 {@code chat_record} 表，也能按会话读回来。
 *
 * <p>读是给前端用的：页面刷新之后内存里什么都不剩，要靠它把之前的对话重新画出来。
 * 但读回来的东西<b>不参与对话</b>——「接着上文聊」是各自对话记忆的职责（轻语是 MySQL 记忆表，
 * 智能体是进程内 {@link com.purify.purifyaiagent.agent.AgentMemory}），和这张账本表是两回事。
 *
 * <p>和 {@code UserProfileRepository} 一样是普通类：依赖走构造器，由 {@code ChatRecordConfig}
 * 装配成 Bean，方便单独 new 出来测。
 */
@Slf4j
public class ChatRecordRepository {

    private static final String INSERT_SQL = """
            INSERT INTO chat_record (conversation_id, scene, question, answer, state, steps, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * 攒不出完整记录的列就不选：{@code id} 只是排序用的，不在 {@link ChatRecord} 里。
     *
     * <p>{@code scene} 过滤的占位符数量随入参变化，所以用 {@code %s} 拼出来——
     * 拼进去的只有 {@code ?}，值一律走参数绑定，不构成注入面。
     */
    private static final String SELECT_TEMPLATE = """
            SELECT conversation_id, scene, question, answer, state, steps, created_at
            FROM chat_record
            WHERE conversation_id = ? AND scene IN (%s)
            ORDER BY id ASC
            """;

    /**
     * 一次把整行还原成 {@link ChatRecord}，不掺任何方法入参——
     * 这样「这一行读出来是什么」只由数据库决定，不会因为调用方传了别的值而变。
     */
    private static final RowMapper<ChatRecord> ROW_MAPPER = (resultSet, rowNum) -> new ChatRecord(
            resultSet.getString("conversation_id"),
            ChatScene.valueOf(resultSet.getString("scene")),
            resultSet.getString("question"),
            resultSet.getString("answer"),
            resultSet.getString("state"),
            // steps 对轻语是 NULL，用 getObject 取出来就是 null；走 getInt 会悄悄变成 0，
            // 那就把「这条链路没有步数」和「走了 0 步」混成一样了
            resultSet.getObject("steps", Integer.class),
            resultSet.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public ChatRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记一轮。
     *
     * <p><b>写失败不往外抛。</b>记账是这一次对话的副产品，而对话本身已经完成了——用户已经拿到答复了。
     * 因为记账失败就把一次成功的对话变成 500，是把主次的顺序搞反了。
     * 所以这里只落一条 error 日志，让问题看得见，但不影响用户。
     */
    public void save(ChatRecord record) {
        try {
            jdbcTemplate.update(INSERT_SQL,
                    record.conversationId(),
                    record.scene().name(),
                    record.question(),
                    record.answer(),
                    record.state(),
                    record.steps(),
                    Timestamp.valueOf(record.createdAt()));
        }
        catch (RuntimeException exception) {
            log.error("[ChatRecord] 聊天记录写库失败，本轮对话不受影响：conversationId={} scene={} 原因={}",
                    record.conversationId(), record.scene(), exception.getMessage());
        }
    }

    /**
     * 读回某个会话的聊天记录，按发生顺序（{@code id} 升序）排列——前端拿到的顺序就是当时说话的顺序。
     *
     * <p>{@code scenes} 是「这个入口关心哪几条链路」：轻语要同时看到文本和看图两种记录，
     * 智能体只要自己那一种。传空列表表示不关心任何链路，直接返回空——
     * 这时候拼出来的 {@code IN ()} 是语法错误，必须提前挡掉。
     *
     * <p><b>这里读失败会往外抛，和 {@link #save} 的处理刚好相反。</b>写失败要兜住，因为对话已经完成了；
     * 读失败不能兜，因为「空列表」本身是一个有意义的答案（这个会话还没聊过），
     * 把「数据库连不上」降级成空列表，用户会以为自己之前的对话凭空消失了。
     * 让它抛出去变成 500，前端的报错框至少说的是实话。
     */
    public List<ChatRecord> findByConversation(String conversationId, List<ChatScene> scenes) {
        if (scenes.isEmpty()) {
            return List.of();
        }
        String placeholders = scenes.stream().map(scene -> "?").collect(Collectors.joining(", "));

        Object[] args = new Object[scenes.size() + 1];
        args[0] = conversationId;
        for (int i = 0; i < scenes.size(); i++) {
            args[i + 1] = scenes.get(i).name();
        }
        return jdbcTemplate.query(SELECT_TEMPLATE.formatted(placeholders), ROW_MAPPER, args);
    }
}
