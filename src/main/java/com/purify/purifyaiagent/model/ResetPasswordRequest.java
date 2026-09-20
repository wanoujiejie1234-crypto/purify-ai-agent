package com.purify.purifyaiagent.model;

/**
 * 找回密码：用发到邮箱的验证码换一次改密码的机会。
 *
 * <p>不改密码之外的东西——不重置 {@code status}、不动头像昵称。
 * 「找回密码」就该只做它名字里那一件事。
 *
 * @param email       注册时用的邮箱，系统据此反查账号
 * @param code        发到该邮箱的 6 位验证码（用途必须是 {@code RESET_PASSWORD}）
 * @param newPassword 新密码明文
 */
public record ResetPasswordRequest(String email, String code, String newPassword) {
}
