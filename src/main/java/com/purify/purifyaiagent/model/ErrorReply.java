package com.purify.purifyaiagent.model;

/**
 * 请求被应用层主动拒绝时的响应体（目前只有敏感词拦截一种）。
 *
 * @param code    稳定的错误码，便于前端区分处理
 * @param message 给用户看的话术
 */
public record ErrorReply(String code, String message) {
}
