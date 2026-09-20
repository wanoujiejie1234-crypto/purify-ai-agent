package com.purify.purifyaiagent.auth;

/**
 * 用户角色。只有两档，而且<b>不打算做成可扩展的权限体系</b>——
 * 需求就是「超级用户能管知识库，其他人都不能」这一条判断题。
 *
 * <p>存进数据库、写进令牌的都是枚举名（{@code SUPER} / {@code NORMAL}），不是序号：
 * 用序号的话，以后往中间插一个角色就会把已有数据的含义整体挪位，
 * 而那种错误不会报任何异常，只是所有人突然都变成了别的角色。
 *
 * <p>解析失败一律退回 {@link #NORMAL}，不抛异常。理由：这个值来自数据库和令牌两处，
 * 两处都可能因为手工改库或换版本而出现不认识的值。退回最小权限是安全的失败方向——
 * 抛异常的后果是整个请求 500，而那时用户本来只是该看到一个「没有权限」。
 */
public enum UserRole {

    /** 超级用户。当前只有知识库模块受它管，见 {@code KnowledgeBaseController}。 */
    SUPER,

    /** 普通用户。能聊天、能看自己的历史，仅此而已。 */
    NORMAL;

    /** 宽松解析：认不出来就是 {@link #NORMAL}。 */
    public static UserRole parse(String value) {
        if (value == null) {
            return NORMAL;
        }
        for (UserRole role : values()) {
            if (role.name().equalsIgnoreCase(value.trim())) {
                return role;
            }
        }
        return NORMAL;
    }
}
