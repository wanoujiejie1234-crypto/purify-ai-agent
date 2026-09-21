package com.purify.purifyaiagent.chat;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /**
     * scene → entry 的反查表，类加载时由上面那两行声明建好。
     *
     * <p>存在的理由是「加一个 scene 忘了登记」这件事<b>不会有任何报错</b>：
     * 漏登记的 scene 不会出现在任何入口的 {@link #scenes()} 里，
     * {@code findByConversation} 的 {@code IN (...)} 因此少一个值，
     * 表现是「用户先发的图，翻历史时不见了」——没有异常、没有日志、也没有测试变红。
     *
     * <p>建表时顺手把两种登记错误炸出来（重复登记、以及 {@link #of} 查不到），
     * 和 {@code AgentToolRegistry#assertNoDuplicateNames} 是同一个取向：
     * 装配期的错误就该在装配期响，而不是等某个用户翻历史时才发现少了一截。
     */
    private static final Map<ChatScene, ChatEntry> BY_SCENE = buildByScene();

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
     * 这个 scene 属于哪个入口；没有登记时返回 {@code null}。
     *
     * <p>返回 null 而不是抛异常，是为了让调用方能自己决定怎么办——
     * 生产代码里 {@link #scenes()} 才是入口，这个方法主要是给守卫测试用的
     * （见 {@code ChatEntryTest}）：它遍历 {@code ChatScene.values()} 一个个问过来，
     * 谁没登记就报谁，加一个 scene 忘了登记会直接测试失败。
     */
    public static ChatEntry of(ChatScene scene) {
        return BY_SCENE.get(scene);
    }

    /**
     * 所有入口的 link，按声明顺序。
     *
     * <p>给「只支持 a、b、c」这类报错文案用。原先调用方是手写
     * {@code ChatEntry.SLIM.link(), ChatEntry.MANUS.link()} 两个参数，
     * 而资源包里的文案也跟着写死了 {@code {1}} 和 {@code {2}} 两个槽位——
     * 加第三个入口时，报错信息会理直气壮地漏掉它，既不报错也没人会发现。
     * 现在槽位只有一个，装的是这里拼出来的完整清单。
     */
    public static List<String> links() {
        return Arrays.stream(values()).map(ChatEntry::link).toList();
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

    /**
     * 把「入口 → 场景」翻过来建成「场景 → 入口」。
     *
     * <p>一个 scene 出现在两个入口下时会抛异常而不是让后来的覆盖先前的：
     * 那意味着同一个会话会同时出现在侧边栏的两栏里，而「哪一栏才是对的」没有答案。
     * 这属于写错了代码，不是运行时状况，所以直接炸——和 {@code ChatEntry} 上面那条
     * 「漏登记不会有任何报错」是同一件事的两面：能炸的就不留给运行期去悄悄错。
     */
    private static Map<ChatScene, ChatEntry> buildByScene() {
        Map<ChatScene, ChatEntry> byScene = new EnumMap<>(ChatScene.class);
        for (ChatEntry entry : values()) {
            for (ChatScene scene : entry.scenes) {
                ChatEntry previous = byScene.put(scene, entry);
                if (previous != null) {
                    throw new IllegalStateException("场景 " + scene + " 在入口映射里出现了两次（"
                            + previous + " 和 " + entry + "）。一个场景只能属于一个入口，"
                            + "否则同一个会话会在侧边栏的两栏里各出现一次");
                }
            }
        }
        return Map.copyOf(byScene);
    }
}
