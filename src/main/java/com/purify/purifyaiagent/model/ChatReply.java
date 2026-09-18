package com.purify.purifyaiagent.model;

/**
 * 对话接口的响应体。
 *
 * @param chatId 本次会话 ID；客户端没传时由服务端生成，需要带回给客户端用于后续多轮对话
 * @param reply  模型回复
 */
public record ChatReply(String chatId, String reply) {
}
