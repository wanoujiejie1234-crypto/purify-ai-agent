package com.purify.purifyaiagent.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 「上游服务没把活干成」——百炼接口超时、被限流、返回 {@code Success=false}、或者干脆 5xx。
 *
 * <p><b>为什么不复用 {@link ApiException}</b>：那个的契约是「HTTP 400，客户端传的东西不合法」。
 * 把「百炼接口超时」报成 400 会把排查方向整个带偏——用户会去改文件名、改分类、改参数，
 * 而实际上他改什么都没用，只能等一会再试。502 才是准确的：错在上游，不在这个请求。
 *
 * <p><b>为什么必须显式建这个类，而不是让裸异常走 Spring 默认的 500</b>：
 * {@code GlobalExceptionHandler} 现在只拦三类异常，其余一律落到 Spring Boot 的默认错误处理。
 * 而 {@code server.error.include-message} 的默认值是 {@code never}——
 * <b>默认错误响应里压根没有 message 字段</b>，于是前端 {@code api/http.js} 里那句
 * {@code if (serverMessage)} 判不成立，最后回落成「请求失败（HTTP 500）」，
 * 百炼到底说了什么（比如 {@code Throttling.User}）全部丢掉。
 * 而「被限流了，等一会重试」和「权限不够，去 RAM 申请」这两件事，用户要做的事完全不同。
 *
 * <p>响应体仍是项目统一的 {@code {code, message}}，与 ApiException / AuthException
 * 走同一套出口，前端不用多学一种格式。
 */
@Getter
public class UpstreamException extends RuntimeException {

    /** 上游（百炼）接口调用失败。 */
    public static final String UPSTREAM_FAILED = "UPSTREAM_FAILED";

    private final String code;

    private final HttpStatus status;

    /** 文案在 {@code messages*.properties} 里的键，**不是**给用户看的那句话。理由同 {@code ApiException}。 */
    private final String messageKey;

    /** 填进文案占位符的参数。 */
    private final Object[] args;

    private UpstreamException(String code, HttpStatus status, String messageKey, Object[] args) {
        // 传键本身，于是 getMessage() 拿到的是键——日志里方便定位到是哪条分支
        super(messageKey);
        this.code = code;
        this.status = status;
        this.messageKey = messageKey;
        this.args = args == null ? new Object[0] : args;
    }

    /**
     * 502：上游没干成。
     *
     * <p>调用方应当把上游给的原话（错误码 + 消息）当参数塞进来。那些原话是这句话里
     * 唯一能指向动作的部分——「Throttling.User」告诉用户等，「Forbidden」告诉用户
     * 去申请权限，而一句笼统的「上游调用失败」什么都不指向。
     */
    public static UpstreamException failed(String messageKey, Object... args) {
        return new UpstreamException(UPSTREAM_FAILED, HttpStatus.BAD_GATEWAY, messageKey, args);
    }
}
