package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.advisor.SensitiveWordChecker;
import com.purify.purifyaiagent.exception.ApiException;
import com.purify.purifyaiagent.exception.AuthException;
import com.purify.purifyaiagent.exception.SensitiveWordException;
import com.purify.purifyaiagent.exception.UpstreamException;
import com.purify.purifyaiagent.i18n.MessageResolver;
import com.purify.purifyaiagent.i18n.Messages;
import com.purify.purifyaiagent.model.ErrorReply;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 *
 * <p>敏感词拦截不是系统故障，而是业务上的「主动拒绝」，
 * 因此这里返回 HTTP 200 + 明确的错误码，让前端可以像普通消息一样渲染这段引导话术；
 * 真正的系统异常才走 500。
 *
 * <p><b>这里是异常文案唯一被翻成人类语言的地方。</b>异常对象里带的是键 + 参数
 * （见 {@code ApiException} 的类注释），翻译发生在这一步，因为只有这里同时握着
 * 两样东西：一个 {@code MessageSource}，和当前请求的语言
 * （由 Spring Boot 默认的 {@code AcceptHeaderLocaleResolver} 从 {@code Accept-Language} 解析）。
 *
 * <p>这个方法一定跑在请求线程上（Spring MVC 的异常处理链），
 * 所以 {@code MessageResolver#current()} 里的 {@code LocaleContextHolder} 是准的——
 * 这一点和智能体循环里完全不同，那边必须靠 {@code AgentRun} 把语言带下去。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final MessageResolver messageResolver;
    private final SensitiveWordChecker sensitiveWordChecker;

    public GlobalExceptionHandler(MessageResolver messageResolver, SensitiveWordChecker sensitiveWordChecker) {
        this.messageResolver = messageResolver;
        this.sensitiveWordChecker = sensitiveWordChecker;
    }

    /**
     * 命中敏感词：把 Advisor 抛出的异常翻译成用户可读的引导话术。
     *
     * <p><b>必须排在 {@link ApiException} 那条之前处理</b>——两者是兄弟类型，
     * {@code SensitiveWordException} 不继承 {@code ApiException}，就是为了防止
     * 这条 200 分支被下面那条 400 抢走。
     *
     * <p><b>这是同一条规则的第一处实现，不是唯一一处。</b>SSE 那几个接口拿不到
     * {@code @RestControllerAdvice}——异常发生在流被订阅之后，早就出了 Spring MVC 的
     * 异常处理链，所以 {@code SlimAppController#terminalEvent} 把同一个异常又翻了一遍，
     * 翻成 {@code AgentEvent.blocked(...)}。
     *
     * <p>两者的输出形态注定不同（一个是 HTTP body，一个是 SSE 事件），没法合并，
     * 能共享的只有「哪类异常算业务拒绝」这个判据。所以<b>新增一种业务拒绝型异常时，
     * 两个地方都要改</b>，而漏掉流式那处不会有任何编译错误、测试失败或日志——
     * 表现是流式用户看到一句「流式响应失败了」，与事实相反。
     *
     * @see SlimAppController#terminalEvent
     */
    @ExceptionHandler(SensitiveWordException.class)
    public ResponseEntity<ErrorReply> handleSensitiveWord(SensitiveWordException exception) {
        log.warn("请求被敏感词拦截：hitWord={}", exception.getHitWord());
        // 话术在这里取，不在 Advisor 里取：异常是 Advisor 抛的，而那条链在流式用法下
        // 跑在 Reactor 线程上，读 LocaleContextHolder 会静默回落成默认语言。
        // 这个方法一定在请求线程上，取到的语言是准的。见 SensitiveWordException 的类注释
        String reply = sensitiveWordChecker.replyMessage(LocaleContextHolder.getLocale());
        return ResponseEntity.ok(new ErrorReply("SENSITIVE_WORD_BLOCKED", reply));
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
        String message = resolve(exception.getMessageKey(), exception.getArgs());
        // 记的是**翻好之后**的那句话，不是键：日志是给人看的，
        // 一句「用户名已经被占用了」比 error.auth.usernameTaken 好读，
        // 而键本来就在异常抛出的那一行代码上，需要时顺藤摸瓜即可
        log.warn("请求未通过：status={} code={} message={}",
                exception.getStatus().value(), exception.getCode(), message);
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorReply(exception.getCode(), message));
    }

    /**
     * 上游服务（百炼）没把活干成：超时、限流、{@code Success=false}、5xx。
     *
     * <p><b>为什么单开一条分支而不是并进 {@link ApiException}</b>：那条的契约是 400
     * 「你传错了」，而这里错在上游——用户改什么都没用，只能等或去申请权限。
     * 状态码取自异常对象（见 {@link UpstreamException}），不是在这里写死 502。
     *
     * <p>记 WARN 而不是 ERROR：这是外部依赖的抖动，不是本系统的故障，
     * 用 ERROR 会让真正该报警的条目淹在里面。但也不能像 {@code AuthException}
     * 那样降到 DEBUG——「知识库同步时好时坏」正是最需要日志能回答的那类问题。
     */
    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<ErrorReply> handleUpstreamException(UpstreamException exception) {
        String message = resolve(exception.getMessageKey(), exception.getArgs());
        log.warn("上游调用失败：status={} code={} message={}",
                exception.getStatus().value(), exception.getCode(), message);
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorReply(exception.getCode(), message));
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
        String message = resolve(exception.getMessageKey(), exception.getArgs());
        log.debug("鉴权未通过：code={} message={}", exception.getCode(), message);
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorReply(exception.getCode(), message));
    }

    /**
     * 把「键 + 参数」翻成当前请求语言下的一句话。
     *
     * <p>取自 request 的语言，不是 JVM 默认语言——{@code AcceptHeaderLocaleResolver}
     * 已经把 {@code Accept-Language} 解析好放在 {@code LocaleContextHolder} 里了。
     * 前端（{@code api/http.js} 和 {@code api/sse.js} 两处）每个请求都会带上这个头，
     * 漏掉的表现是「界面全英文，一报错冒出一句中文」。
     */
    private String resolve(String key, Object[] args) {
        Messages messages = messageResolver.current();
        return messages.get(key, args);
    }
}
