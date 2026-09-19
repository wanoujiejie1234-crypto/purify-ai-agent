package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.exception.DocumentIndexException;
import com.purify.purifyaiagent.exception.InvalidImageException;
import com.purify.purifyaiagent.exception.SensitiveWordException;
import com.purify.purifyaiagent.exception.UnsupportedDocumentException;
import com.purify.purifyaiagent.model.ErrorReply;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 *
 * <p>敏感词拦截不是系统故障，而是业务上的「主动拒绝」，
 * 因此这里返回 HTTP 200 + 明确的错误码，让前端可以像普通消息一样渲染这段引导话术；
 * 真正的系统异常才走 500。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 命中敏感词：把 Advisor 抛出的异常翻译成用户可读的引导话术。 */
    @ExceptionHandler(SensitiveWordException.class)
    public ResponseEntity<ErrorReply> handleSensitiveWord(SensitiveWordException exception) {
        log.warn("请求被敏感词拦截：hitWord={}", exception.getHitWord());
        return ResponseEntity.ok(new ErrorReply("SENSITIVE_WORD_BLOCKED", exception.getReplyMessage()));
    }

    /**
     * 图片不合法：这是客户端传错了东西，返回 400。
     *
     * <p>与敏感词不同，这里不需要「照顾用户体验」的话术，
     * 直接告诉调用方哪里传错了，反而更容易排查。
     */
    @ExceptionHandler(InvalidImageException.class)
    public ResponseEntity<ErrorReply> handleInvalidImage(InvalidImageException exception) {
        log.warn("图片校验不通过：{}", exception.getMessage());
        return ResponseEntity.badRequest().body(new ErrorReply("INVALID_IMAGE", exception.getMessage()));
    }

    /**
     * 知识库文档不收：格式、体积、分类值等入口处的校验没过。
     *
     * <p>和图片一样属于「客户端传错了东西」，返回 400 并原样带上原因——
     * 这些消息都是写给上传的人看的，比如「只支持 txt/md」或者「未知的分类」。
     */
    @ExceptionHandler(UnsupportedDocumentException.class)
    public ResponseEntity<ErrorReply> handleUnsupportedDocument(UnsupportedDocumentException exception) {
        log.warn("文档未被接受：{}", exception.getMessage());
        return ResponseEntity.badRequest().body(new ErrorReply("UNSUPPORTED_DOCUMENT", exception.getMessage()));
    }

    /**
     * 文档收下了但索引建不下去：内容为空、不是 UTF-8、切片数撞上上限。
     *
     * <p>同样是文档本身的问题，所以也是 400；真正服务端的故障（比如向量库连不上）
     * 会是 {@code DataAccessException} 之类，不在这里拦，照旧走 500。
     */
    @ExceptionHandler(DocumentIndexException.class)
    public ResponseEntity<ErrorReply> handleDocumentIndex(DocumentIndexException exception) {
        log.warn("文档建索引失败：{}", exception.getMessage());
        return ResponseEntity.badRequest().body(new ErrorReply("DOCUMENT_INDEX_FAILED", exception.getMessage()));
    }
}
