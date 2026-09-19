package com.purify.purifyaiagent.tools;

import com.purify.purifyaiagent.model.ActivityLevel;
import com.purify.purifyaiagent.model.UserProfile;
import com.purify.purifyaiagent.profile.UserProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
 * 键是 {@link #USER_ID_KEY}。这样做是必须的：如果把 userId 做成普通参数交给模型填，
 * 模型会照着自己编一个，用户这次存进去的画像下次就找不回来了。
 *
 * <p>由此带来一条硬性约束：<b>调用挂了本工具的 ChatClient 时，必须每次都带上 toolContext</b>。
 * Spring AI 的 {@code MethodToolCallback} 在方法声明了 ToolContext 却收到空上下文时，
 * 会直接抛 {@code IllegalArgumentException: ToolContext is required by the method as an argument}。
 * 这不是可以忽略的告警，是所有对话入口都要记得加的一行。
 *
 * <p><b>当前 userId 用的是会话 ID</b>（{@code chatId}），因为这个项目还没有登录体系，
 * chatId 是唯一的用户标识。代价是：换一个 chatId 就等于换了一个人，画像读不到。
 * 客户端复用同一个 chatId 就能跨轮次记住；将来接上登录后，只要把 SlimApp 里塞进
 * toolContext 的值换成真实用户 ID，这个类一个字都不用改。
 */
@Slf4j
public class UserProfileTool {

    /**
     * 从 {@link ToolContext} 里取用户标识用的键。
     *
     * <p>公开出来是为了让 {@code SlimApp} 直接引用它去填值，而不是在两边各写一遍字符串——
     * 这种键一旦拼错，表现是「画像永远读不到」，而且不报任何错，很难查。
     */
    public static final String USER_ID_KEY = "userId";

    private final UserProfileRepository repository;

    public UserProfileTool(UserProfileRepository repository) {
        this.repository = repository;
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

        Optional<UserProfile> found = repository.find(userId);
        if (found.isEmpty()) {
            log.debug("[UserProfileTool] userId={} 还没有画像", userId);
            // 画像不存在时不去查流水，省一次必然为空的查询
            return UserProfile.empty().describe(List.of());
        }

        UserProfile profile = found.get();
        List<UserProfile.WeightRecord> history = repository.findWeightHistory(userId);
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

        LocalDateTime now = LocalDateTime.now();
        UserProfile incoming = new UserProfile(age, heightCm, weightKg, goal,
                activityLevel, dietPreference, avoidFood, now);
        if (incoming.isBlank()) {
            return "这次没有传来任何可以保存的字段，画像保持不变。"
                    + "如果只是想看一下用户的情况，用 getUserProfile。";
        }

        UserProfile existing = repository.find(userId).orElseGet(UserProfile::empty);
        UserProfile merged = existing.merge(incoming);
        repository.save(userId, merged);

        // 记流水的前提是「体重确实变了」：模型经常把已知的体重再传一遍，
        // 逐次照记的话，流水会迅速被同一个数字淹没，趋势也就看不出来了
        boolean weightChanged = weightKg != null
                && (existing.weightKg() == null || Double.compare(existing.weightKg(), weightKg) != 0);
        if (weightChanged) {
            repository.appendWeight(userId, weightKg, now);
            log.info("[UserProfileTool] userId={} 体重 {} kg → {} kg，已记入体重历史",
                    userId, existing.weightKg(), weightKg);
        }
        log.info("[UserProfileTool] userId={} 画像已更新", userId);

        return "已保存。当前画像：\n" + merged.describe(repository.findWeightHistory(userId));
    }

    /**
     * 从工具上下文里取用户标识，取不到返回 {@code null}。
     *
     * <p>只在两端都出问题时才会走到：一是 SlimApp 忘了设 toolContext（那会在更早的地方
     * 被 Spring AI 拦下并抛异常），二是设了但键对不上。后一种正是拼错字符串的典型症状，
     * 所以这里返回一句话让模型转告用户，比抛一个栈要好排查得多。
     */
    private static String userIdOf(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(USER_ID_KEY);
        if (value == null || !StringUtils.hasText(value.toString())) {
            log.warn("[UserProfileTool] 工具上下文里没有 {}，无法确定这是哪位用户", USER_ID_KEY);
            return null;
        }
        return value.toString();
    }

    private static String missingUserId() {
        return "没能确定是哪个用户。这是服务端的装配问题（对话入口没有把用户标识传进工具上下文），"
                + "不是用户说错了什么，请如实告诉用户「暂时记不住这些信息」，不要假装已经存下了。";
    }
}
