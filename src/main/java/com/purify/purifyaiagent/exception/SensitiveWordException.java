package com.purify.purifyaiagent.exception;

import lombok.Getter;

/**
 * 命中敏感词时由 {@code SensitiveWordAdvisor} 抛出。
 *
 * <p>由 {@code GlobalExceptionHandler} 统一转换成对用户友好的回复，
 * 因此它继承 {@link RuntimeException} 但不视为系统故障，日志里按 WARN 处理。
 */
@Getter
public class SensitiveWordException extends RuntimeException {

    /** 命中的敏感词，仅用于日志排查，不返回给前端。 */
    private final String hitWord;

    /** 给用户看的引导话术。 */
    private final String replyMessage;

    public SensitiveWordException(String hitWord, String replyMessage) {
        super("命中敏感词: " + hitWord);
        this.hitWord = hitWord;
        this.replyMessage = replyMessage;
    }
}
