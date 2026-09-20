package com.purify.purifyaiagent.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 「你没能证明自己是谁」或「你证明了自己，但不够格」。
 *
 * <p><b>为什么不复用 {@link ApiException}</b>：那个的契约是「HTTP 400，
 * 客户端传的东西不合法」——参数为空、格式不对、分类值不存在。而这里的两类
 * 需要两个不同的状态码，前端的行为也完全不同：
 * <ul>
 *   <li>{@link #unauthorized} → <b>401</b>：没有令牌、令牌过期或伪造。
 *       前端应当清掉本地令牌并跳登录页；</li>
 *   <li>{@link #forbidden} → <b>403</b>：身份没问题，是权限不够。
 *       前端应当<b>保持登录</b>，只提示一句。</li>
 * </ul>
 * 把这两者混成一个码，最直接的后果是普通用户点到一个本不该看到的链接就被登出了。
 *
 * <p>响应体仍然是项目统一的 {@code {code, message}}，由 {@code GlobalExceptionHandler}
 * 里的两条分支渲染——和 {@code ApiException} 走的是同一套出口，前端不用多学一种格式。
 *
 * <p>状态码放在异常对象里而不是由处理器按类型决定：将来再加一档（比如 423 锁定）
 * 就不用再动处理器。
 */
@Getter
public class AuthException extends RuntimeException {

    /** 没登录、令牌无效或已过期。前端据此清令牌并跳登录页。 */
    public static final String UNAUTHORIZED = "UNAUTHORIZED";

    /** 登录了，但角色不够。前端保持登录态。 */
    public static final String FORBIDDEN = "FORBIDDEN";

    private final String code;
    private final HttpStatus status;

    private AuthException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /**
     * 401：没能证明身份。
     *
     * <p>{@code message} 会被原样显示给用户，所以两件事要注意：
     * 别把「令牌签名不对」这种内部细节写进去（对用户没有意义，对攻击者反而有用），
     * 也别写「用户不存在」——那是个账号枚举口子。统一说「登录已失效，请重新登录」。
     */
    public static AuthException unauthorized(String message) {
        return new AuthException(UNAUTHORIZED, HttpStatus.UNAUTHORIZED, message);
    }

    /** 403：身份没问题，权限不够。 */
    public static AuthException forbidden(String message) {
        return new AuthException(FORBIDDEN, HttpStatus.FORBIDDEN, message);
    }
}
