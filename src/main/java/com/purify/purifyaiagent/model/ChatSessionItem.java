package com.purify.purifyaiagent.model;

import java.time.LocalDateTime;

/**
 * 侧边栏里的一项：一个会话。
 *
 * <p>只有「这个会话叫什么、什么时候聊的」，没有消息内容——点进去才去
 * {@code /api/{link}/history} 拉具体内容。列表接口要是把消息也带上，
 * 一个用户聊过几十次之后这个响应就没法看了。
 *
 * @param conversationId 会话 ID，点开它就去拉历史
 * @param title          会话标题。默认是首轮提问的截断，用户可以改
 * @param createdAt      首次对话时间
 * @param updatedAt      最近一次对话时间。列表按它倒序，所以「刚聊过的」永远在最上面
 */
public record ChatSessionItem(String conversationId,
                              String title,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt) {
}
