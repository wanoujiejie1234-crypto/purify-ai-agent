package com.purify.purifyaiagent.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 造 {@link Messages} 的地方：把「当前是什么语言」这件事收在一个入口。
 *
 * <p><b>语言从哪来。</b>Spring Boot 默认装配的就是 {@code AcceptHeaderLocaleResolver}，
 * 它读请求的 {@code Accept-Language} 头，结果放在 {@code LocaleContextHolder} 里。
 * 这里<b>故意不注册自定义的 {@code LocaleResolver} Bean</b>：
 * 默认那套已经够用，而自建一个的失败方式特别隐蔽——Bean 名字写错（必须是
 * {@code localeResolver}）或者类型被别的东西抢先，解析器就静默地不生效，
 * 表现是「前端明明发了 Accept-Language，后端还是回中文」，且不报任何错。
 *
 * <p><b>只能在这两个地方调 {@link #current()}：</b>
 * <ul>
 *   <li>Controller 层（请求线程上）——取下 {@link Messages} 之后往下传；</li>
 *   <li>异常处理器和拦截器（同样在请求线程上）。</li>
 * </ul>
 * <b>绝对不要</b>在智能体的循环里、或者任何 Reactor 算子里调它，理由见 {@link Messages}。
 */
@Component
public class MessageResolver {

    private final MessageSource messageSource;

    public MessageResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 请求线程上该用的那份。 */
    public Messages current() {
        return new Messages(messageSource, LocaleContextHolder.getLocale());
    }

    /** 指定语言的一份。给「已经离开请求线程、但手里有 locale」的地方用。 */
    public Messages of(Locale locale) {
        return new Messages(messageSource, locale);
    }
}
