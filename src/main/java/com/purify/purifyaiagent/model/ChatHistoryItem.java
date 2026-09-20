package com.purify.purifyaiagent.model;

import java.time.LocalDateTime;

/**
 * 聊天历史里的一条，给前端刷新页面后把之前的对话重新画出来用。
 *
 * <p>它比 {@link ChatRecord} 少一个 {@code conversationId}：那是查询时传进去的条件，
 * 客户端本来就知道，回传一遍只是噪音。
 *
 * <p>{@code state} 和 {@code steps} <b>只有智能体的记录才有</b>，轻语那边是 null——
 * 前端据此决定要不要画「走了几步」「被中止了」这类标记，别把它们当成空字符串来判空。
 *
 * @param question  用户那一轮说的话
 * @param answer    那一轮的回应。智能体反问用户时，这里是它问的那句话
 * @param state     智能体那一轮的收尾状态（{@code FINISHED} / {@code WAITING_FOR_USER} / ...）；
 *                  轻语为 null
 * @param steps     智能体那一轮走了几步；轻语为 null
 * @param createdAt 发生时间，按它升序排列就是对话的自然顺序
 */
public record ChatHistoryItem(String question,
                              String answer,
                              String state,
                              Integer steps,
                              LocalDateTime createdAt) {

    public static ChatHistoryItem from(ChatRecord record) {
        return new ChatHistoryItem(record.question(),
                record.answer(),
                record.state(),
                record.steps(),
                record.createdAt());
    }
}
