package com.purify.purifyaiagent.resource;

import java.util.Locale;

/**
 * 资料库里一条记录是怎么来的。
 *
 * <p>这个区分是有用的，不只是分类：界面要据此决定「能不能直接点开」。
 * {@link #PDF} 和 {@link #DOWNLOAD} 都带一个能打开的地址，
 * 而 {@link #WRITTEN} 没有——它落在本地一个没有对外映射的目录里，
 * 只能走后端那个带鉴权的下载接口（理由见 {@code FileConstant#WORK_FILE_DIR}）。
 */
public enum ResourceKind {

    /** 智能体生成的 PDF，已经传到对象存储。 */
    PDF,

    /** 从网上抓回来的资源，落在本地下载目录。 */
    DOWNLOAD,

    /** writeFile 写出来的文本文件，落在本地工作目录。 */
    WRITTEN;

    /**
     * 给人看的名字在 {@code messages*.properties} 里的键。
     *
     * <p><b>是键不是句子</b>，理由同 {@code ApiException}：这个枚举在业务代码里到处用，
     * 而那里拿不到当前语言。翻的动作统一发生在 controller 那一层——只有它同时握着
     * {@code MessageResolver} 和请求的语言。
     *
     * <p>它和 {@code ActivityLevel} 那批枚举标签<b>不一样</b>，别照着那边的做法处理：
     * 那些标签参与匹配（{@code parse} 拿它当依据），翻了会写不进库；
     * 这一个纯粹是展示，翻它不改变任何行为，所以它进了语言包。
     */
    public String getLabelKey() {
        return "resource.kind." + name();
    }

    /**
     * 宽松解析：认不出来返回 {@code null} 而不是抛异常。
     *
     * <p>读的是数据库里的字符串，而库是可以手工改的；让一行数据把整个资料库列表
     * 查崩掉，比把这一项当成未知要好得多（同 {@code ActivityLevel#parse} 的取向）。
     */
    public static ResourceKind parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
