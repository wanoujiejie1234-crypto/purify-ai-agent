package com.purify.purifyaiagent.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把当前登录用户注入 controller 的方法参数。
 *
 * <pre>
 *   &#64;GetMapping("/api/auth/me")
 *   public AuthUserView me(&#64;CurrentUser LoginUser me) { ... }
 * </pre>
 *
 * <p><b>为什么不直接注入 {@code HttpServletRequest} 自己取属性</b>：
 * 那样每个方法都要写一行 {@code (LoginUser) request.getAttribute(...)}，
 * 而且返回类型是可空的——每个调用点都得决定「取不到怎么办」，
 * 于是这件事会被决定很多次，迟早有一次决定错。用注解 + 参数解析器，
 * 这件事只决定一次。
 *
 * <p><b>{@link #required()} 默认 true</b>：标了 {@code @CurrentUser} 却拿到 null，
 * 绝大多数情况是「这个接口忘了加 {@code @RequireLogin}」这个 bug，
 * 而不是「这个接口本来就允许匿名」。前者应该当场炸出来（由解析器抛异常），
 * 而不是让一个 null 顺着代码流下去，最后在某个遥远的地方变成 NPE。
 * 确实需要可空的接口（目前没有）显式写 {@code @CurrentUser(required = false)}。
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {

    /** 没有登录用户时是否允许注入 null。 */
    boolean required() default true;
}
