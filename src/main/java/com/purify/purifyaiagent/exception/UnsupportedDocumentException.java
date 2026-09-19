package com.purify.purifyaiagent.exception;

/**
 * 这份文档知识库不收：格式不支持、体积超限、没带文件名、或者分类值不在配置的分类表里。
 *
 * <p>共同点是「还没开始解析就能判定它不该被收下」。与之相对的是
 * {@code DocumentIndexException}——那个是「收下了，但解析/切片做不下去」。
 * 两者都是客户端传错了东西，所以都由 {@code GlobalExceptionHandler} 翻译成 HTTP 400。
 */
public class UnsupportedDocumentException extends RuntimeException {

    public UnsupportedDocumentException(String message) {
        super(message);
    }
}
