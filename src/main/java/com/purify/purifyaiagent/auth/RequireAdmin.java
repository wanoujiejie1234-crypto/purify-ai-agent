package com.purify.purifyaiagent.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注「必须超级用户才能访问」。可以打在方法上，也可以打在类上。
 *
 * <p>隐含了 {@link RequireLogin}——超级用户必然已经登录，不需要两个都写。
 * {@link AuthInterceptor} 的检查顺序也是先 admin 后 login，两者都标时以后者为准。
 *
 * <p><b>未登录和权限不够返回的状态码必须不同</b>：未登录是 401（前端清令牌去登录页），
 * 权限不够是 403（前端保持登录，提示一句就行）。混成一个码的后果是
 * 「普通用户点到一个本不该看到的链接，结果被登出了」——这与事实完全不符，
 * 用户会以为是自己账号出了问题。
 *
 * <p>目前只有知识库模块用它（{@code KnowledgeBaseController} + {@code RagController}）。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
