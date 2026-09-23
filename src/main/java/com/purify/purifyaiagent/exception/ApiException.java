package com.purify.purifyaiagent.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

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
 *
 * <h2>message 是「键」不是「句子」</h2>
 *
 * <p>从接入中英双语起，工厂方法的第一个参数是 {@code messages*.properties} 里的<b>键</b>，
 * 后面跟的 {@code args} 填进文案里的 {0} {1}。真正翻成人话发生在
 * {@code GlobalExceptionHandler} 里（那里有 {@code MessageSource} 和当前请求的语言）。
 *
 * <p>为什么不在这里就把句子拼好：拼的时候<b>拿不到语言</b>。异常是在业务代码深处
 * new 出来的，那些地方既不在请求线程上（智能体的循环跑在 Reactor 线程上），
 * 也不该为了取一句文案去依赖 {@code MessageSource}。
 * 换成键 + 参数之后，这个异常本身是语言无关的，翻不翻、翻成哪种语言，
 * 由处理它的那一层决定。
 *
 * <p>{@code getMessage()} 因此返回的是<b>键</b>，仅供日志定位用。
 * 需要给人看的文案请走 {@code GlobalExceptionHandler}——它会先翻译再记日志。
 */
@Getter
public class ApiException extends RuntimeException {

    /** 上传的文件不是图片、或图片格式无法识别。 */
    public static final String INVALID_IMAGE = "INVALID_IMAGE";

    /** 对话请求本身不合法，比如 {@code message} 为空。 */
    public static final String INVALID_CHAT_REQUEST = "INVALID_CHAT_REQUEST";

    /** 这份文档知识库不收：格式不支持、体积超限、没带文件名、分类名不合法。 */
    public static final String UNSUPPORTED_DOCUMENT = "UNSUPPORTED_DOCUMENT";

    /** 文档收下了，但建索引这一步做不下去：内容为空、编码不是 UTF-8、切片数撞上限。 */
    public static final String DOCUMENT_INDEX_FAILED = "DOCUMENT_INDEX_FAILED";

    /**
     * 注册/找回密码这条链路上「输入的东西不对」：用户名被占用、邮箱已注册、
     * 两次密码不一致、验证码错误或已过期。
     *
     * <p>和「没登录/没权限」不是一回事——那两类归 {@code AuthException}（401 / 403）。
     * 这里全都是 400：客户端还没开始干活就能判定它传错了。
     */
    public static final String AUTH_INVALID = "AUTH_INVALID";

    /**
     * 验证码要得太频繁，还在 60 秒的冷却里。
     *
     * <p>单开一个码而不是并进 {@code AUTH_INVALID}：前端要据此把「重新获取」
     * 那个按钮变成倒计时，而不是弹一个报错框。消息里带着剩余秒数。
     */
    public static final String CODE_TOO_FREQUENT = "CODE_TOO_FREQUENT";

    /**
     * 要的东西不存在，或者存在但不属于调用方（两者故意不可区分）。
     *
     * <p>唯一一个返回 404 而不是 400 的码，见 {@link #notFound}。
     */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** 返回给前端的错误码，取值是上面几个常量之一。 */
    private final String code;

    /**
     * 文案在 {@code messages*.properties} 里的键，**不是**给用户看的那句话。
     *
     * <p>{@code super(message)} 传的也是它，于是 {@code getMessage()} 拿到的是键 ——
     * 这是有意的：日志里出现 {@code error.auth.badCredentials} 一眼就能定位到是哪条分支，
     * 而一句翻好的中文既没法反查、也没法知道它属于哪个码。
     */
    private final String messageKey;

    /**
     * 填进文案占位符的参数，顺序对应 {@code {0} {1}}。
     *
     * <p>用 {@code Object...} 而不是提前拼成字符串：拼了就又变成语言相关的了
     * （「还可以试 3 次」和 "3 attempt(s) left" 里数字的位置不一样）。
     */
    private final Object[] args;

    /**
     * HTTP 状态码。绝大多数情况是 400，只有 {@link #notFound} 用 404。
     *
     * <p><b>为什么最后还是给它加了个状态字段</b>：原来的设计是「这一类比全都是
     * 『还没开始干活就判定客户端传错了』，统一 400 是准确的」。接入登录之后多出来一种
     * 不属于这一类的错误——「这个会话不存在，或者不属于你」。它必须返回 404，
     * 因为那是删除/改名接口已经定下的口径（{@code ChatSessionRepository#delete}
     * 的注释里写了理由），而前端也要靠状态码区分「接口坏了」和「这个会话没了」。
     *
     * <p>于是这里保留了一个默认值 400 的重载，让原有那几类一个字都不用改——
     * 只有明确需要别的状态码时才用带状态的工厂方法。
     */
    private final HttpStatus status;

    private ApiException(String code, String messageKey, Object[] args) {
        this(code, messageKey, args, HttpStatus.BAD_REQUEST);
    }

    private ApiException(String code, String messageKey, Object[] args, HttpStatus status) {
        // 传键本身而不是翻好的句子，见类注释。日志里因此看到的是键
        super(messageKey);
        this.code = code;
        this.messageKey = messageKey;
        this.args = args == null ? new Object[0] : args;
        this.status = status;
    }

    /**
     * 图片不合法。
     *
     * <p>与其把不确定的字节流发给模型让它瞎猜（还照样计费），不如在入口就直接挡掉。
     *
     * @param messageKey {@code messages*.properties} 里的键，不是给用户看的句子
     */
    public static ApiException invalidImage(String messageKey, Object... args) {
        return new ApiException(INVALID_IMAGE, messageKey, args);
    }

    /**
     * 对话请求不合法。
     *
     * <p>挡在这里而不是让空消息走到模型那一层：DashScope 对空输入会报一个含义模糊的
     * 参数错误，看起来像服务端故障；而「你没说话」本来就该是一个 400。
     */
    public static ApiException invalidChatRequest(String messageKey, Object... args) {
        return new ApiException(INVALID_CHAT_REQUEST, messageKey, args);
    }

    /** 文档在入口处被拒：还没开始解析就能判定它不该被收下。 */
    public static ApiException unsupportedDocument(String messageKey, Object... args) {
        return new ApiException(UNSUPPORTED_DOCUMENT, messageKey, args);
    }

    /** 文档收下了，但解析/切片/写入做不下去。 */
    public static ApiException documentIndexFailed(String messageKey, Object... args) {
        return new ApiException(DOCUMENT_INDEX_FAILED, messageKey, args);
    }

    /**
     * 注册/找回密码的输入不合法。
     *
     * <p>键指向的那句话会被原样显示给用户，所以它是写给用户看的——
     * 别写「唯一索引冲突」这种内部说法，写「这个用户名已经被占用了」。
     */
    public static ApiException authInvalid(String messageKey, Object... args) {
        return new ApiException(AUTH_INVALID, messageKey, args);
    }

    /** 验证码要得太频繁。文案里要带上还剩几秒，前端拿它做倒计时。 */
    public static ApiException codeTooFrequent(String messageKey, Object... args) {
        return new ApiException(CODE_TOO_FREQUENT, messageKey, args);
    }

    /**
     * 请求的那个东西不存在——**或者存在但不属于调用方**。
     *
     * <p>两种情况的响应完全一致，这一点是刻意的：区分开来，这个接口就成了一个
     * 存在性探测器，能问出「这个 ID 是不是真的存在」。{@code ChatSessionRepository}
     * 的删除和改名早就是按这个口径处理的（「不额外报错，免得变成
     * 『这个 ID 是存在的，只是不属于你』的信息泄露口子」），这里只是把它
     * 扩展到读取路径上。
     *
     * <p>{@code messageKey} 也要统一（见 {@code SessionAccess} 里的常量），
     * 否则光靠错别字就能把两种情况分辨出来。两种语言下各自统一即可——
     * 中英各有一句，但只要前端始终带着 {@code Accept-Language}，
     * 同一个请求里的两次响应就一定是同一种语言，仍然分不出来。
     */
    public static ApiException notFound(String messageKey, Object... args) {
        return new ApiException(NOT_FOUND, messageKey, args, HttpStatus.NOT_FOUND);
    }
}
