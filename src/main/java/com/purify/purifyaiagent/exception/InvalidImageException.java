package com.purify.purifyaiagent.exception;

/**
 * 上传的文件不是图片、或图片格式无法识别时抛出。
 *
 * <p>和 {@code SensitiveWordException} 一样属于「应用层主动拒绝」：
 * 与其把不确定的字节流发给模型让它瞎猜（还照样计费），不如在入口就直接挡掉。
 *
 * <p>之所以单独定义一个异常类型，而不是直接抛 {@link IllegalArgumentException}：
 * 后者会被 Spring 的断言、参数校验等地方大量抛出，用一个专门的类型才能保证
 * {@code GlobalExceptionHandler} 里的 400 处理不会误伤真正的程序缺陷。
 */
public class InvalidImageException extends RuntimeException {

    public InvalidImageException(String message) {
        super(message);
    }
}
