package com.purify.purifyaiagent.model;

import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 用户画像：一个用户的当前状态。
 *
 * <p><b>每个字段都可以为 null</b>，这不是偷懒，而是这张表的常态——用户不会一次性报全，
 * 通常是聊到哪问到哪，身高体重先来、忌口过两天才提。所以 null 表达的是「还没问到」，
 * 不是「没有」。
 *
 * <p>也正因为如此，{@link #merge(UserProfile)} 的规则是「非空才覆盖」：
 * 模型每次只传它这轮听到的字段，剩下的原样保留。这条规则是整个画像功能里最容易写错的地方，
 * 写成整体覆盖的话，用户第二次只说了句体重，身高目标忌口就全被抹成 null 了。
 *
 * <p>{@link #describe(List)} 负责把画像渲染成给模型看的文本。渲染放在这里而不是工具里，
 * 是为了让「存进去的值」和「模型看到的值」在同一处定义，改格式不用两头找。
 */
public record UserProfile(
        Integer age,
        Double heightCm,
        Double weightKg,
        String goal,
        ActivityLevel activityLevel,
        String dietPreference,
        String avoidFood,
        LocalDateTime updatedAt) {

    /** 时间是给人看的，秒级足够，也给模型省几个 token。 */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 一份什么都不知道的空画像，用来兜住「数据库里没有这个用户」。 */
    public static UserProfile empty() {
        return new UserProfile(null, null, null, null, null, null, null, null);
    }

    /**
     * 用 {@code incoming} 里<b>非空</b>的字段覆盖自己，返回一份新画像。
     *
     * <p>字符串另外把「空白」也当成没填：模型很容易在没话找话时传个 {@code ""} 或 {@code " "} 进来，
     * 那和没传是同一个意思，不该把用户原来的忌口冲掉。
     *
     * <p>注意 {@code incoming.activityLevel} 为 null 时保持原值，但显式传 {@link ActivityLevel}
     * 永远是覆盖——「改成久坐」这种降档需求是真实存在的，不能当成没填。
     *
     * @param incoming 这轮新听到的信息，只填要改的字段
     */
    public UserProfile merge(UserProfile incoming) {
        return new UserProfile(
                incoming.age != null ? incoming.age : this.age,
                incoming.heightCm != null ? incoming.heightCm : this.heightCm,
                incoming.weightKg != null ? incoming.weightKg : this.weightKg,
                text(incoming.goal) ? incoming.goal : this.goal,
                incoming.activityLevel != null ? incoming.activityLevel : this.activityLevel,
                text(incoming.dietPreference) ? incoming.dietPreference : this.dietPreference,
                text(incoming.avoidFood) ? incoming.avoidFood : this.avoidFood,
                incoming.updatedAt != null ? incoming.updatedAt : this.updatedAt);
    }

    /** 业务字段是否一个都没有。时间不算——空的画像也会有更新时间和创建时间。 */
    public boolean isBlank() {
        return age == null && heightCm == null && weightKg == null
                && !text(goal) && activityLevel == null
                && !text(dietPreference) && !text(avoidFood);
    }

    /**
     * BMI = 体重(kg) / 身高(m)^2。
     *
     * <p>算不出来（缺身高或体重）或者算出来离谱（明显是录错了，比如把身高填成 17cm）时返回 null，
     * 不返回一个会把模型带偏的数。
     */
    public Double bmi() {
        if (weightKg == null || heightCm == null || heightCm < 50 || heightCm > 250 || weightKg <= 0) {
            return null;
        }
        double meters = heightCm / 100;
        return weightKg / (meters * meters);
    }

    /**
     * 渲染成给模型看的中文文本。
     *
     * <p>没填的字段直接不出现，而不是写「年龄：未知」——摆一排「未知」不但占 token，
     * 还会让模型觉得「这些字段是存在的，只是值是未知」，反而更容易在回答里编一个出来。
     *
     * @param history 最近的体重流水，按时间倒序（最新的在前），可以为空
     */
    public String describe(List<WeightRecord> history) {
        if (isBlank()) {
            // 说清楚「这是空的」以及「接下来该干什么」。只返回空串的话，模型会以为自己拿到了画像，
            // 然后拿空气当依据开始给建议
            return "还没有这位用户的画像，任何一项都没有记录。请先通过对话了解："
                    + "年龄、身高、体重、目标、日常活动水平、饮食偏好、忌口，"
                    + "问到之后用 updateUserProfile 存下来。";
        }

        StringBuilder text = new StringBuilder("用户画像：\n");
        append(text, "年龄", age == null ? null : age + " 岁");
        append(text, "身高", heightCm == null ? null : heightCm + " cm");
        append(text, "体重", weightKg == null ? null : weightKg + " kg");

        Double bmi = bmi();
        if (bmi != null) {
            append(text, "BMI", String.format("%.1f（%s）", bmi, bmiCategory(bmi)));
        }
        append(text, "目标", goal);
        append(text, "活动水平", activityLevel == null ? null
                : activityLevel.getLabel() + "（" + activityLevel.getHint() + "）");
        append(text, "饮食偏好", dietPreference);
        append(text, "忌口", avoidFood);

        if (history != null && !history.isEmpty()) {
            text.append("- 体重记录（最近 ").append(history.size()).append(" 次，新的在前）：\n");
            for (WeightRecord record : history) {
                text.append("    ").append(record.recordedAt().format(DATE))
                        .append("  ").append(record.weightKg()).append(" kg\n");
            }
            if (history.size() >= 2) {
                // 趋势比流水更好用：模型不用自己去比对两个数字，也就不会看反方向
                double newest = history.get(0).weightKg();
                double oldest = history.get(history.size() - 1).weightKg();
                double delta = newest - oldest;
                text.append("    这段时间").append(delta < 0 ? "减少 " : delta > 0 ? "增加 " : "持平 ")
                        .append(String.format("%.1f", Math.abs(delta))).append(" kg\n");
            }
        }
        if (updatedAt != null) {
            text.append("- 画像最近更新：").append(updatedAt.format(DATE)).append('\n');
        }
        return text.toString();
    }

    /** 体重流水的一条。只记录「什么时候多少公斤」，不记别的。 */
    public record WeightRecord(double weightKg, LocalDateTime recordedAt) {
    }

    /** 中国成人 BMI 分级，用来把数字翻译成一句人话。 */
    private static String bmiCategory(double bmi) {
        if (bmi < 18.5) {
            return "偏瘦";
        }
        if (bmi < 24) {
            return "正常";
        }
        return bmi < 28 ? "超重" : "肥胖";
    }

    private static void append(StringBuilder text, String label, String value) {
        if (StringUtils.hasText(value)) {
            text.append("- ").append(label).append('：').append(value).append('\n');
        }
    }

    private static boolean text(String value) {
        return StringUtils.hasText(value);
    }
}
