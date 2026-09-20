package com.purify.purifyaiagent.auth;

import java.time.LocalDateTime;

/**
 * {@code user} 表里的一行。和 {@link LoginUser} 的区别是<b>这个带密码哈希，那个不带</b>。
 *
 * <p>分成两个类型是有意的：密码哈希只应该出现在「验证密码」这一件事的附近
 * （{@link AuthService#login}），而不应该跟着当前用户在项目里到处传。
 * 一个类型的话，某个 controller 顺手把「当前用户」序列化给前端就会把它带出去。
 *
 * <p>{@link #isDeleted} 和 {@link #status} 分开存：前者是「账号被删了」，
 * 后者是「管理员把账号禁用了」。对登录的判定来说两者都是「不许进」，
 * 但对用户的提示不一样——被禁用的账号说「请联系管理员」是有意义的，
 * 而账号已被删除时说这句会让人白等。
 *
 * @param isDeleted 逻辑删除标记。登录查询会把它过滤掉，
 *                  所以只有 {@code RootAgentInitializer} 会看到 {@code true} 的值
 */
public record UserAccount(
        Long id,
        String username,
        String password,
        String nickname,
        String avatar,
        String email,
        String phone,
        UserRole role,
        boolean enabled,
        boolean isDeleted,
        LocalDateTime lastLoginTime) {

    /** 登录放行条件。两件事都满足才算「这个账号能用」。 */
    public boolean canLogin() {
        return enabled && !isDeleted;
    }

    /** 展示名：有昵称用昵称，没有就退回用户名。头像上的首字母也用它。 */
    public String displayName() {
        return (nickname == null || nickname.isBlank()) ? username : nickname;
    }
}
