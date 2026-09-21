package com.purify.purifyaiagent.exception;

import lombok.Getter;

/**
 * 命中敏感词时由 {@code SensitiveWordAdvisor} 抛出。
 *
 * <p>由 {@code GlobalExceptionHandler} 统一转换成对用户友好的回复，
 * 因此它继承 {@link RuntimeException} 但不视为系统故障，日志里按 WARN 处理。
 *
 * <h2>为什么它只带「命中了哪个词」，不带话术</h2>
 *
 * <p>因为<b>抛出的那一刻拿不到语言</b>。这个异常是在 ChatClient 的 Advisor 链里抛的，
 * 而那条链在流式用法下跑在 Reactor 的调度线程上——请求线程上的
 * {@code LocaleContextHolder} 在那里不可见（见 {@code i18n/Messages} 的说明）。
 * 在这里读它不会报错，只会静默地回落成默认语言，表现是「英文界面上一句中文的引导话术」。
 *
 * <p>所以话术由**展示它的那一层**去取（{@code checker.replyMessage(locale)}），
 * 而那一层要么在请求线程上，要么手里有请求线程上捕获好的 locale。
 * 两边都做对之后，命中敏感词这条路径的语言才和中英切换是一致的。
 */
@Getter
public class SensitiveWordException extends RuntimeException {

    /** 命中的敏感词，仅用于日志排查，不返回给前端。 */
    private final String hitWord;

    public SensitiveWordException(String hitWord) {
        super("命中敏感词: " + hitWord);
        this.hitWord = hitWord;
    }
}
