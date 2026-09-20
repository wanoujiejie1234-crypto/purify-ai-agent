package com.purify.purifyaiagent.auth;

/**
 * 验证码的用途。注册和找回密码共用一套验证码机制，靠这个字段隔开。
 *
 * <p><b>两者的验证码不能通用。</b>合并成一个「万能验证码」会让「拿到注册验证码」
 * 等于「拿到改密码的权限」——而注册那条链路是开放的（谁都能请求），
 * 找回密码那条则不该是。分开存之后，一条用于 A 用途的码在 B 用途上查不到。
 *
 * <p>解析失败返回 {@code null} 而不是像 {@link UserRole} 那样退回默认值：
 * 这个值来自请求体，写错了是该报 400 的，而不是猜一个继续往下走。
 * 猜错的后果是「用注册的码去改密码」被当成合法路径——一个静默的权限混淆。
 */
public enum VerificationPurpose {

    /** 注册新账号。 */
    REGISTER,

    /** 重置已有账号的密码。 */
    RESET_PASSWORD;

    /** 宽松解析，认不出来返回 {@code null}，由调用方决定报什么错。 */
    public static VerificationPurpose parse(String value) {
        if (value == null) {
            return null;
        }
        for (VerificationPurpose purpose : values()) {
            if (purpose.name().equalsIgnoreCase(value.trim())) {
                return purpose;
            }
        }
        return null;
    }
}
