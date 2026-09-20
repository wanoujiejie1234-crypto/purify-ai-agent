package com.purify.purifyaiagent.model;

import com.purify.purifyaiagent.chat.ChatScene;

import java.time.LocalDateTime;

/**
 * 一条聊天记录：一轮问答存一行。对应 {@code chat_record} 表。
 *
 * <p>注意它不是「对话记忆」——记忆是喂给模型的，记录是给人看的。所以这里刻意只有
 * 问题和答复，没有工具调用那些中间过程：中间过程在日志里，不在这张表里。
 *
 * @param conversationId 会话 ID
 * @param scene          来自哪条链路，见 {@link ChatScene}
 * @param question       用户这一轮说的话
 * @param answer         这一轮的回应。智能体反问用户时，这里记的是它问的那句话
 * @param state          智能体这一轮的收尾状态（{@code AgentState} 的名字）；
 *                       轻语没有这个概念，为 null
 * @param steps          智能体这一轮走了几步；轻语为 null
 * @param createdAt      写入时间
 */
public record ChatRecord(String conversationId,
                         ChatScene scene,
                         String question,
                         String answer,
                         String state,
                         Integer steps,
                         LocalDateTime createdAt) {

    /**
     * 轻语的一轮问答。
     *
     * <p>状态和步数留空，不是「还没填」，而是这条链路本来就没有——用 null 比填一个
     * 看不出区别的 {@code FINISHED} 更诚实：查库的人一眼就知道那一列只有智能体会用。
     */
    public static ChatRecord slim(String conversationId, ChatScene scene, String question, String answer) {
        return new ChatRecord(conversationId, scene, question, answer, null, null, LocalDateTime.now());
    }

    /** 智能体的一轮：比轻语多一个「怎么收的尾」和「走了几步」。 */
    public static ChatRecord manus(String conversationId,
                                   String question,
                                   String answer,
                                   String state,
                                   Integer steps) {
        return new ChatRecord(conversationId, ChatScene.MANUS, question, answer, state, steps, LocalDateTime.now());
    }
}
