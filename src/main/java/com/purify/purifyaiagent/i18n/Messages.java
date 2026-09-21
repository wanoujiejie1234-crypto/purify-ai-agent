package com.purify.purifyaiagent.i18n;

import org.springframework.context.MessageSource;

import java.util.Locale;

/**
 * 一份「已经绑好语言」的文案出口。
 *
 * <p><b>为什么要有这个类，而不是到处调 {@code MessageSource#getMessage(..., LocaleContextHolder.getLocale())}。</b>
 *
 * <p>智能体的循环跑在 Reactor 的调度线程上（见 {@code BaseAgent#runStream} 的
 * {@code subscribeOn(Schedulers.boundedElastic())}），请求线程上的
 * {@code LocaleContextHolder} 在那里<b>不可见</b>——这是这个项目里已经踩过两次的坑
 * （另外两次是登录用户和用户画像，见 {@code auth/AuthInterceptor} 和 {@code AgentRun#userId}
 * 的注释）。在那种地方读 {@code LocaleContextHolder} 不会报错，只会静默地
 * 回落到 JVM 默认语言，表现是「英文界面上，智能体跑到一半冒出几句中文」。
 *
 * <p>所以规矩和 {@code userId} 一样：<b>语言在请求线程上取一次，然后跟着这次 run 走。</b>
 * 这个类的实例就是那个「取好之后带下去」的值——不可变、可自由跨线程传递，
 * 拿到它的人不需要再知道任何上下文。
 *
 * <p>取值失败（键写错了、某个语言的文件里漏了这条）时返回键本身而不是抛异常：
 * 界面上看到 {@code error.auth.badCredentials} 很难看，但比一次 500 好，
 * 而且一眼就能看出是漏了哪条文案。
 */
public final class Messages {

    private final MessageSource source;
    private final Locale locale;

    public Messages(MessageSource source, Locale locale) {
        this.source = source;
        this.locale = locale == null ? Locale.getDefault() : locale;
    }

    /** 按当前语言取一条文案，{@code args} 对应文案里的 {0} {1}。 */
    public String get(String key, Object... args) {
        return source.getMessage(key, args, key, locale);
    }

    /** 这次 run 用的是哪种语言。需要按语言分支（而不是按文案）时用它。 */
    public Locale locale() {
        return locale;
    }

    /**
     * 是不是中文。
     *
     * <p>给「按语言选一个东西」的场景用，最典型的是<b>选哪一份系统提示词模板</b>
     * （{@code slim-app-system.st} vs {@code slim-app-system-en.st}）。
     * 那种地方没有「一句话」可以放进 properties——整份模板都是另一种语言写的，
     * 只能按语言选文件。
     *
     * <p>判据只看语言主标签（{@code zh}），不看国家/地区：{@code zh-TW}、{@code zh-HK}
     * 都算中文，用中文那份模板是对的。非中文一律走英文那份——
     * 现在只有两种语言，将来加了第三种，这里会自然地把它当成「非中文」处理，
     * 而那正是「英文模板总比中文模板对其余语言更可能可用」这个判断想要的结果。
     */
    public boolean isChinese() {
        return locale.getLanguage().startsWith("zh");
    }

    /**
     * 换一种语言，得到另一份。
     *
     * <p>给「一次请求里要按不同语言产文案」的场景用——目前没有这种场景，
     * 但语言切换要生效就得有一条从 locale 到 Messages 的路，留着比以后到处找补好。
     */
    public Messages withLocale(Locale other) {
        return new Messages(source, other);
    }
}
