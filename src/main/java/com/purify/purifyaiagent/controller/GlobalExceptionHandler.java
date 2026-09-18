package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.exception.InvalidImageException;
import com.purify.purifyaiagent.exception.SensitiveWordException;
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
}
