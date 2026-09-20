package com.purify.purifyaiagent.model;

/**
 * 登录请求。
 *
 * <p>用用户名而不是邮箱登录：需求里那个超级用户叫 {@code root_agent}，
 * 而它没有邮箱（{@code user.email} 可空）。用邮箱当账号的话，
 * 种子账号就得凭空编一个邮箱出来，而编出来的地址是不通邮的——
 * 一旦它需要找回密码就会卡死。用户名没有这个问题。
 *
 * @param username 登录名
 * @param password 明文密码，只用于这一次比对
 */
public record LoginRequest(String username, String password) {
}
