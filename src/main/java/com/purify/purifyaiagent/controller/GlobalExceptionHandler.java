package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.exception.ApiException;
import com.purify.purifyaiagent.exception.AuthException;
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
     * <p>状态码取自异常对象而不是写死 400：{@link ApiException#notFound} 那一类
     * 必须是 404（会话不存在 / 不属于调用方），而它对用户和前端的意义与 400
     * 完全不同——400 是「你传的参数不对，改了再来」，404 是「这个东西没了」。
     *
     * <p><b>注意覆盖范围</b>：{@code SlimAppController} 和 {@code PurifyManusController}
     * 里的 {@code requireMessage()} 是在构造 {@code Flux} <b>之前</b>调用的，
     * 所以 SSE 接口的入参错误也能走到这里返回 400。哪天把它挪进流里面，
     * 这个 400 就会消失、被 {@code SlimAppController} 的兜底文案吞掉。
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorReply> handleApiException(ApiException exception) {
        log.warn("请求未通过：status={} code={} message={}",
                exception.getStatus().value(), exception.getCode(), exception.getMessage());
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorReply(exception.getCode(), exception.getMessage()));
    }

    /**
     * 没登录、令牌失效或权限不够。
     *
     * <p><b>401 和 403 必须是两个码，前端对它们的处理也完全不同</b>：
     * <ul>
     *   <li><b>401</b> → 清掉本地令牌、跳登录页。「你是谁」这个问题没有答案，重新登录是唯一的出路；</li>
     *   <li><b>403</b> → <b>保持登录</b>，只弹一句「这个功能只对超级管理员开放」。
     *       身份是好的，只是这个功能不给他。</li>
     * </ul>
     * 把两者并成一个码，最常见的后果是：普通用户点到一个本不该看到的链接，
     * 结果被登出了——用户会以为是自己账号出了问题，而实际上什么都没发生。
     *
     * <p>状态码取自异常对象（见 {@link AuthException}），不是在这里按类型写死：
     * 将来加一档（比如账号锁定 423）不用再动这个文件。
     *
     * <p>这里<b>不打 warn 打扰日志</b>：未登录时前端会有一串请求撞上来，
     * 每一次都记一条只会把真正的告警淹掉。权限不足那条在
     * {@code AuthInterceptor} 里已经单独记了（那条值得看，因为它是异常行为）。
     */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorReply> handleAuthException(AuthException exception) {
        log.debug("鉴权未通过：code={} message={}", exception.getCode(), exception.getMessage());
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorReply(exception.getCode(), exception.getMessage()));
    }
}
