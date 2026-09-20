package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.agent.AgentEvent;
import com.purify.purifyaiagent.agent.PurifyManus;
import com.purify.purifyaiagent.chat.ChatEntry;
import com.purify.purifyaiagent.chat.ChatRecordRepository;
import com.purify.purifyaiagent.chat.ChatSessionRepository;
import com.purify.purifyaiagent.model.ChatHistoryItem;
import com.purify.purifyaiagent.model.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * PurifyManus 的 HTTP 接口，和 {@code /api/slim/*} 是并列的两条链路，互不影响。
 *
 * <pre>
 *   POST /api/manus/chat     跑一次任务（SSE 流式）
 *   GET  /api/manus/history  读回某个会话的聊天记录
 * </pre>
 *
 * <p><b>只有流式一个入口，没有阻塞版。</b>这个智能体的中间过程本身就是它最有价值的部分——
 * 调了哪个工具、工具返回了什么、是不是被看门狗拦下了。跑完再一次性返回的话，用户盯着的就是一个
 * 转了很久的圈，而循环检测这套机制在界面上等于不存在。真要「攒完再看」，前端把 {@code TEXT}
 * 片段拼起来、等 {@code FINAL} 就行，流的形态本身已经把阻塞式包含了。
 *
 * <p><b>和轻语最大的区别是状态</b>：智能体可能跑到一半停下来问用户，
 * 这时流以一个 {@code QUESTION} 事件收尾（{@code state=WAITING_FOR_USER}）。
 * 前端把这条当成一次正常的「回答」展示出来即可，用户接着说一句——带着<b>同一个 chatId</b>——
 * 它就从停下的地方接着跑。「暂停—回答—继续」这个来回靠 chatId 串起来，服务端不留任何等待中的连接。
 *
 * <p>{@code chatId} 不传就新建一个，在响应头 {@code X-Chat-Id} 里返回，所以第一次对话不用先申请 ID。
 * 它挂了哪些工具、记忆里存了什么，看日志就行（启动那行 {@code [PurifyManus] 初始化完成} 会列出全部工具名）。
 */
@Slf4j
@RestController
@RequestMapping("/api/manus")
public class PurifyManusController {

    private final PurifyManus manus;
    private final ChatRecordRepository chatRecordRepository;
    private final ChatSessionRepository chatSessionRepository;

    public PurifyManusController(PurifyManus manus,
                                 ChatRecordRepository chatRecordRepository,
                                 ChatSessionRepository chatSessionRepository) {
        this.manus = manus;
        this.chatRecordRepository = chatRecordRepository;
        this.chatSessionRepository = chatSessionRepository;
    }

    /**
     * 跑一次任务，每一步都实时推给前端。
     *
     * <p>发出来的是结构化事件：{@code STEP}（开始第几步）、{@code TEXT}（模型说的话，逐段到达）、
     * {@code TOOL_CALL} / {@code TOOL_RESULT}（调了什么、拿回什么）、{@code LOOP_SIGNAL}（看门狗介入了）、
     * 以及三种收尾：{@code FINAL}（答完了）、{@code QUESTION}（在等你回答）、{@code ERROR}（出错了）。
     * 事件名就是 type，{@code data} 是一个 JSON 对象。
     *
     * <p>{@code FINAL} 的 text 是权威的完整答复：前端应该用它<b>覆盖</b>累积出来的文本，
     * 而不是往后追加——智能体跑了多步时，中途那些 {@code TEXT} 只是过程，不是最终答案。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<AgentEvent>>> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = SessionController.USER_ID_HEADER, required = false) String userId) {
        String message = request.requireMessage();
        String id = resolveChatId(request.chatId());
        log.info("[manus] chat 请求 chatId={} message={}", id, message);

        // 会话行在这里建/续期，理由同 SlimAppController：只有 HTTP 这层知道用户是谁。
        // 标题只在首次插入时写入，用户改过的名字不会被下一句话冲掉
        chatSessionRepository.touch(id, SessionController.resolveUserId(userId), ChatEntry.MANUS, message);

        // 智能体自己会把模型和工具的错误转成 ERROR 事件；能走到这里的只有传输层的意外
        // （客户端断开、编码失败）。留一条兜底，免得用户看到一个没有事件、也没有结尾的流，
        // 分不清是还在跑还是已经断了
        Flux<AgentEvent> events = manus.chatStream(id, message)
                .onErrorResume(error -> {
                    log.error("[manus] 流式对话失败 chatId={}", id, error);
                    return Flux.just(AgentEvent.error("服务暂时出了点问题，请稍后再试。"));
                });

        return SseEvents.response(id, events);
    }

    /**
     * 读回某个会话的聊天记录，用于刷新页面后把对话重新画出来。
     *
     * <p>每条记录带着它的收尾状态和步数：前端据此可以把「它当时问了你一个问题」
     * 和「它答完了」两种轮次画成不同的样子——这是智能体这条链路独有的信息。
     *
     * <p>会话不存在或还没聊过时返回空数组，这是正常结果，不是错误。
     */
    @GetMapping("/history")
    public List<ChatHistoryItem> history(@RequestParam String chatId) {
        List<ChatHistoryItem> items = chatRecordRepository.findByConversation(chatId, ChatEntry.MANUS.scenes())
                .stream()
                .map(ChatHistoryItem::from)
                .toList();
        log.info("[manus] 读取历史 chatId={} 共 {} 条", chatId, items.size());
        return items;
    }

    private String resolveChatId(String chatId) {
        return (chatId == null || chatId.isBlank()) ? manus.newChatId() : chatId;
    }
}
