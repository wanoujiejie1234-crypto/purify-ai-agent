package com.purify.purifyaiagent.model;

/**
 * 设置页提交的画像。
 *
 * <p><b>语义是「整体替换」，不是「只改传了的字段」。</b>设置页上显示的就是全部内容，
 * 用户按下保存时写回去的自然也该是全部内容——所以 {@code null} 在这条路径上表示
 * <b>清空</b>，而不是「这次没提」。这一点和对话那条路径上的工具正好相反，
 * 理由见 {@code ProfileService} 的类注释（简言之：合并语义下用户永远清不掉一个字段）。
 *
 * <p>由此带来一个约定：**表单必须每次把七个字段都发上来**。
 * 只发改动过的字段的话，剩下的六个会被当成「清空」。
 *
 * <p>{@code activityLevel} 是 {@code String} 而不是 {@link ActivityLevel}，
 * 有两个原因：
 * <ul>
 *   <li>用户选「未填写」时前端会传空串，Jackson 直接映射成枚举会在解析阶段就抛 400，
 *       错误信息还是一句用户看不懂的 {@code Cannot deserialize value of type...}；</li>
 *   <li>{@link ActivityLevel#parse} 本来就认「枚举名」和「中文标签」两种写法，
 *       而且认不出来时返回 null 而不是抛异常。</li>
 * </ul>
 */
public record UserProfileUpdateRequest(
        Integer age,
        Double heightCm,
        Double weightKg,
        String goal,
        String activityLevel,
        String dietPreference,
        String avoidFood) {

    /**
     * 转成领域对象。
     *
     * <p>空白字符串一律折成 {@code null}：表单里被清空的输入框给的是 {@code ""}，
     * 而库里空值一律用 NULL 表示（{@link UserProfile} 的 {@code text()} 也按这个约定判断）。
     * 不折的话库里会攒下一堆 {@code ""}，虽然读出来效果一样，但手工查库时会很迷惑。
     *
     * <p>{@code updatedAt} 传 null，由 {@code ProfileService} 在写库那一刻统一盖。
     */
    public UserProfile toProfile() {
        return new UserProfile(age, heightCm, weightKg, blankToNull(goal),
                ActivityLevel.parse(activityLevel), blankToNull(dietPreference), blankToNull(avoidFood), null);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
