package com.purify.purifyaiagent.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注「这个接口必须登录」。可以打在方法上，也可以打在类上（类上的对该类所有方法生效）。
 *
 * <p><b>为什么是「标了才拦」而不是「默认全拦、放行的才标」</b>：
 * 这个项目在接入登录之前，所有接口都是公开的。默认全拦的话，
 * 每加一个注解就多一个「忘了放行」的机会，而忘掉的表现是全站 401——
 * 排查方向会指向那个看起来完全无关的接口。现在这样，漏标的表现是
 * 「这个接口还是公开的」，问题范围明确、且没有破坏性。
 *
 * <p><b>代价要说清楚</b>：新加的接口如果忘了标，它就是公开的，而且不会有任何提示。
 * 所以凡是读取或写入「属于某个人的数据」的接口都必须标上，这一点没有捷径。
 * 受影响的地方集中在三个 controller：{@code SessionController}、
 * {@code SlimAppController}、{@code PurifyManusController}。
 *
 * <p>和 {@link RequireAdmin} 的关系：后者更严，两个都标时以后者为准
 * （超级用户必然是登录用户，不需要再检查一遍）。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireLogin {
}
