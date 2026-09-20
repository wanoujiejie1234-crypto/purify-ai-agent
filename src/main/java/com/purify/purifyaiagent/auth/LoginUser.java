package com.purify.purifyaiagent.auth;

/**
 * 当前请求的登录用户。由 {@link AuthInterceptor} 从令牌里解出来、挂到请求属性上，
 * 再由 {@link LoginUserArgumentResolver} 交给 controller 的 {@code @CurrentUser} 参数。
 *
 * <p><b>{@link #id()} 是 String，不是 Long</b>，虽然 {@code user.id} 是 BIGINT。
 * 这是有意的：会话表和用户画像表的 {@code user_id} 都是 {@code VARCHAR(64)}，
 * 而且它们在你那台共享 MySQL 上已经建好了、还存着登录前的旧数据——
 * 改列类型要走 Java 侧的 schema 守卫，收益为零。十进制数字串（最长 19 位）
 * 放进 VARCHAR(64) 绰绰有余。让 id 在这一层就是字符串，
 * 调用方不用在每一处都写一遍 {@code String.valueOf(...)}，少一个能写错的地方。
 *
 * <p><b>只放鉴权真正需要的东西</b>：id、username、role。不放密码哈希、不放邮箱、
 * 不放 status——它们在这一层没有用途，而多放一个字段就多一个「不小心把它序列化给前端」的机会。
 * {@code username} 也在其中：它<b>只用于日志和界面展示，不参与任何鉴权判断</b>，
 * 鉴权只看 {@link #role()}。
 *
 * @param id       用户 id 的十进制字符串，对应 {@code user.id}
 * @param username 登录名，仅用于日志与展示
 * @param role     角色，鉴权唯一的依据
 */
public record LoginUser(String id, String username, UserRole role) {

    /**
     * 请求属性名。公开是为了让 {@link AuthInterceptor} 和
     * {@link LoginUserArgumentResolver} 引用同一个常量——写字符串字面量的话，
     * 拼错不会报错，表现是「每个 @CurrentUser 参数都是 null」。
     */
    public static final String ATTRIBUTE = LoginUser.class.getName();

    /** 是不是超级用户。知识库模块的入口判断都走它。 */
    public boolean isAdmin() {
        return role == UserRole.SUPER;
    }

    /** 给日志用。不要拿它做任何判断。 */
    public String describe() {
        return username + "#" + id + "(" + role + ")";
    }
}
