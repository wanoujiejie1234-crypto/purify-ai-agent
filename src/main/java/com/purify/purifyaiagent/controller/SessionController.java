package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.auth.AuthInterceptor;
import com.purify.purifyaiagent.auth.CurrentUser;
import com.purify.purifyaiagent.auth.LoginUser;
import com.purify.purifyaiagent.auth.RequireLogin;
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
 * <p><b>用户是谁</b>由令牌决定：{@code Authorization} 头里的 JWT 经
 * {@link AuthInterceptor} 解出 {@link LoginUser}，再通过 {@code @CurrentUser} 参数注入进来。
 * 这四个接口都标了 {@link RequireLogin}，没有令牌一律 401。
 *
 * <p>接入登录之前，用户标识来自请求头 {@code X-User-Id}（浏览器自己生成的一个 UUID）。
 * 那套东西<b>整个删掉了</b>，包括兜底用的 {@code anonymous}：留着就是一条彻底的鉴权绕过——
 * 任何人发一个 {@code X-User-Id: 5} 就能读到 5 号用户的会话列表。
 * 「本地 curl 试接口时省一步」这点便利，不值得换来一个这样的后门。
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions")
@RequireLogin
public class SessionController {

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
    public List<ChatSessionItem> list(@RequestParam String entry, @CurrentUser LoginUser me) {
        ChatEntry chatEntry = requireEntry(entry);
        List<ChatSessionItem> items = chatSessionRepository.list(me.id(), chatEntry);
        log.info("[会话] 列出 user={} entry={} 共 {} 个", me.describe(), chatEntry, items.size());
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
                                       @CurrentUser LoginUser me) {
        if (!StringUtils.hasText(title)) {
            throw ApiException.invalidChatRequest("error.session.titleRequired");
        }
        boolean renamed = chatSessionRepository.rename(conversationId, me.id(), title);
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
    public void delete(@PathVariable String conversationId, @CurrentUser LoginUser me) {
        boolean deleted = chatSessionRepository.delete(conversationId, me.id());
        log.info("[会话] 删除 conversationId={} 操作者={} 结果={}",
                conversationId, me.describe(), deleted ? "已删除" : "不存在");
    }

    private static ChatEntry requireEntry(String entry) {
        ChatEntry parsed = ChatEntry.fromLink(entry);
        if (parsed == null) {
            // 明确报错而不是退到默认入口：一个拼错的 link 如果静默变成「这个入口没有历史」，
            // 前端看到的只是一个空列表，没有任何线索指向「入口名写错了」
            throw ApiException.invalidChatRequest("error.session.unknownEntry", entry, ChatEntry.SLIM.link(), ChatEntry.MANUS.link());
        }
        return parsed;
    }
}
