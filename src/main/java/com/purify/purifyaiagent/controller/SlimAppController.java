package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.app.SlimApp;
import com.purify.purifyaiagent.exception.InvalidImageException;
import com.purify.purifyaiagent.exception.SensitiveWordException;
import com.purify.purifyaiagent.model.ChatReply;
import com.purify.purifyaiagent.model.HistoryMessage;
import com.purify.purifyaiagent.model.SlimPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * SlimApp 的 HTTP 接口，方便用 curl 或前端直接体验。
 *
 * <p>所有对话接口都接受可选的 {@code chatId}：
 * 不传则自动新建一个会话，并把 ID 返回给客户端；后续请求带上同一个 ID 即可延续上下文。
 */
@Slf4j
@RestController
@RequestMapping("/api/slim")
public class SlimAppController {

    private final SlimApp slimApp;

    public SlimAppController(SlimApp slimApp) {
        this.slimApp = slimApp;
    }

    /** 新建一个会话 ID，用于客户端提前拿到 ID（例如流式场景）。 */
    @GetMapping("/chat-id")
    public ChatReply newChatId() {
        return new ChatReply(slimApp.newChatId(), "会话已创建，带上这个 chatId 开始对话吧。");
    }

    /** 多轮对话。 */
    @GetMapping("/chat")
    public ChatReply chat(@RequestParam String message,
                          @RequestParam(required = false) String chatId) {
        String id = resolveChatId(chatId);
        return new ChatReply(id, slimApp.chat(message, id));
    }

    /**
     * 多轮对话（流式，SSE）。
     *
     * <p>会话 ID 通过响应头 {@code X-Chat-Id} 返回，因为 SSE 的每条 data 都是正文内容，
     * 不适合混入元信息。
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<String>> chatStream(@RequestParam String message,
                                                   @RequestParam(required = false) String chatId) {
        String id = resolveChatId(chatId);
        Flux<String> stream = slimApp.chatStream(message, id)
                // 流式场景下异常发生在订阅之后，@RestControllerAdvice 拦不到，这里就地降级
                .onErrorResume(error -> Flux.just(fallbackMessage(error)));
        return ResponseEntity.ok()
                .header("X-Chat-Id", id)
                .body(stream);
    }

    /** 生成结构化瘦身计划。 */
    @GetMapping("/plan")
    public SlimPlan plan(@RequestParam String message,
                         @RequestParam(required = false) String chatId) {
        return slimApp.generatePlan(message, resolveChatId(chatId));
    }

    /**
     * 看图解读（多模态）。
     *
     * <p>用 multipart 而不是把图片塞进 query 参数：图片是二进制，Base64 后体积还要涨 1/3，
     * 而且 URL 长度限制也扛不住手机拍的照片。
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
                                  @RequestParam(required = false) String chatId) {
        if (file == null || file.isEmpty()) {
            throw new InvalidImageException("请上传一张非空的图片文件");
        }
        String id = resolveChatId(chatId);
        log.info("[explainImage] chatId={} filename={} size={}B contentType={}",
                id, file.getOriginalFilename(), file.getSize(), file.getContentType());
        return new ChatReply(id, slimApp.explainImage(question, file.getResource(), resolveImageType(file), id));
    }

    /** 查看某个会话存在 MySQL 里的历史消息。 */
    @GetMapping("/history")
    public List<HistoryMessage> history(@RequestParam String chatId) {
        return slimApp.history(chatId).stream().map(HistoryMessage::from).toList();
    }

    /** 清空某个会话的历史。 */
    @DeleteMapping("/history")
    public void clearHistory(@RequestParam String chatId) {
        slimApp.clearHistory(chatId);
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
            throw new InvalidImageException("无法解析的 Content-Type：" + contentType);
        }

        if (mimeType == null) {
            throw new InvalidImageException("无法识别图片格式，请改用 png / jpg / webp 等常见格式");
        }
        if (!"image".equalsIgnoreCase(mimeType.getType())) {
            throw new InvalidImageException("只支持上传图片，当前类型：" + mimeType);
        }
        return mimeType;
    }

    /** 把流式链路里的异常翻译成给用户看的话术。 */
    private static String fallbackMessage(Throwable error) {
        if (error instanceof SensitiveWordException sensitiveWordException) {
            return sensitiveWordException.getReplyMessage();
        }
        log.error("流式对话失败", error);
        return "抱歉，服务暂时出了点问题，请稍后再试。";
    }
}
