package com.purify.purifyaiagent.model;

/**
 * 请求被应用层主动拒绝时的响应体。
 *
 * <p>两种来源：命中敏感词（HTTP 200，错误码 {@code SENSITIVE_WORD_BLOCKED}），
 * 以及客户端传错了东西（HTTP 400，错误码见 {@code ApiException} 里的常量）。
 *
 * @param code    稳定的错误码，便于前端区分处理
 * @param message 给用户看的话术
 */
public record ErrorReply(String code, String message) {
}
