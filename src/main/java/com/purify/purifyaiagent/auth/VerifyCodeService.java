package com.purify.purifyaiagent.auth;

import com.purify.purifyaiagent.config.AuthProperties;
import com.purify.purifyaiagent.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邮箱验证码：生成、限流、校验、消费。
 *
 * <p><b>防爆破靠三条一起，缺一不可</b>：
 * <ol>
 *   <li>有效期 10 分钟——限制攻击窗口；</li>
 *   <li>重发限流 60 秒——限制攻击者能触发的验证码数量；</li>
 *   <li>尝试上限 5 次——限制<b>同一条</b>验证码能被试几次。</li>
 * </ol>
 * 只说第 2 条是不够的：6 位数是 100 万种，10 分钟里跑完它并不难，而重发限流
 * 只限制「换一条码」，不限制「拿同一条码一直试」。第 3 条才是真正封死暴力枚举的那一条。
 *
 * <p><b>限流是按 (邮箱, 用途) 的，挡不住换邮箱轮询。</b>按 IP 限流需要额外记录来源 IP
 * 并考虑代理头，超出本次范围；如果这个接口要暴露到公网，那是下一件该做的事。
 *
 * <p>验证码比对用 {@link MessageDigest#isEqual} 而不是 {@code String.equals}：
 * 后者一旦发现第一个不同的字符就返回，比较耗时随「前面有几位是对的」变化，
 * 理论上可以被用来逐位试出验证码。这里只有 6 位、还有 5 次尝试上限兜着，
 * 实际可被利用的空间很小，但换成常量时间比较只是一行的事。
 */
@Slf4j
public class VerifyCodeService {

    /**
     * 生成验证码用 {@link SecureRandom}，<b>不是 {@code Math.random()} 或 {@code new Random()}</b>。
     * 后两者的种子可以从输出反推，攻击者算出接下来的几个验证码之后，
     * 「等他点重发」就能拿到一个已知的码。
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 「上次给这个 (邮箱, 用途) 发验证码是什么时候」。
     *
     * <p>键形如 {@code a@x.com:REGISTER}。放内存而不是查库，理由见
     * {@link #enforceResendCooldown}：未注册的邮箱在库里没有行，
     * 只查库的话那条路径限不到，而限不到就等于给出一个账号枚举口子。
     *
     * <p>用 {@code ConcurrentHashMap} 因为这是个单例 Bean，多个请求会并发读写。
     */
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();

    private final VerifyCodeRepository repository;
    private final EmailSender emailSender;
    private final AuthProperties authProperties;

    public VerifyCodeService(VerifyCodeRepository repository,
                             EmailSender emailSender,
                             AuthProperties authProperties) {
        this.repository = repository;
        this.emailSender = emailSender;
        this.authProperties = authProperties;
    }

    /**
     * 生成一条新验证码并发出去。重发太快时抛 {@link ApiException#codeTooFrequent}。
     *
     * <p>限流检查在生成之前：先做这个判断再花时间生成和发信，顺序反了的话
     * 被限流的请求也会真的发出一封邮件——那正好是限流想阻止的事。
     *
     * @param email   收件邮箱，内部统一归一化
     * @param purpose 用途。注册和找回密码的码不通用，见 {@link VerificationPurpose}
     */
    public void send(String email, VerificationPurpose purpose) {
        String normalized = requireEmail(email);
        enforceResendCooldown(normalized, purpose);

        String code = randomCode();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(authProperties.getCode().getTtlMinutes());
        // replace 顺带删掉了旧码，「重发即作废」的语义在那里
        repository.replace(normalized, purpose, code, expiresAt);
        // 清理挂在发送流程里，不引定时任务——写这张表的频率本来就很低
        repository.deleteExpired();

        try {
            emailSender.sendVerificationCode(normalized, code, purpose);
        }
        catch (RuntimeException exception) {
            // 发送失败就把刚写进去的那条码删掉。
            // 留着的后果是：用户看到「发送失败，请稍后重试」，立刻重试，
            // 却被告知「请 60 秒后再试」——一条永远收不到的验证码把冷却窗口占住了，
            // 用户只能干等。删掉之后重试是立刻可用的
            repository.removeLatest(normalized, purpose);
            throw exception;
        }

        markSent(normalized, purpose);
    }

    /**
     * 找回密码时**邮箱未注册**的那条路径：什么都不发，但要和真的发送走同一套限流。
     *
     * <p>为什么必须共用限流：`POST /api/auth/code` 是一个公开接口，而它对
     * 「邮箱是否注册过」刻意返回完全相同的响应体（防账号枚举）。
     * 如果限流只作用于已注册的邮箱，那么<b>限流本身就成了探针</b>——
     * 同一秒内请求两次，一个返回「请 60 秒后再试」、另一个返回成功，
     * 不需要任何分析就能分辨出哪个邮箱在本站注册过。响应体做得再一致也没用。
     *
     * <p>它不写库、不发信，只记一次「这个地址刚刚请求过」和打一行日志。
     */
    public void sendToUnregistered(String email, VerificationPurpose purpose) {
        // 走同一个 requireEmail 而不是自己再写一遍归一化和判空：
        // 复制一份的结果一定是某天只改了一边
        String normalized = requireEmail(email);
        enforceResendCooldown(normalized, purpose);
        markSent(normalized, purpose);
        log.info("[VerifyCode] 找回密码请求了一个未注册的邮箱（不发信，按已发送响应）");
    }

    /**
     * 邮箱归一化 + 非空校验。两条发送路径共用，免得有一边漏掉校验。
     */
    private static String requireEmail(String email) {
        String normalized = UserRepository.normalizeEmail(email);
        if (!StringUtils.hasText(normalized)) {
            throw ApiException.authInvalid("error.auth.emailRequired");
        }
        return normalized;
    }

    /**
     * 校验一条验证码，<b>不消费</b>。通过返回它的行 id，调用方拿这个 id 去
     * {@link #consume(long)}。
     *
     * <p><b>为什么不把「校验」和「消费」合成一个方法。</b>合成一个的话，它就必然
     * 只能在事务里调用（消费要跟着后面那步「插入用户」一起回滚），
     * 而这会带来一个很隐蔽的严重后果：<b>猜错时的那次尝试计数会被一起回滚掉</b>，
     * 于是计数永远是 0，{@code maxAttempts} 这条上限<b>永远不会触发</b>——
     * 一条防爆破的防线就这样静默失效了，而代码看起来完全正常。
     *
     * <p>所以拆开：校验（连同计数）在事务<b>外</b>做，自增的尝试次数立即提交；
     * 只有「消费 + 建号」那一步进事务。猜错的代价因此真的会被记下来。
     *
     * @return 这条验证码的行 id
     * @throws ApiException 验证码不存在、不对、过期、用过了或试太多次
     */
    public long verify(String email, VerificationPurpose purpose, String submittedCode) {
        String normalized = UserRepository.normalizeEmail(email);
        if (!StringUtils.hasText(submittedCode)) {
            throw ApiException.authInvalid("error.auth.codeRequired");
        }

        Optional<VerifyCodeRepository.StoredCode> found = repository.findLatest(normalized, purpose);
        if (found.isEmpty()) {
            throw ApiException.authInvalid("error.auth.codeNotSent");
        }
        VerifyCodeRepository.StoredCode stored = found.get();

        // 顺序有讲究：先判「用过」再判「过期」。反过来的话，一条用过且过期的码
        // 会报「已过期」，用户去重新获取——而正确的结果（已经用过了）其实已经达成
        if (stored.used()) {
            throw ApiException.authInvalid("error.auth.codeUsed");
        }
        if (stored.isExpired()) {
            throw ApiException.authInvalid("error.auth.codeExpired");
        }

        int maxAttempts = authProperties.getCode().getMaxAttempts();
        if (stored.attempts() >= maxAttempts) {
            throw ApiException.authInvalid("error.auth.codeTooManyAttempts");
        }

        // 先记一次尝试再比对：反过来的话，一个错误的验证码不会留下任何痕迹，
        // 尝试上限就永远数不到。这一步在比对之前是安全的——比对失败时多记一次是对的
        int attempts = repository.incrementAttempts(stored.id());
        if (!matches(stored.code(), submittedCode)) {
            int left = maxAttempts - attempts;
            throw ApiException.authInvalid(left > 0 ? "error.auth.codeWrong" : "error.auth.codeTooManyAttempts", left);
        }

        return stored.id();
    }

    /**
     * 消费掉一条验证码。<b>必须在事务里调</b>，而且要和它保护的那步写操作同一个事务。
     *
     * <p>CAS 的意义是并发下的正确性：两个请求同时拿着同一个验证码注册，
     * 只有一个能拿到 true。返回 false 时调用方<b>必须报错而不是放行</b>——
     * 放行就等于一次验证码换两次操作。
     *
     * <p>它和它保护的写操作在同一个事务里，是为了让「插入用户失败」能把这步撤销，
     * 用户可以用同一个验证码换个用户名重试，而不是白白等 60 秒重发。
     */
    public boolean consume(long codeId) {
        return repository.consume(codeId);
    }

    /**
     * 距离可以重发还有几秒。返回 0 表示现在就可以发。
     *
     * <p>**这里不抛异常**——「还剩几秒」是一个正常状态，
     * 抛异常会让调用方只能用 catch 来拿这个数。
     */
    public long remainingCooldownSeconds(String email, VerificationPurpose purpose) {
        Instant sentAt = lastSent.get(throttleKey(UserRepository.normalizeEmail(email), purpose));
        if (sentAt == null) {
            return 0;
        }
        long elapsed = Duration.between(sentAt, Instant.now()).toSeconds();
        return Math.max(0, authProperties.getCode().getResendSeconds() - elapsed);
    }

    /**
     * 还在冷却里就拒绝，消息里带上剩余秒数供前端做倒计时。
     *
     * <p>记在内存里而不是查库，是为了让 {@link #sendToUnregistered} 那条路径也能共用：
     * 未注册的邮箱在库里根本没有行，靠查表就限不到它，而限不到它，
     * 限流本身就成了「这个邮箱注册过没有」的探针（见那个方法的说明）。
     *
     * <p>代价是重启之后冷却窗口清零。可以接受——它挡的是脚本刷验证码，
     * 而重启不是攻击者能触发的动作。
     */
    private void enforceResendCooldown(String email, VerificationPurpose purpose) {
        long remaining = remainingCooldownSeconds(email, purpose);
        if (remaining > 0) {
            throw ApiException.codeTooFrequent("error.auth.codeTooFrequent", remaining);
        }
    }

    /** 记一次「刚刚给这个地址发过」。只增不删：过期条目靠 {@link #sweepThrottle} 清。 */
    private void markSent(String email, VerificationPurpose purpose) {
        sweepThrottle();
        lastSent.put(throttleKey(email, purpose), Instant.now());
    }

    /**
     * 顺手清掉早已过期的限流记录。
     *
     * <p>这张 map 的键是用户填的邮箱，**不清理它就是一个无界的内存增长**——
     * 一个脚本用不同的邮箱刷这个接口，能把它撑到把内存吃光。
     * 挂在发送流程里做（而不是定时任务），和 {@code deleteExpired} 同一个思路：
     * 这个接口的调用频率本来就很低，顺手扫一遍足够。
     */
    private void sweepThrottle() {
        Instant cutoff = Instant.now().minusSeconds(authProperties.getCode().getResendSeconds());
        lastSent.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }

    private static String throttleKey(String email, VerificationPurpose purpose) {
        // 邮箱为空时也要有一个稳定的键，不能拼出 "null:REGISTER" 之外的意外。
        // 走到这里 email 一定非空（上面两个方法都校验过），这只是兜底
        return (email == null ? "" : email) + ':' + purpose.name();
    }

    /**
     * 生成一个 {@code length} 位的数字验证码，左侧补零。
     *
     * <p>补零不能省：直接 {@code nextInt(1000000)} 再 toString 的话，
     * 抽到 42 会得到 "42" 这个两位的验证码，而数据库那一列是 {@code CHAR(6)}——
     * 存进去变成 "42    "，校验时和用户输入的 "000042" 永远对不上。
     * 表现是「有千分之一的概率验证码怎么填都不对」。
     */
    private String randomCode() {
        int length = authProperties.getCode().getLength();
        int bound = (int) Math.pow(10, length);
        return String.format("%0" + length + "d", RANDOM.nextInt(bound));
    }

    /**
     * 常量时间比对。
     *
     * <p>先把两边都转成字节再比，而不是直接比字符串——{@code isEqual} 比的是字节数组，
     * 长度不同时会提前返回，但验证码固定 6 位，长度差异只可能来自用户输入，
     * 那种情况本来就该失败。
     */
    private static boolean matches(String expected, String submitted) {
        if (expected == null || submitted == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.trim().getBytes(StandardCharsets.UTF_8),
                submitted.trim().getBytes(StandardCharsets.UTF_8));
    }
}
