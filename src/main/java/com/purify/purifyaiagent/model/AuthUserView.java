package com.purify.purifyaiagent.model;

import com.purify.purifyaiagent.auth.UserAccount;
import com.purify.purifyaiagent.auth.UserRole;

/**
 * 给前端看的「我是谁」。
 *
 * <p><b>这个类型存在的唯一理由是「不要直接把 {@link UserAccount} 序列化出去」</b>——
 * 那个记录里带着密码哈希。手滑一次就是把 BCrypt 哈希发给了浏览器，
 * 而它看起来只是一个多了几个字段的 JSON，不会有人注意到。
 * 中间隔一层显式的投影，漏掉字段会表现为「前端少了个东西」而不是「密码泄露了」。
 *
 * <p>{@code role} 直接给枚举名（{@code SUPER} / {@code NORMAL}），前端用它判断
 * 「知识库入口要不要显示」。前端那个判断<b>只是界面显隐</b>，真正的拦截在后端——
 * 改一下 localStorage 就能看到入口，但点了照样 403。
 *
 * @param id       用户 id（十进制字符串，见 {@code LoginUser} 里为什么不是 Long）
 * @param username 登录名
 * @param nickname 昵称，可能为 null
 * @param avatar   头像 URL，可能为 null
 * @param role     角色
 */
public record AuthUserView(String id, String username, String nickname, String avatar, UserRole role) {

    public static AuthUserView from(UserAccount account) {
        return new AuthUserView(
                String.valueOf(account.id()),
                account.username(),
                account.nickname(),
                account.avatar(),
                account.role());
    }
}
