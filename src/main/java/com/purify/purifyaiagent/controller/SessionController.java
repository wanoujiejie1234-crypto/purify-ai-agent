package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.chat.ChatEntry;
import com.purify.purifyaiagent.chat.ChatSessionRepository;
import com.purify.purifyaiagent.exception.ApiException;
import com.purify.purifyaiagent.model.ChatSessionItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话列表的接口：侧边栏要的那一份数据。两条链路共用这一个 controller。
 *
 * <pre>
 *   GET    /api/sessions?entry=slim        列出这个用户的会话
 *   PUT    /api/sessions/{id}?title=xxx    改名
 *   DELETE /api/sessions/{id}              删除（连同聊天记录）
 * </pre>
 *
 * <p><b>用户是谁</b>由请求头 {@code X-User-Id} 决定。这个项目还没有登录体系，
 * 所以那个值是浏览器生成、存在 localStorage 里的一个 UUID——它能做到「同一个浏览器
 * 的多个会话归到一起」，<b>但做不到真正的隔离</b>：伪造这个头就能看到别人的会话。
 * 这不是一个可以带到生产的方案，接入登录后把这里换成从会话/令牌里取用户 ID 即可，
 * 表结构不用动。
 *
 * <p>不传这个头时退到 {@link #ANONYMOUS}：所有匿名请求共用一个身份，
 * 比返回 400 好——本地用 curl 试接口时不必先编一个 UUID 出来。
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    /** 没有登录体系时的公共身份。口径写在类注释里。 */
    public static final String ANONYMOUS = "anonymous";

    /** 用户标识的请求头名。与响应头 {@code X-Chat-Id} 一样，都走 {@code X-} 前缀。 */
    public static final String USER_ID_HEADER = "X-User-Id";

    private final ChatSessionRepository chatSessionRepository;

    public SessionController(ChatSessionRepository chatSessionRepository) {
        this.chatSessionRepository = chatSessionRepository;
    }

    /**
     * 列出某个入口下、这个用户的全部会话，按最近使用倒序。
     *
     * <p>{@code entry} 必填：轻语和智能体的会话列表互不可见，
     * 不传就不知道要列哪一栏，默认成哪一个都是猜。
     */
    @GetMapping
    public List<ChatSessionItem> list(@RequestParam String entry,
                                      @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        ChatEntry chatEntry = requireEntry(entry);
        List<ChatSessionItem> items = chatSessionRepository.list(resolveUserId(userId), chatEntry);
        log.info("[会话] 列出 user={} entry={} 共 {} 个", userId, chatEntry, items.size());
        return items;
    }

    /**
     * 给会话改名。
     *
     * <p>改名是用户主动做的事，所以这里<b>不兜异常</b>（会话仓储的读写取向见它的类注释）。
     * 会话不存在、或者不属于这个用户时返回 404，而不是静默成功——
     * 前端点了「重命名」什么都没发生、也不报错，是最难查的一种表现。
     */
    @PutMapping("/{conversationId}")
    public ResponseEntity<Void> rename(@PathVariable String conversationId,
                                       @RequestParam String title,
                                       @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        if (!StringUtils.hasText(title)) {
            throw ApiException.invalidChatRequest("标题不能为空");
        }
        boolean renamed = chatSessionRepository.rename(conversationId, resolveUserId(userId), title);
        if (!renamed) {
            return ResponseEntity.notFound().build();
        }
        log.info("[会话] 改名 conversationId={} title={}", conversationId, title);
        return ResponseEntity.ok().build();
    }

    /**
     * 删除会话，连同它的聊天记录。
     *
     * <p><b>删不到也返回 200</b>，而不是 404。理由和 {@code KnowledgeBaseController#delete}
     * 一样：删除天然幂等——客户端重试一次、或者两个标签页同时删同一个会话，
     * 第二次拿到 404 只会让前端弹一个没意义的报错，而它想要的结果其实已经达成了。
     */
    @DeleteMapping("/{conversationId}")
    public void delete(@PathVariable String conversationId,
                       @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        boolean deleted = chatSessionRepository.delete(conversationId, resolveUserId(userId));
        log.info("[会话] 删除 conversationId={} 结果={}", conversationId, deleted ? "已删除" : "不存在");
    }

    private static ChatEntry requireEntry(String entry) {
        ChatEntry parsed = ChatEntry.fromLink(entry);
        if (parsed == null) {
            // 明确报错而不是退到默认入口：一个拼错的 link 如果静默变成「这个入口没有历史」，
            // 前端看到的只是一个空列表，没有任何线索指向「入口名写错了」
            throw ApiException.invalidChatRequest(
                    "未知的入口「" + entry + "」，只支持 " + ChatEntry.SLIM.link() + " 或 " + ChatEntry.MANUS.link());
        }
        return parsed;
    }

    /**
     * 取用户标识，没传就用匿名。
     *
     * <p>没传时打一条 debug 而不是 warn：本地 curl 试接口必然会走到这里，
     * 每次都刷一条警告只会把真正的告警淹掉。
     */
    public static String resolveUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            log.debug("[会话] 请求没带 {}，按 {} 处理", USER_ID_HEADER, ANONYMOUS);
            return ANONYMOUS;
        }
        return userId.trim();
    }
}
