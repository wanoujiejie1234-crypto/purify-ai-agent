package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.agent.AgentEvent;
import com.purify.purifyaiagent.app.SlimApp;
import com.purify.purifyaiagent.chat.ChatEntry;
import com.purify.purifyaiagent.chat.ChatRecordRepository;
import com.purify.purifyaiagent.chat.ChatSessionRepository;
import com.purify.purifyaiagent.exception.ApiException;
import com.purify.purifyaiagent.exception.SensitiveWordException;
import com.purify.purifyaiagent.model.ChatHistoryItem;
import com.purify.purifyaiagent.model.ChatReply;
import com.purify.purifyaiagent.model.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 轻语的 HTTP 接口，和 {@code /api/manus/*} 是并列的两条链路，互不影响。
 *
 * <pre>
 *   POST /api/slim/chat     对话（SSE 流式）
 *   GET  /api/slim/history  读回某个会话的聊天记录
 *   POST /api/slim/image    看图解读（multipart）
 * </pre>
 *
 * <p><b>为什么对话用 POST + SSE 而不是 GET + EventSource。</b>
 * 一是 {@code message} 是用户随手打的一段话，走 URL 要先编码、长了会撞上长度上限；
 * 二是浏览器的 {@code EventSource} 在连接断开时会自动重连，而重连等于把同一句话再问一遍——
 * 智能体那边更严重，那是一次完整的任务重跑，白烧 token 还可能把工具重放一遍。
 * 所以前端的正确姿势是 {@code fetch} + 读 {@code response.body}，发送一次就是一次。
 *
 * <p>会话 ID 放在响应头 {@code X-Chat-Id} 里，客户端不传 {@code chatId} 时由服务端生成。
 * 注意跨域调用时浏览器默认读不到这个头，需要在 CORS 里放行它（{@code Access-Control-Expose-Headers}）；
 * 前端如果用同源的开发代理，就没有这个问题。
 */
@Slf4j
@RestController
@RequestMapping("/api/slim")
public class SlimAppController {

    private final SlimApp slimApp;
    private final ChatRecordRepository chatRecordRepository;
    private final ChatSessionRepository chatSessionRepository;

    public SlimAppController(SlimApp slimApp,
                             ChatRecordRepository chatRecordRepository,
                             ChatSessionRepository chatSessionRepository) {
        this.slimApp = slimApp;
        this.chatRecordRepository = chatRecordRepository;
        this.chatSessionRepository = chatSessionRepository;
    }

    /**
     * 多轮对话，逐段推给前端。
     *
     * <p>事件的含义见 {@link AgentEvent}：轻语这条链路只会发出 {@code TEXT}（正文片段）
     * 和收尾的一个 {@code FINAL}（完整答复）或 {@code ERROR}。事件种类比智能体少，
     * 但<b>帧结构和事件名完全一致</b>，前端一套解析代码就能同时跑通两条链路。
     *
     * <p>{@code FINAL} 的 text 是权威的完整答复：前端应该用它<b>覆盖</b>累积出来的文本，
     * 而不是往后追加，否则最后一句话会被拼两遍。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<AgentEvent>>> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = SessionController.USER_ID_HEADER, required = false) String userId) {
        String message = request.requireMessage();
        String id = resolveChatId(request.chatId());
        log.info("[slim] chat 请求 chatId={} message={}", id, message);

        // 会话行在这里建/续期，而不是在 SlimApp 里：那边没有 HTTP 请求，拿不到用户是谁。
        // 标题取用户这句话——但只有首次插入时才写，用户改过的名字不会被冲掉（见仓储的注释）
        chatSessionRepository.touch(id, SessionController.resolveUserId(userId), ChatEntry.SLIM, message);

        // 流式没有一个「最终的字符串」可以事后取，只能一边推一边自己攒；攒的动作和
        // SlimApp 内部记聊天记录时是同一个套路
        StringBuilder full = new StringBuilder();
        Flux<AgentEvent> events = slimApp.chatStream(message, id)
                .doOnNext(full::append)
                .map(AgentEvent::text)
                // 必须包一层 defer：concatWith 的参数是在方法返回前就构造好的，
                // 直接写 AgentEvent.finished(full.toString()) 的话，那时候流还没跑，攒出来是空的
                .concatWith(Flux.defer(() -> Flux.just(AgentEvent.finished(full.toString()))))
                // 流式场景下异常发生在订阅之后，@RestControllerAdvice 拦不到，这里就地降级
                .onErrorResume(error -> Flux.just(terminalEvent(error)));

        return SseEvents.response(id, events);
    }

    /**
     * 读回某个会话的聊天记录，用于刷新页面后把对话重新画出来。
     *
     * <p>只读 {@code chat_record} 这张账本表，不动对话记忆——记忆是喂给模型的，
     * 两者的用途不同，别把它们混在一起。
     *
     * <p>会话不存在或还没聊过时返回空数组，这是正常结果，不是错误。
     */
    @GetMapping("/history")
    public List<ChatHistoryItem> history(@RequestParam String chatId) {
        // 入口到 scene 的映射收在 ChatEntry 里：它和侧边栏分栏用的是同一份规则，
        // 两处各写一遍的话，漂移的表现是「历史里少了一截」而不报任何错
        List<ChatHistoryItem> items = chatRecordRepository.findByConversation(chatId, ChatEntry.SLIM.scenes())
                .stream()
                .map(ChatHistoryItem::from)
                .toList();
        log.info("[slim] 读取历史 chatId={} 共 {} 条", chatId, items.size());
        return items;
    }

    /**
     * 看图解读（多模态）。
     *
     * <p>这是唯一一个不走流式的入口，也是唯一一个用 multipart 的：图片是二进制，
     * Base64 塞进 JSON 体积还要涨三分之一，而看图本来就是一眼的事，没有必要逐字往外挤。
     *
     * <p>它写进 {@code chat_record} 的记录 scene 是 {@code SLIM_IMAGE}，会出现在同一个会话的
     * {@code /history} 里——因为记忆是共用的，「先发图再追问」本来就是一轮连续对话。
     *
     * <p>示例：
     * <pre>
     *   curl -X POST http://localhost:8080/api/slim/image \
     *        -F "file=@meal.jpg" -F "question=这顿饭热量高吗？" -F "chatId=xxx"
     * </pre>
     */
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatReply explainImage(@RequestParam("file") MultipartFile file,
                                  @RequestParam(required = false) String question,
                                  @RequestParam(required = false) String chatId,
                                  @RequestHeader(value = SessionController.USER_ID_HEADER, required = false) String userId) {
        if (file == null || file.isEmpty()) {
            throw ApiException.invalidImage("请上传一张非空的图片文件");
        }
        String id = resolveChatId(chatId);
        log.info("[explainImage] chatId={} filename={} size={}B contentType={}",
                id, file.getOriginalFilename(), file.getSize(), file.getContentType());

        // 看图也是轻语的一段对话，同样要出现在侧边栏里。
        // 标题取这句提问；用户没写提问时仓储会兜成「新会话」——
        // 这里不替它编一句默认问题，那句话是给模型看的，不是用户说的
        chatSessionRepository.touch(id, SessionController.resolveUserId(userId), ChatEntry.SLIM, question);
        return new ChatReply(id, slimApp.explainImage(question, file.getResource(), resolveImageType(file), id));
    }

    private String resolveChatId(String chatId) {
        return (chatId == null || chatId.isBlank()) ? slimApp.newChatId() : chatId;
    }

    /**
     * 判定图片的 MIME 类型，顺便挡掉非图片的上传。
     *
     * <p>优先用上传时带的 Content-Type——它比文件名后缀可靠，后缀是可以随便改的；
     * 拿不到时才回退到按文件名推断。挡在入口的好处是：明显不是图片的请求
     * 不会走到模型那一层，既不浪费 token，也不用让模型去猜一堆乱码字节。
     */
    private static MimeType resolveImageType(MultipartFile file) {
        String contentType = file.getContentType();
        MimeType mimeType;
        try {
            mimeType = (contentType == null || contentType.isBlank())
                    ? MediaTypeFactory.getMediaType(file.getResource()).orElse(null)
                    : MimeTypeUtils.parseMimeType(contentType);
        } catch (IllegalArgumentException exception) {
            // MimeTypeUtils 对畸形 Content-Type 抛的是 InvalidMimeTypeException
            throw ApiException.invalidImage("无法解析的 Content-Type：" + contentType);
        }

        if (mimeType == null) {
            throw ApiException.invalidImage("无法识别图片格式，请改用 png / jpg / webp 等常见格式");
        }
        if (!"image".equalsIgnoreCase(mimeType.getType())) {
            throw ApiException.invalidImage("只支持上传图片，当前类型：" + mimeType);
        }
        return mimeType;
    }

    /**
     * 把流式链路里的异常翻译成一条终态事件。
     *
     * <p><b>敏感词拦截不能走 {@code AgentEvent.error}</b>：它不是故障，是顾问按策略主动拒绝，
     * 那段引导话术本身就是要给用户看的内容。走 ERROR 的话前端会画成红色的「生成失败」，
     * 用户看到的是「服务坏了」——与事实正好相反。
     *
     * <p>这条接口的阻塞版（{@code GlobalExceptionHandler}）为同一个异常特意返回 HTTP 200 +
     * 引导话术，注释里写明了「让前端可以像普通消息一样渲染这段引导话术」。这里用
     * {@code blocked} 是在流式链路上兑现同一条口径：话术照常显示，但状态单开一档，
     * 界面仍然能把它和正常答完区分开。智能体那条链路用的是同一个状态。
     */
    private static AgentEvent terminalEvent(Throwable error) {
        if (error instanceof SensitiveWordException sensitiveWordException) {
            return AgentEvent.blocked(sensitiveWordException.getReplyMessage());
        }
        log.error("流式对话失败", error);
        return AgentEvent.error("抱歉，服务暂时出了点问题，请稍后再试。");
    }
}
