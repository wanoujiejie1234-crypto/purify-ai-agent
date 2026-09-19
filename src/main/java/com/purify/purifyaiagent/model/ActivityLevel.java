package com.purify.purifyaiagent.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * 日常活动水平。
 *
 * <p>用枚举而不是自由文本，是因为它会被工具当成参数类型：Spring AI 会把这个枚举的常量名
 * 直接生成进工具的 JSON Schema（{@code "enum": ["SEDENTARY", ...]}），模型只能在给定选项里挑，
 * 挑不出「经常动一动」这种既没法比较、也没法用来估算消耗的说法。
 *
 * <p>数据库里存的是枚举名（{@code SEDENTARY}），给人看的是 {@link #getLabel()}
 * （{@code 久坐}）——两套值各司其职：前者要稳定、好比较，后者要好读。
 */
@Slf4j
public enum ActivityLevel {

    /** 几乎不动。基础代谢之外几乎没有额外消耗。 */
    SEDENTARY("久坐", "几乎不运动，日常以坐着为主"),

    /** 每周动几次，或者通勤本身就有一定运动量。 */
    LIGHT("轻度活动", "每周运动 1-3 次，或每天走路通勤"),

    /** 大多数有健身习惯的人落在这里。 */
    MODERATE("中度活动", "每周运动 3-5 次"),

    /** 训练频率已经很高了。 */
    ACTIVE("高度活动", "每周运动 6-7 次"),

    /** 体力工作，或者每天都在做高强度训练。 */
    VERY_ACTIVE("极高活动", "体力工作，或每天高强度训练");

    private final String label;

    private final String hint;

    ActivityLevel(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    /** 给人看的中文名，拼进工具返回值里。 */
    public String getLabel() {
        return label;
    }

    /** 判断依据，同样会拼进工具返回值，让模型知道自己当初是按什么标准定的这一档。 */
    public String getHint() {
        return hint;
    }

    /**
     * 宽松解析：先按枚举名，再按中文名，都认不出来就返回 {@code null}。
     *
     * <p>之所以不直接 {@code valueOf} 让它抛：这个值是从数据库里读回来的，
     * 而数据库是可以用手工 SQL 改的。读到一行别人手动填了「中度」的记录时，
     * 正确的做法是这一项当没有、其余照常返回，而不是让整个画像查询炸掉。
     */
    public static ActivityLevel parse(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        for (ActivityLevel level : values()) {
            if (level.name().equalsIgnoreCase(trimmed) || level.label.equals(trimmed)) {
                return level;
            }
        }
        log.warn("[ActivityLevel] 认不出的活动水平「{}」，本次按未填写处理；可选值：{}",
                value, java.util.Arrays.toString(values()));
        return null;
    }
}
