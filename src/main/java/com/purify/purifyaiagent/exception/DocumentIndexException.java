package com.purify.purifyaiagent.exception;

/**
 * 文档本身合法，但建索引这一步做不下去：内容为空、编码不是 UTF-8、
 * 或者切出的切片数撞上了上限（再切下去会静默丢内容）。
 *
 * <p>和 {@code InvalidImageException} 一样单独定义类型而不用
 * {@code IllegalArgumentException}：后者会被 Spring 的断言、参数校验等地方大量抛出，
 * 用专门类型才能保证异常处理器里的 400 不会误伤真正的程序缺陷。
 */
public class DocumentIndexException extends RuntimeException {

    public DocumentIndexException(String message) {
        super(message);
    }
}
