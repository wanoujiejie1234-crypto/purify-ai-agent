package com.purify.purifyaiagent.model;

/**
 * 注册请求。验证码必须已经通过 {@code POST /api/auth/code} 发到 {@code email}。
 *
 * @param username 登录名
 * @param password 明文密码。<b>只存在于这个请求对象和一次 BCrypt 调用之间</b>，
 *                 落库的永远是哈希，日志里也不打它
 * @param email    邮箱。注册流程要求它真实可收信——验证码是唯一的凭证
 * @param code     收到的 6 位验证码
 * @param nickname 昵称，可空。不填就用用户名展示
 */
public record RegisterRequest(String username, String password, String email, String code, String nickname) {
}
