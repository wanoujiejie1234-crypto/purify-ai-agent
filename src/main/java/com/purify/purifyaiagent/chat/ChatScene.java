package com.purify.purifyaiagent.chat;

/**
 * 一条聊天记录来自哪条链路。存进 {@code chat_record.scene} 列。
 *
 * <p>两条链路（轻语、PurifyManus）写的是同一张表，因为「用户问了什么、答了什么」这件事
 * 对它们来说是一样的，查询时也希望能一起查；差别只在场景本身，用这一列区分就够了。
 *
 * <p>用枚举而不是散落的字符串：这一列将来是要拿来过滤的（比如「只看智能体的记录」），
 * 值写错了不会报错、只会静默查不到，正是最该由类型管住的那类东西。
 */
public enum ChatScene {

    /** 轻语的文本对话（{@code /api/slim/chat}）。 */
    SLIM,

    /** 轻语的看图对话（{@code /api/slim/image}）。 */
    SLIM_IMAGE,

    /** PurifyManus 智能体（{@code /api/manus/*}）。 */
    MANUS
}
