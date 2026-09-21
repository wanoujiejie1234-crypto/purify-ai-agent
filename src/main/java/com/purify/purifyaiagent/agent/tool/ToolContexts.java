package com.purify.purifyaiagent.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具上下文的键，以及构造它的唯一入口。
 *
 * <p>工具靠 {@link ToolContext} 知道「现在跟我说话的是谁、在哪次会话里」。
 * 这些值由服务端在发起请求时塞进去，**模型既看不到也传不了**——
 * 这正是关键：让模型自己填 userId，它会编一个出来，这次存下的东西下次就找不回来了。
 *
 * <h2>为什么要有这个类，而不是在各个入口各写一遍 Map.of</h2>
 *
 * <p><b>因为「空 map」是个会炸的输入，不是一个可以忽略的边界。</b>
 * Spring AI 的 {@code MethodToolCallback} 在调用一个声明了 {@code ToolContext} 参数的
 * 方法之前，会做一次检查（{@code validateToolContextSupport}）：
 *
 * <pre>
 *   if (方法接受 ToolContext &amp;&amp; 传进来的上下文是空的) {
 *       throw new IllegalArgumentException("ToolContext is required by the method as an argument");
 *   }
 * </pre>
 *
 * <p>也就是说，某个入口漏传上下文时，代价<b>不是「画像读不到」，而是整轮对话直接失败</b>。
 * 原先 {@code SlimApp} 在 userId 为空时返回的是 {@code Map.of()}（空 map），
 * 本地手测永远走不到那条分支（聊天接口都要求登录），所以这个雷一直没响——
 * 而它一旦响，症状是一个 IllegalArgumentException，看不出跟「用户身份」有什么关系。
 *
 * <p>所以这里做了两件事：<b>常量占位键保证 map 永远非空</b>，以及把键名收在一处。
 * 键名拼错是另一类难查的问题——表现是「画像永远读不到」，同样不报错。
 */
@Slf4j
public final class ToolContexts {

    /** 发起这次请求的用户 id（十进制字符串）。 */
    public static final String USER_ID_KEY = "userId";

    /** 这次请求所属的会话 id。工具要把产出归档到资料库时要用它。 */
    public static final String CHAT_ID_KEY = "chatId";

    /**
     * 占位键。
     *
     * <p>它的值是什么不重要，存在本身就是意义：只要它在，map 就非空，
     * 上面那个 {@code validateToolContextSupport} 就不会抛。
     * 用常量键而不是「随便塞一个 null 进去」——{@code Map.of} 遇到 null 会立刻抛
     * {@code NullPointerException}，而 {@code ToolContext} 的底层实现也可能对内容
     * 做防御性拷贝。
     */
    private static final String PRESENT_KEY = "purifyToolContext";

    private ToolContexts() {
    }

    /**
     * 构造一份工具上下文。**所有对话入口都必须用它**，不要自己拼 Map。
     *
     * @param userId 用户 id，可以为空（工具会退化成「认不出是谁」并如实告诉模型）
     * @param chatId 会话 id，可以为空（资料库那类需要归档的工具会跳过记录）
     */
    public static Map<String, Object> of(String userId, String chatId) {
        Map<String, Object> context = new HashMap<>();
        context.put(PRESENT_KEY, Boolean.TRUE);
        putIfPresent(context, USER_ID_KEY, userId);
        putIfPresent(context, CHAT_ID_KEY, chatId);
        return context;
    }

    /** 在已有上下文上补充键值。给 {@code PurifyManus} 那种「先拿一份基础上下文再追加」的场景用。 */
    public static void putIfPresent(Map<String, Object> context, String key, String value) {
        if (StringUtils.hasText(value)) {
            context.put(key, value);
        }
    }

    /**
     * 取用户 id，取不到返回 {@code null}。
     *
     * <p>只在两端都出问题时才会取不到：一是入口忘了调 {@link #of}（那会在更早的地方被
     * Spring AI 拦下并抛异常），二是传了但键对不上。后一种正是键名拼错的症状，
     * 所以这里返回 null 让调用方回一句话给模型，比抛栈好排查。
     */
    public static String userIdOf(ToolContext toolContext) {
        return valueOf(toolContext, USER_ID_KEY);
    }

    /** 取会话 id，取不到返回 {@code null}。 */
    public static String chatIdOf(ToolContext toolContext) {
        return valueOf(toolContext, CHAT_ID_KEY);
    }

    private static String valueOf(ToolContext toolContext, String key) {
        Object value = toolContext == null ? null : toolContext.getContext().get(key);
        if (value == null || !StringUtils.hasText(value.toString())) {
            return null;
        }
        return value.toString();
    }
}
