package com.purify.purifyaiagent.auth;

import com.purify.purifyaiagent.exception.ApiException;
import com.purify.purifyaiagent.model.AuthUserView;
import com.purify.purifyaiagent.model.LoginRequest;
import com.purify.purifyaiagent.model.LoginResponse;
import com.purify.purifyaiagent.model.RegisterRequest;
import com.purify.purifyaiagent.model.ResetPasswordRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 注册、登录、找回密码的业务逻辑。
 *
 * <p><b>为什么用 {@link TransactionTemplate} 而不是 {@code @Transactional}</b>：
 * 后者要靠 Spring 的代理生效，而本项目的类都是「普通类 + Config 里手工装配」，
 * 没有一个是 {@code @Service}。为了两个方法给整个类套上代理体系，
 * 换来的只是少写两行——而且一旦有人从类内部直接调这些方法（绕过代理），
 * 事务会静默失效，那种 bug 极难发现。{@code TransactionTemplate} 是显式的：
 * 有没有事务、边界在哪儿，看代码就知道。
 *
 * <p><b>密码校验规则只有一处</b>（{@link #requirePassword}），注册和找回密码都走它。
 * 分开写的话迟早有一边被漏改，表现是「注册时必须 8 位，改密码时 6 位也能过」。
 */
@Slf4j
public class AuthService {

    /** 密码长度下限。8 位是当前普遍的最低要求，不设上限的复杂度规则（它们反而促使用户复用密码）。 */
    private static final int PASSWORD_MIN_LENGTH = 8;

    /** 用户名长度上下限。上限留了余量，但远小于列宽 {@code VARCHAR(50)}。 */
    private static final int USERNAME_MIN_LENGTH = 3;
    private static final int USERNAME_MAX_LENGTH = 20;

    /**
     * 邮箱长度上限，**和 {@code user.email VARCHAR(100)} 严格对齐**。
     *
     * <p>不能靠数据库去拦：MySQL 严格模式下超长会抛 {@code DataAccessException}，
     * 而 {@code GlobalExceptionHandler} 没有这一类的处理器，于是客户端拿到的是一个
     * 框架默认的 500 + 一段解析不了的 HTML，而不是「邮箱太长了」。
     * 这种错误对用户来说完全无法理解，也给不出去改的方向。
     *
     * <p>同样的道理适用于 {@code nickname}（{@code VARCHAR(50)}）和
     * {@code username}（列宽 50，上面那个 20 的语义上限更严，已经够用）。
     */
    private static final int EMAIL_MAX_LENGTH = 100;

    /** 昵称长度上限，对齐 {@code user.nickname VARCHAR(50)}。 */
    private static final int NICKNAME_MAX_LENGTH = 50;

    private final UserRepository userRepository;
    private final VerifyCodeService verifyCodeService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TransactionTemplate transactionTemplate;

    public AuthService(UserRepository userRepository,
                       VerifyCodeService verifyCodeService,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        this.verifyCodeService = verifyCodeService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 注册。三步：校验验证码（事务外）→ 消费 + 建号（事务内）→ 签发令牌。
     *
     * <p><b>为什么校验在事务外、消费在事务内——这两件事必须分开，不能图省事合成一步。</b>
     * <ul>
     *   <li><b>校验在外面</b>：校验时会先把这条码的「尝试次数 +1」再比对，
     *       猜错就把次数抛出去。如果这些都发生在事务里，那么抛出的异常会让事务回滚，
     *       <b>次数加了个寂寞</b>——于是尝试上限永远数不到，那条防爆破的防线
     *       静默失效，而代码看上去完全正常。这是个很难发现的错误。</li>
     *   <li><b>消费在里面</b>：消费是一次 CAS 写库，它必须能跟着下面那步「插入用户」
     *       一起回滚。插入可能撞唯一索引失败（两个人同时注册同一个用户名），
     *       没有事务的话，那次失败会留下一个已经被消费掉的验证码——用户填对了码、
     *       却看到「用户名已被占用」，改个用户名重试时又被告知「验证码已经用过了」，
     *       还得等 60 秒才能重发。有了事务，整块回滚，他可以
     *       <b>用同一个验证码换个用户名重试</b>。</li>
     * </ul>
     *
     * <p>「先查重」那一步只是为了让报错更具体（告诉用户是用户名还是邮箱撞了），
     * <b>它不保证正确性</b>——两次查询之间别人可能刚插进去。真正的保证是唯一索引，
     * 也就是下面那个 {@code DuplicateKeyException} 分支。这不算多余：
     * 有它在，99% 的情况能给出准确的提示；没它，所有冲突都只能报一句笼统的话。
     */
    public LoginResponse register(RegisterRequest request) {
        String username = requireUsername(request.username());
        String password = requirePassword(request.password());
        String email = requireEmail(request.email());

        // 查重放在事务外：它只是一次「能不能给出更好的提示」的判断，
        // 不需要事务保护，也就没必要占着连接
        if (userRepository.usernameExists(username)) {
            throw ApiException.authInvalid("error.auth.usernameTaken", username);
        }
        if (userRepository.emailExists(email)) {
            throw ApiException.authInvalid("error.auth.emailTaken");
        }

        // 密码哈希也在事务外算：BCrypt 是刻意设计成慢的（几十到几百毫秒），
        // 放在事务里等于白白占着数据库连接等 CPU
        String hash = passwordEncoder.encode(password);
        String nickname = normalizeNickname(request.nickname(), username);

        // **校验验证码必须在事务外**，原因写在 VerifyCodeService#verify 上：
        // 猜错时那次「尝试次数 +1」如果在事务里，就会跟着 ApiException 一起回滚，
        // 于是尝试上限永远数不到，防爆破的那条防线静默失效。
        // 事务里只留「消费 + 建号」这两步
        long codeId = verifyCodeService.verify(email, VerificationPurpose.REGISTER, request.code());

        Long userId = transactionTemplate.execute(status -> {
            // 消费验证码。CAS 失败说明它刚被另一个请求用掉了，这时必须报错而不是放行——
            // 放行就等于一次验证码换两个账号
            if (!verifyCodeService.consume(codeId)) {
                throw ApiException.authInvalid("error.auth.codeUsed");
            }
            try {
                return userRepository.insert(username, hash, email, nickname, UserRole.NORMAL);
            }
            catch (DuplicateKeyException exception) {
                // 走到这里说明查重之后、插入之前有人抢先了。抛出去让事务回滚，
                // 那次消费也跟着撤销——用户可以拿同一个验证码换个用户名重试，
                // 而不是被迫等 60 秒重发。转成 400 而不是让它变成 500：
                // 这是一个客户端可以自己解决的情况，不是服务端故障
                throw ApiException.authInvalid("error.auth.usernameOrEmailTaken");
            }
        });

        log.info("[Auth] 新用户注册成功：userId={} username={}", userId, username);
        UserAccount account = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("刚插入的用户查不到：id=" + userId));
        return new LoginResponse(jwtService.issue(userId, username, account.role()), AuthUserView.from(account));
    }

    /**
     * 登录。
     *
     * <p><b>「用户名不存在」和「密码不对」返回同一句话</b>，否则这个接口就成了
     * 一个账号枚举器：攻击者可以靠消息的差异批量试出哪些用户名是存在的。
     * 被禁用/被删除则单独给一句话——那是用户能采取行动的（联系管理员），
     * 而且账号是否存在这件事，在那个时刻已经由「他注册过」表明了。
     *
     * <p><b>用户名不存在时也会跑一次 BCrypt 比对</b>（拿一个固定哈希去比），
     * 这是为了让两条路径的耗时接近。不跑的话，「用户名不存在」会立刻返回，
     * 而「密码不对」要等几十毫秒的哈希计算——时间差本身就把答案说出去了，
     * 前面那句「同一句话」也就白费了。
     */
    public LoginResponse login(LoginRequest request, String clientIp) {
        String username = request.username() == null ? "" : request.username().trim();
        String password = request.password() == null ? "" : request.password();

        Optional<UserAccount> found = userRepository.findForLogin(username);
        if (found.isEmpty()) {
            passwordEncoder.matches(password, DUMMY_HASH);
            log.debug("[Auth] 登录失败：用户名不存在");
            throw ApiException.authInvalid("error.auth.badCredentials");
        }

        UserAccount account = found.get();
        if (!passwordEncoder.matches(password, account.password())) {
            log.debug("[Auth] 登录失败：密码不正确 userId={}", account.id());
            throw ApiException.authInvalid("error.auth.badCredentials");
        }

        if (!account.canLogin()) {
            // 密码是对的，所以这里可以放心地告诉对方账号的真实状态——
            // 能通过密码验证的人本来就是账号主人
            throw ApiException.authInvalid("error.auth.accountUnavailable");
        }

        userRepository.touchLogin(account.id(), clientIp);
        log.info("[Auth] 登录成功：userId={} username={} role={} ip={}",
                account.id(), account.username(), account.role(), clientIp);
        return new LoginResponse(
                jwtService.issue(account.id(), account.username(), account.role()),
                AuthUserView.from(account));
    }

    /**
     * 用邮箱验证码重置密码。
     *
     * <p>整体在一个事务里，和注册同样的理由：验证码消费了但改密码失败的话，
     * 用户会卡在「验证码已用过」上，而他什么都没改成。
     *
     * <p><b>已知取舍：改完密码，之前签发的令牌仍然有效。</b>令牌是无状态的，
     * 服务端没有吊销名单（见 {@code JwtService}）。所以如果账号是被盗后找回的，
     * 攻击者手里那个令牌在过期前还能用。要真正断开，得缩短有效期或引入吊销机制——
     * 这是本方案的已知边界，写在这里免得以后当成 bug 查。
     */
    public void resetPassword(ResetPasswordRequest request) {
        String email = requireEmail(request.email());
        String password = requirePassword(request.newPassword());

        Optional<UserAccount> found = userRepository.findByEmail(email);
        // 账号不存在时假装成功，还是明确报错？
        //
        // 明确报错。理由是这一步的输入里已经有验证码了——而验证码只发给了已注册的邮箱，
        // 所以「账号不存在」这个信息在更早的一步（发送验证码时）就已经泄露了。
        // 在这里假装成功，用户会以为密码改好了，然后用新密码登录失败，白折腾一轮。
        // （发送验证码那一步是另一回事，那里确实不该泄露，见 AuthController#sendCode）
        if (found.isEmpty()) {
            throw ApiException.authInvalid("error.auth.emailNotRegistered");
        }

        String hash = passwordEncoder.encode(password);
        UserAccount account = found.get();

        // 同 register：校验在事务外（否则猜错时的计数会被回滚，尝试上限失效），
        // 事务里只留「消费 + 改密码」
        long codeId = verifyCodeService.verify(email, VerificationPurpose.RESET_PASSWORD, request.code());

        transactionTemplate.executeWithoutResult(status -> {
            if (!verifyCodeService.consume(codeId)) {
                throw ApiException.authInvalid("error.auth.codeUsed");
            }
            userRepository.updatePassword(account.id(), hash);
        });

        log.info("[Auth] 密码已重置：userId={} username={}", account.id(), account.username());
    }

    /**
     * 用户名规范化 + 校验。
     *
     * <p>不做「只允许字母数字下划线」这类限制：这个项目面向中文用户，
     * 硬性禁掉中文用户名只会让人困惑。真正要防的是<b>空白和超长</b>——
     * 前者会让「打了一串空格的人」注册出一个看不见名字的账号，
     * 后者会被数据库截断（严格模式下直接报错）。
     */
    private static String requireUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw ApiException.authInvalid("error.auth.usernameRequired");
        }
        String trimmed = username.trim();
        if (trimmed.length() < USERNAME_MIN_LENGTH || trimmed.length() > USERNAME_MAX_LENGTH) {
            throw ApiException.authInvalid("error.auth.usernameLength", USERNAME_MIN_LENGTH, USERNAME_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * 密码校验。注册和找回密码共用这一处。
     *
     * <p>只查长度，不查复杂度（必须有大写/数字/符号那一套）。现代 NIST 指南已经不推荐
     * 复杂度规则：它们促使用户在末尾加个「1!」或者干脆复用别处的密码，
     * 而真正有效的长度下限反而被忽略了。
     *
     * <p><b>BCrypt 在 72 字节处静默截断</b>——超过的部分不算数。对这里的长度要求来说
     * 够不着（72 字节的中文密码是 24 个字），所以不加限制，但值得知道。
     */
    private static String requirePassword(String password) {
        if (!StringUtils.hasText(password)) {
            throw ApiException.authInvalid("error.auth.passwordRequired");
        }
        if (password.length() < PASSWORD_MIN_LENGTH) {
            throw ApiException.authInvalid("error.auth.passwordTooShort", PASSWORD_MIN_LENGTH);
        }
        return password;
    }

    /** 邮箱规范化 + 校验。归一化统一走 {@link UserRepository#normalizeEmail}。 */
    private static String requireEmail(String email) {
        String normalized = UserRepository.normalizeEmail(email);
        if (!StringUtils.hasText(normalized)) {
            throw ApiException.authInvalid("error.auth.emailRequired");
        }
        // 只做最基本的形状检查。**故意不用复杂的邮箱正则**：它们几乎都是错的
        // （真正合法的地址比大多数人以为的宽松得多），错杀一个合法地址的代价
        // 是用户根本注册不了，而多收一个畸形地址的代价只是那封邮件发不出去
        if (!normalized.contains("@") || normalized.startsWith("@") || normalized.endsWith("@")) {
            throw ApiException.authInvalid("error.auth.emailInvalid");
        }
        // 长度必须在入口挡住，交给数据库拦会变成 500，理由见 EMAIL_MAX_LENGTH
        if (normalized.length() > EMAIL_MAX_LENGTH) {
            throw ApiException.authInvalid("error.auth.emailTooLong", EMAIL_MAX_LENGTH);
        }
        return normalized;
    }

    /**
     * 昵称规范化 + 校验。没填返回 {@code null}（用用户名兜底是在调用处做的）。
     *
     * <p>不校验的话，一个超过 50 字的昵称会在 INSERT 时被 MySQL 拒绝，
     * 而那个异常没有对应的处理器，用户拿到的是 500。见 {@link #EMAIL_MAX_LENGTH} 的说明。
     */
    private static String normalizeNickname(String nickname, String fallback) {
        if (!StringUtils.hasText(nickname)) {
            return fallback;
        }
        String trimmed = nickname.trim();
        if (trimmed.length() > NICKNAME_MAX_LENGTH) {
            throw ApiException.authInvalid("error.auth.nicknameTooLong", NICKNAME_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * 用户名不存在时拿来「陪跑」的哈希，用来抹平两条路径的耗时差。
     *
     * <p>它对应的是一个谁也不知道的密码，所以 {@code matches} 必然返回 false。
     * 值本身不重要，重要的只是「让 BCrypt 真的跑一遍」。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
}
