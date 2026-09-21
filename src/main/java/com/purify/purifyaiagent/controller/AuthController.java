package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.auth.AuthService;
import com.purify.purifyaiagent.auth.AvatarStorage;
import com.purify.purifyaiagent.auth.LoginUser;
import com.purify.purifyaiagent.auth.UserRepository;
import com.purify.purifyaiagent.auth.CurrentUser;
import com.purify.purifyaiagent.auth.RequireLogin;
import com.purify.purifyaiagent.auth.UserAccount;
import com.purify.purifyaiagent.auth.VerificationPurpose;
import com.purify.purifyaiagent.auth.VerifyCodeService;
import com.purify.purifyaiagent.exception.ApiException;
import com.purify.purifyaiagent.exception.AuthException;
import com.purify.purifyaiagent.media.ImageTypes;
import com.purify.purifyaiagent.model.AuthUserView;
import com.purify.purifyaiagent.model.LoginRequest;
import com.purify.purifyaiagent.model.LoginResponse;
import com.purify.purifyaiagent.model.RegisterRequest;
import com.purify.purifyaiagent.model.ResetPasswordRequest;
import com.purify.purifyaiagent.model.SendCodeRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 登录、注册、找回密码。
 *
 * <pre>
 *   POST /api/auth/code            发验证码（邮箱）
 *   POST /api/auth/register        注册，返回令牌
 *   POST /api/auth/login           登录，返回令牌
 *   POST /api/auth/password/reset  用验证码重置密码
 *   GET  /api/auth/me              当前用户（用于启动时校验本地令牌）
 *   POST /api/auth/logout          退出
 * </pre>
 *
 * <p>前四个是公开的（{@link com.purify.purifyaiagent.auth.AuthInterceptor} 里列了白名单），
 * 后两个要登录。
 *
 * <p><b>登录态是无状态的 JWT，所以这个 controller 不存任何东西。</b>
 * 登录就是「验证密码、签发令牌」，退出就是「客户端把令牌丢掉」——
 * 服务端没有会话可清除，{@link #logout} 存在只是为了给前端一个明确的语义，
 * 以及一条可以对齐时间点的日志。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * 找回密码那条链路的固定回应，**已注册和未注册共用这一句**（见 {@link #sendCode}）。
     *
     * <p>写成常量而不是在两处各写一遍：这个字符串是防账号枚举的<b>全部</b>依据，
     * 两处各写一遍的话，以后有人顺手改了一处的措辞，那个口子就悄无声息地开了——
     * 而它不会报错、不会有任何症状，只是一个接口变得可以用来探测账号是否存在。
     */
    private static final String RESET_CODE_MESSAGE =
            "如果该邮箱已注册，验证码已发送，请查收（10 分钟内有效）";

    private final AuthService authService;
    private final VerifyCodeService verifyCodeService;
    private final UserRepository userRepository;
    private final AvatarStorage avatarStorage;

    public AuthController(AuthService authService,
                          VerifyCodeService verifyCodeService,
                          UserRepository userRepository,
                          AvatarStorage avatarStorage) {
        this.authService = authService;
        this.verifyCodeService = verifyCodeService;
        this.userRepository = userRepository;
        this.avatarStorage = avatarStorage;
    }

    /**
     * 发送邮箱验证码。注册和找回密码共用这一个接口，用 {@code purpose} 区分。
     *
     * <p><b>两种用途对「邮箱是否已注册」的回应是不一样的，这是有意的</b>：
     * <ul>
     *   <li>{@code REGISTER} —— 邮箱已注册时<b>明确拒绝</b>。因为注册的最后一步
     *       （唯一索引）本来就会暴露这件事，这里含糊只是让用户白等一封不会来的邮件；</li>
     *   <li>{@code RESET_PASSWORD} —— 邮箱是否存在，<b>返回完全相同的响应</b>。
     *       这个接口是公开的，如果能靠它区分「注册过」和「没注册过」，
     *       它就成了一个账号枚举器：攻击者可以拿一份邮箱列表批量试出哪些人在这个站注册过。
     *       而找回密码这条链路的后续步骤都需要收到邮件才能继续，所以不告诉他也推进不下去。</li>
     * </ul>
     *
     * <p><b>验证码永远不会出现在响应里</b>，只会发到邮箱（没配邮件时打到服务端日志）。
     */
    @PostMapping("/code")
    public Map<String, Object> sendCode(@RequestBody SendCodeRequest request) {
        VerificationPurpose purpose = request.requirePurpose();
        String email = UserRepository.normalizeEmail(request.email());
        // 邮箱为空必须在分支之前挡掉。否则 RESET_PASSWORD 那条路会走到下面的
        // 「邮箱不存在就假装成功」分支，返回一句「验证码已发送」——
        // 而用户根本没填邮箱，什么都没有发生，他会在那儿一直等
        if (!StringUtils.hasText(email)) {
            throw ApiException.authInvalid("error.auth.emailRequired");
        }

        if (purpose == VerificationPurpose.REGISTER && userRepository.emailExists(email)) {
            throw ApiException.authInvalid("error.auth.emailTaken");
        }

        // 找回密码这条链路：邮箱存不存在，**响应必须逐字相同**。
        // 不这样做的话，这个公开接口就是一个账号枚举器——拿一份邮箱列表批量试一遍，
        // 就知道哪些人在这个站注册过。所以下面两条分支共用同一个 message 常量，
        // 而不是各写一句意思相近的话：差一个字就等于没做这件事。
        boolean registered = userRepository.emailExists(email);
        if (purpose == VerificationPurpose.RESET_PASSWORD && !registered) {
            // 不真的发信（这个邮箱根本没有账号），但响应体、以及**限流行为**，
            // 都和已注册的情况完全一样。限流那一半很关键：只统一响应体是不够的，
            // 同一秒内请求两次，一个说「太频繁」一个说成功，一样能分辨出账号是否存在。
            // 所以这条路径走 sendToUnregistered 而不是直接 return
            verifyCodeService.sendToUnregistered(email, purpose);
            return Map.of("sent", true, "message", RESET_CODE_MESSAGE);
        }

        verifyCodeService.send(email, purpose);
        // 注册那条链路没必要含糊——最后一步的唯一索引本来就会暴露它，
        // 这里含糊只是让用户白等一封不会来的邮件
        return Map.of("sent", true,
                "message", purpose == VerificationPurpose.RESET_PASSWORD
                        ? RESET_CODE_MESSAGE
                        : "验证码已发送，请查收");
    }

    /** 注册。验证码正确才建号，成功后直接返回令牌，前端不用再登录一次。 */
    @PostMapping("/register")
    public LoginResponse register(@RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    /** 登录。 */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, clientIpOf(httpRequest));
    }

    /** 用邮箱验证码重置密码。 */
    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok().build();
    }

    /**
     * 当前用户。
     *
     * <p>前端在应用启动时用它跑一次：本地 localStorage 里那个令牌可能已经过期或失效
     * （换了密钥、账号被删），而<b>令牌本身没法自证有效</b>——它带着 exp，
     * 但签名对不对只有服务端知道。让它去问一次，比让用户在第一跳受保护的导航上
     * 撞一个 401 要好：那时人已经在页面里了，界面会先闪一下再跳走。
     *
     * <p>从数据库重读而不是直接用令牌里的信息：这样改昵称、改角色之后，
     * 刷新页面就能看到新的（令牌里那份是签发时的快照，见 {@code JwtService}）。
     */
    @GetMapping("/me")
    @RequireLogin
    public AuthUserView me(@CurrentUser LoginUser me) {
        // 令牌有效但账号已经被删了——这种情况必须在这里挡住。
        // 令牌是无状态的，服务端不知道账号没了，只有查一次库才知道
        UserAccount account = userRepository.findById(Long.valueOf(me.id()))
                .filter(UserAccount::canLogin)
                .orElseThrow(() -> AuthException.unauthorized("error.auth.accountGone"));
        return AuthUserView.from(account);
    }

    /**
     * 换头像。
     *
     * <p>返回刷新后的用户信息（和 {@code /me} 同一个结构），前端直接拿它更新本地那份，
     * 不用再问一次服务端。
     *
     * <p><b>先存新的、再删旧的</b>，顺序不能反：反过来的话，万一新图写盘失败，
     * 用户就落得一个头像被删、新的又没存上的状态——而重传一次本来是可以救回来的。
     * 代价是失败时会在磁盘上留一个孤儿文件，那比用户丢头像轻得多。
     *
     * <p>删旧图失败只记日志（见 {@code AvatarStorage#deleteQuietly}），
     * 不影响本次更换的结果。
     */
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireLogin
    public AuthUserView uploadAvatar(@RequestParam("file") MultipartFile file,
                                     @CurrentUser LoginUser me) {
        UserAccount account = userRepository.findById(Long.valueOf(me.id()))
                .filter(UserAccount::canLogin)
                .orElseThrow(() -> AuthException.unauthorized("error.auth.accountGone"));

        String url = avatarStorage.store(file, ImageTypes.DEFAULT_MAX_BYTES);
        userRepository.updateAvatar(account.id(), url);
        // account 是改之前读出来的，它身上还是旧地址——正好拿来删旧文件
        avatarStorage.deleteQuietly(account.avatar());

        // 重新读一次而不是手工拼一个返回值：拼的话就得把 UserAccount 的十几个字段
        // 抄一遍，抄漏一个就会返回一份和库里不一致的数据。头像不是高频操作，多一次查询无所谓
        UserAccount updated = userRepository.findById(account.id())
                .orElseThrow(() -> AuthException.unauthorized("error.auth.accountGone"));
        return AuthUserView.from(updated);
    }

    /**
     * 退出登录。
     *
     * <p><b>它不在服务端做任何事</b>——JWT 是无状态的，没有会话可以销毁。
     * 留着这个接口是为了两件事：前端有一个明确的动作可以调（而不是只是默默地
     * 删掉本地存储），以及日志里能对齐「谁什么时候退出的」。
     *
     * <p>注意：真正让令牌失效是不可能的，它在过期前一直有效（见 {@code JwtService}）。
     * 所以如果有人拿到了那个令牌，用户点「退出」并不能把他踢出去。
     */
    @PostMapping("/logout")
    @RequireLogin
    public ResponseEntity<Void> logout(@CurrentUser LoginUser me) {
        log.info("[Auth] 退出登录：{}", me.describe());
        return ResponseEntity.ok().build();
    }

    /**
     * 取客户端 IP，用于记录最后登录来源。
     *
     * <p>优先读 {@code X-Forwarded-For} 的第一跳：部署在 Nginx 之类的反向代理后面时，
     * {@code getRemoteAddr()} 拿到的是代理自己的地址，每个用户记下来都是同一个。
     * <b>只取第一段</b>——后面那些是转发链上的其他代理，而且整条头都是客户端可以伪造的，
     * 所以这个值只能用来做审计参考，不能拿它做任何安全判断。
     */
    private static String clientIpOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return truncate(first, 50);
            }
        }
        return truncate(request.getRemoteAddr(), 50);
    }

    /** {@code user.last_login_ip} 是 VARCHAR(50)，超了会被 MySQL 严格模式拒绝。 */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
