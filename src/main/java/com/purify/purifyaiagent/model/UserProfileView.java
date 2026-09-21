package com.purify.purifyaiagent.model;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 用户画像的对外结构，给设置页用。
 *
 * <p>为什么不直接返回 {@link UserProfile}：那个 record 是**给模型看的**——
 * {@code describe()} 渲染成中文段落、字段随时可能因为提示词的措辞而调整。
 * 拿它当 HTTP 契约的话，改一次提示词就会顺手改坏前端。
 * 这里显式列一遍字段，两边就解耦了。
 *
 * <p>{@code activityLevel} 传的是**枚举名**（{@code SEDENTARY}），不是中文标签。
 * 标签是用来显示的，而且 {@link ActivityLevel#parse} 认的是枚举名和标签两种写法——
 * 走枚举名这条更稳，将来标签的措辞要改也不会把回写弄坏。
 *
 * <p>{@code activityLevelOptions} 放在同一个响应里，是为了让设置页开一次抽屉
 * 只发一个请求。它本来就该和画像一起取：下拉框的选项如果由前端另写一份，
 * 早晚会和后端的枚举对不上——而那种不一致的表现是「某个选项保存不了」，很难查。
 */
public record UserProfileView(
        Integer age,
        Double heightCm,
        Double weightKg,
        String goal,
        String activityLevel,
        String dietPreference,
        String avoidFood,
        Double bmi,
        LocalDateTime updatedAt,
        List<WeightPoint> weightHistory,
        List<ActivityLevelOption> activityLevelOptions) {

    /** 一次体重记录。名字不带 Record 后缀是为了不撞 {@code java.lang.Record} 的阅读负担。 */
    public record WeightPoint(Double weightKg, LocalDateTime recordedAt) {
    }

    /** 活动水平下拉框的一项。{@code value} 是要回传的枚举名，{@code label} 是给人看的。 */
    public record ActivityLevelOption(String value, String label, String hint) {
    }

    /**
     * 由领域对象拼出对外结构。
     *
     * <p>{@code bmi} 直接取 {@link UserProfile#bmi()}：它会在一眼就不对劲的数据上返回 null
     * （比如身高填成了 17），那种情况下前端显示「—」比显示一个错数好。
     */
    public static UserProfileView from(UserProfile profile, List<UserProfile.WeightRecord> history) {
        List<WeightPoint> points = history == null ? List.of() : history.stream()
                .map(record -> new WeightPoint(record.weightKg(), record.recordedAt()))
                .toList();

        List<ActivityLevelOption> options = Arrays.stream(ActivityLevel.values())
                .map(level -> new ActivityLevelOption(level.name(), level.getLabel(), level.getHint()))
                .toList();

        return new UserProfileView(
                profile.age(),
                profile.heightCm(),
                profile.weightKg(),
                profile.goal(),
                // 枚举名；没填就是 null，前端据此选「未填写」那一项
                profile.activityLevel() == null ? null : profile.activityLevel().name(),
                profile.dietPreference(),
                profile.avoidFood(),
                profile.bmi(),
                profile.updatedAt(),
                points,
                options);
    }
}
