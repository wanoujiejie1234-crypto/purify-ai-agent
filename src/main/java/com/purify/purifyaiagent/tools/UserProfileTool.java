package com.purify.purifyaiagent.tools;

import com.purify.purifyaiagent.agent.tool.ToolContexts;
import com.purify.purifyaiagent.model.ActivityLevel;
import com.purify.purifyaiagent.model.UserProfile;
import com.purify.purifyaiagent.profile.ProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 用户画像工具：让「轻语」记得住这个人的基本情况，并且跨会话留得住。
 *
 * <p>两个方法一个读一个写，覆盖了画像完整的生命周期：
 * <ul>
 *   <li>{@link #getUserProfile} —— 制定方案前先读，拿到年龄、身高、体重、目标、活动水平、
 *       饮食偏好、忌口，以及最近的体重变化；</li>
 *   <li>{@link #updateUserProfile} —— 聊到新信息就存，只更新这次听到的字段。</li>
 * </ul>
 *
 * <p><b>用户是谁，不归模型管。</b>两个方法都带一个 {@link ToolContext} 参数，
 * 而 Spring AI 会把它从工具的 JSON Schema 里摘掉——也就是说模型既看不到它，也传不了它。
 * 值由 {@code SlimApp} 在发起请求时通过 {@code .toolContext(...)} 塞进去，
 * 键是 {@link ToolContexts#USER_ID_KEY}。这样做是必须的：如果把 userId 做成普通参数交给模型填，
 * 模型会照着自己编一个，用户这次存进去的画像下次就找不回来了。
 *
 * <p>由此带来一条硬性约束：<b>调用挂了本工具的 ChatClient 时，必须每次都带上 toolContext</b>。
 * Spring AI 的 {@code MethodToolCallback} 在方法声明了 ToolContext 却收到空上下文时，
 * 会直接抛 {@code IllegalArgumentException: ToolContext is required by the method as an argument}。
 * 这不是可以忽略的告警，是所有对话入口都要记得加的一行。
 *
 * <p><b>userId 现在是真的用户 ID</b>：由 HTTP 层从 JWT 令牌里解出，
 * 经 {@code AgentRun#userId()} 传到 {@code toolContext}。在这之前它用的是会话 ID
 * （{@code chatId}），代价是「换一个会话就等于换了一个人」——同一个用户在轻语里说过的
 * 身高体重，新开一个会话就查不到了。现在画像真正按人存，跨会话、跨链路都读得到。
 *
 * <p>这个类本身对这次切换<b>一个字都没改</b>：它一直只认 {@link ToolContexts#USER_ID_KEY} 这个键，
 * 值从哪里来是外面的事。这正是当初把用户标识做成工具上下文而不是模型参数的回报。
 */
@Slf4j
public class UserProfileTool {

    /**
     * 读写都走 {@link ProfileService}，不直接碰仓储。
     *
     * <p>设置页那条路径（{@code ProfileController}）用的是同一个 service——
     * 「先读再合并」「体重变了才记流水」这些规则只写一遍，
     * 两个入口才不会慢慢走偏。
     */
    private final ProfileService profileService;

    public UserProfileTool(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 读画像。
     *
     * <p>返回值是拼好的中文文本而不是 JSON：模型要的是「用户多高多重、想减到多少」这些事实，
     * JSON 只会多一层它要自己解析的结构。
     */
    @Tool(description = "读取当前用户的画像：年龄、身高、体重、BMI、减重目标、日常活动水平、"
            + "饮食偏好、忌口，以及最近的体重变化记录。"
            + "在制定任何饮食或运动方案之前，必须先调用本工具；"
            + "回答中只要涉及用户本人的情况（多高多重、想减多少、能吃什么），也应该先调用它再作答，"
            + "不要凭对话记忆或猜测给出数字。")
    public String getUserProfile(ToolContext toolContext) {
        String userId = userIdOf(toolContext);
        if (userId == null) {
            return "读取用户画像失败：" + missingUserId();
        }

        UserProfile profile = profileService.read(userId);
        if (profile.isBlank()) {
            log.debug("[UserProfileTool] userId={} 还没有画像", userId);
            // 画像还是空的就别去查流水了，省一次必然为空的查询
            return profile.describe(List.of());
        }

        List<UserProfile.WeightRecord> history = profileService.history(userId);
        log.debug("[UserProfileTool] userId={} 读到画像，体重流水 {} 条", userId, history.size());
        return profile.describe(history);
    }

    /**
     * 写画像。
     *
     * <p>所有字段都是可选的，因为模型每次只会听到其中一两个——
     * 这轮说了体重就只传体重，剩下的靠 {@link UserProfile#merge} 从库里已有的那份补上。
     * 这也是为什么这里必须先读一次再写：不读的话，没传的字段会被当成「用户要求清空」。
     *
     * <p>没有任何字段被传时直接返回提示，不做无意义的写库——
     * 模型偶尔会在没有新信息的时候也调一次，让它知道这没必要。
     */
    @Tool(description = "保存或更新当前用户的画像。用户提到自己的年龄、身高、体重、减重目标、"
            + "日常活动水平、饮食偏好或忌口时调用它。"
            + "只传这次真正听到的字段，没提到的字段保持原值：不要为了「补全」而重复传已知的值，"
            + "也不要在没有新信息时调用。体重和上次记录不一样时会自动记入体重历史。")
    public String updateUserProfile(
            @ToolParam(description = "年龄（岁），例如 30", required = false) Integer age,
            @ToolParam(description = "身高（厘米），例如 170", required = false) Double heightCm,
            @ToolParam(description = "体重（公斤），例如 71.5", required = false) Double weightKg,
            @ToolParam(description = "减重目标，用用户自己的说法，例如「三个月减到 65 公斤」", required = false) String goal,
            @ToolParam(description = "日常活动水平。只能是这几个值之一："
                    + "SEDENTARY（久坐，几乎不运动）、LIGHT（每周运动 1-3 次）、"
                    + "MODERATE（每周运动 3-5 次）、ACTIVE（每周运动 6-7 次）、"
                    + "VERY_ACTIVE（体力工作或每天高强度训练）", required = false) ActivityLevel activityLevel,
            @ToolParam(description = "饮食偏好，用用户自己的说法，例如「爱吃面食」「不吃辣」「素食」", required = false) String dietPreference,
            @ToolParam(description = "忌口或过敏，例如「海鲜过敏」「乳糖不耐受」", required = false) String avoidFood,
            ToolContext toolContext) {

        String userId = userIdOf(toolContext);
        if (userId == null) {
            return "保存用户画像失败：" + missingUserId();
        }

        // updatedAt 传 null：时间戳由 ProfileService 在写库那一刻统一盖，
        // 调用方传什么都会被覆盖——在这里写 now() 只会让人以为它有用
        UserProfile incoming = new UserProfile(age, heightCm, weightKg, goal,
                activityLevel, dietPreference, avoidFood, null);
        if (incoming.isBlank()) {
            return "这次没有传来任何可以保存的字段，画像保持不变。"
                    + "如果只是想看一下用户的情况，用 getUserProfile。";
        }

        // 合并、记流水这两件事在 service 里，和设置页那条路径共用同一份实现
        UserProfile merged = profileService.update(userId, incoming);

        return "已保存。当前画像：\n" + merged.describe(profileService.history(userId));
    }

    /**
     * 从工具上下文里取用户标识，取不到返回 {@code null}。
     *
     * <p>取值逻辑搬到了 {@link ToolContexts#userIdOf}，和资料库那几个工具共用一份。
     * 键名收在一个类里，「拼错了字符串」这种不报错、只表现为「画像永远读不到」的问题
     * 才算有了根除的地方。
     *
     * <p>取不到时返回一句话让模型转告用户，比抛一个栈要好排查得多。
     */
    private static String userIdOf(ToolContext toolContext) {
        String userId = ToolContexts.userIdOf(toolContext);
        if (userId == null) {
            log.warn("[UserProfileTool] 工具上下文里没有 {}，无法确定这是哪位用户", ToolContexts.USER_ID_KEY);
        }
        return userId;
    }

    private static String missingUserId() {
        return "没能确定是哪个用户。这是服务端的装配问题（对话入口没有把用户标识传进工具上下文），"
                + "不是用户说错了什么，请如实告诉用户「暂时记不住这些信息」，不要假装已经存下了。";
    }
}
