package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.exception.ApiException;
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

    /**
     * 命中敏感词：把 Advisor 抛出的异常翻译成用户可读的引导话术。
     *
     * <p><b>必须排在 {@link ApiException} 那条之前处理</b>——两者是兄弟类型，
     * {@code SensitiveWordException} 不继承 {@code ApiException}，就是为了防止
     * 这条 200 分支被下面那条 400 抢走。
     */
    @ExceptionHandler(SensitiveWordException.class)
    public ResponseEntity<ErrorReply> handleSensitiveWord(SensitiveWordException exception) {
        log.warn("请求被敏感词拦截：hitWord={}", exception.getHitWord());
        return ResponseEntity.ok(new ErrorReply("SENSITIVE_WORD_BLOCKED", exception.getReplyMessage()));
    }

    /**
     * 客户端传错了东西：对话请求为空、图片格式不合法、文档不收或建不了索引。
     *
     * <p>这四类原先各有各的处理器，但处理逻辑完全一样，区别只在错误码——
     * 而错误码现在已经由 {@link ApiException} 的工厂方法固定在异常对象里，
     * 所以这里只需要一条分支。
     *
     * <p>返回 400 而不是 500：这些消息都是写给调用方看的
     * （比如「只支持 txt/md」或者「未知的分类」），原样带上比换成一句笼统的话更容易排查。
     * 真正的服务端故障（比如向量库连不上）会是 {@code DataAccessException} 之类，
     * 不在这里拦，照旧走 500。
     *
     * <p><b>注意覆盖范围</b>：{@code SlimAppController} 和 {@code PurifyManusController}
     * 里的 {@code requireMessage()} 是在构造 {@code Flux} <b>之前</b>调用的，
     * 所以 SSE 接口的入参错误也能走到这里返回 400。哪天把它挪进流里面，
     * 这个 400 就会消失、被 {@code SlimAppController} 的兜底文案吞掉。
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorReply> handleApiException(ApiException exception) {
        log.warn("请求校验不通过：code={} message={}", exception.getCode(), exception.getMessage());
        return ResponseEntity.badRequest().body(new ErrorReply(exception.getCode(), exception.getMessage()));
    }
}
