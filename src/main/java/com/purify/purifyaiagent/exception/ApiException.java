package com.purify.purifyaiagent.exception;

import lombok.Getter;

/**
 * 「客户端传错了东西」这一类异常的统称，由 {@code GlobalExceptionHandler} 统一翻译成
 * HTTP 400 + 一个稳定的错误码。
 *
 * <p><b>为什么是一个类而不是四个</b>：它原先分成
 * {@code InvalidChatRequestException}、{@code InvalidImageException}、
 * {@code UnsupportedDocumentException}、{@code DocumentIndexException} 四个类，
 * 但四者除了错误码之外<b>没有任何差别</b>——都是 {@code extends RuntimeException}
 * 加一个 {@code String message} 构造器。四个文件换来的唯一区分点是
 * {@code GlobalExceptionHandler} 里的四个 {@code @ExceptionHandler} 方法，
 * 而它们的处理逻辑也一模一样（记一条 WARN、返回 400、带上各自的错误码）。
 *
 * <p><b>用静态工厂方法而不是公开构造器</b>，是为了把「类型即错误码」这个保证留下来：
 * 合并之后错误码变成了构造参数，如果开放成 {@code new ApiException(code, msg)}，
 * 每个 throw site 都要自己写对那个字符串，写错了编译器不会拦——而这四个码是
 * 接口契约的一部分（前端按码判断怎么渲染）。工厂方法把码固定在方法名里，
 * throw site 只需声明「这是哪一类错误」，仍然不需要碰字符串。
 *
 * <p><b>为什么用 400 而不是让每个码自带状态</b>：这四类全都是「还没开始干活就能判定
 * 客户端传错了」，统一 400 是准确的。真正的服务端故障（比如向量库连不上）会是
 * {@code DataAccessException} 之类，不在这里拦，照旧走 500。
 *
 * <p>与 {@link SensitiveWordException} 的关系：<b>是兄弟，不是父子</b>。
 * 那个返回的是 HTTP 200 + 引导话术（「命中敏感词」是业务上的主动拒绝，不是错误），
 * 一旦让它继承本类，它就会被 400 的那条分支抢走处理权。
 */
@Getter
public class ApiException extends RuntimeException {

    /** 上传的文件不是图片、或图片格式无法识别。 */
    public static final String INVALID_IMAGE = "INVALID_IMAGE";

    /** 对话请求本身不合法，比如 {@code message} 为空。 */
    public static final String INVALID_CHAT_REQUEST = "INVALID_CHAT_REQUEST";

    /** 这份文档知识库不收：格式不支持、体积超限、没带文件名、分类值不在配置表里。 */
    public static final String UNSUPPORTED_DOCUMENT = "UNSUPPORTED_DOCUMENT";

    /** 文档收下了，但建索引这一步做不下去：内容为空、编码不是 UTF-8、切片数撞上限。 */
    public static final String DOCUMENT_INDEX_FAILED = "DOCUMENT_INDEX_FAILED";

    /** 返回给前端的错误码，取值是上面四个常量之一。 */
    private final String code;

    private ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 图片不合法。
     *
     * <p>与其把不确定的字节流发给模型让它瞎猜（还照样计费），不如在入口就直接挡掉。
     */
    public static ApiException invalidImage(String message) {
        return new ApiException(INVALID_IMAGE, message);
    }

    /**
     * 对话请求不合法。
     *
     * <p>挡在这里而不是让空消息走到模型那一层：DashScope 对空输入会报一个含义模糊的
     * 参数错误，看起来像服务端故障；而「你没说话」本来就该是一个 400。
     */
    public static ApiException invalidChatRequest(String message) {
        return new ApiException(INVALID_CHAT_REQUEST, message);
    }

    /** 文档在入口处被拒：还没开始解析就能判定它不该被收下。 */
    public static ApiException unsupportedDocument(String message) {
        return new ApiException(UNSUPPORTED_DOCUMENT, message);
    }

    /** 文档收下了，但解析/切片/写入做不下去。 */
    public static ApiException documentIndexFailed(String message) {
        return new ApiException(DOCUMENT_INDEX_FAILED, message);
    }
}
