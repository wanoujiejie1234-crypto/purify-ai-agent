package com.purify.purifyaiagent.model;

/**
 * 登录 / 注册成功后返回的东西：一个令牌，加上「你是谁」。
 *
 * <p>把用户信息一起返回而不是让前端再打一次 {@code /api/auth/me}：
 * 登录之后马上就要画侧边栏底部的那个用户菜单，而多一次往返意味着
 * 界面会先空一下再填上。这两个数据本来就是一起产生的，分两次拿没有好处。
 *
 * @param token 后续请求放在 {@code Authorization: Bearer <token>} 里
 * @param user  当前用户，前端用它决定显示什么，以及知识库入口要不要出现
 */
public record LoginResponse(String token, AuthUserView user) {
}
