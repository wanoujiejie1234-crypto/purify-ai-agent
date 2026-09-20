package com.purify.purifyaiagent.chat;

import java.util.List;
import java.util.Locale;

/**
 * 对话的入口——用户是从哪个页面进来的。存进 {@code chat_session.entry}。
 *
 * <p><b>它和 {@link ChatScene} 不是一回事，别合并。</b>scene 是「这条记录是谁写的」，
 * 一次会话里可以出现多个（轻语先发图、再追问，就是 {@code SLIM_IMAGE} 和 {@code SLIM}
 * 混在同一个 chatId 下）；entry 是「这个会话属于哪个入口」，一次会话只有一个，
 * 侧边栏按它分栏——轻语的历史里就不该冒出智能体的会话。
 *
 * <p>入口到 scene 的映射也放在这里，而不是在两个 controller 里各写一份：
 * 「轻语要同时看到文本和看图两种记录」这条规则，两个地方各写一遍迟早会漂移，
 * 而漂移的表现是「历史里少了一截」——用户先发的图不见了，但谁也不会收到报错。
 */
public enum ChatEntry {

    /** 轻语：文本对话 + 看图。两者算同一段对话，理由见上面的类注释。 */
    SLIM("slim", List.of(ChatScene.SLIM, ChatScene.SLIM_IMAGE)),

    /** PurifyManus 智能体。 */
    MANUS("manus", List.of(ChatScene.MANUS));

    /** 前端路由 / URL 里用的名字，与 {@code chatConfig.js} 里的 link 字段一字不差。 */
    private final String link;

    private final List<ChatScene> scenes;

    ChatEntry(String link, List<ChatScene> scenes) {
        this.link = link;
        this.scenes = scenes;
    }

    public String link() {
        return link;
    }

    /** 这个入口的历史该读哪些 scene，交给 {@code ChatRecordRepository#findByConversation}。 */
    public List<ChatScene> scenes() {
        return scenes;
    }

    /**
     * 按链接名解析，大小写不敏感。
     *
     * <p>解析不了时返回 {@code null} 而不是抛异常：调用方需要区分「没传」和「传错了」——
     * 没传可以退到默认入口，传错了则应当明确报错，否则一个拼错的 link 会静默变成
     * 「这个入口没有历史记录」，查起来毫无线索。
     */
    public static ChatEntry fromLink(String link) {
        if (link == null) {
            return null;
        }
        String normalized = link.trim().toLowerCase(Locale.ROOT);
        for (ChatEntry entry : values()) {
            if (entry.link.equals(normalized)) {
                return entry;
            }
        }
        return null;
    }
}
