package com.purify.purifyaiagent.auth;

import com.purify.purifyaiagent.config.AuthProperties;
import com.purify.purifyaiagent.exception.AuthException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * 签发和校验令牌（HS256 的 JWT）。
 *
 * <p><b>自己写 JWT 而不是用库</b>是刻意避开的：手写 HS256 有三处极易写错、
 * 且错了照样能跑通的地方——签名用 {@code String.equals} 比较（时序侧信道）、
 * 信任令牌头里的 {@code alg}（可以填 {@code none} 绕过验签）、忘了验 {@code exp}。
 * 三个都是「本地测全对、被人打时才暴露」的类型，所以这一步交给 jjwt。
 *
 * <p><b>令牌里放什么</b>：
 * <ul>
 *   <li>{@code sub} = 用户 id（十进制字符串）。这是唯一的身份依据；</li>
 *   <li>{@code username} —— 只给日志和界面看，<b>不参与鉴权</b>；</li>
 *   <li>{@code role} —— 鉴权读的就是它；</li>
 *   <li>{@code iat} / {@code exp} —— jjwt 自己生成和校验。</li>
 * </ul>
 * 把 role 放进令牌的代价是：<b>改角色要重新登录才生效</b>（令牌里那份是签发时的快照）。
 * 换来的是每个请求**不查一次库**——这个项目现在没有任何 per-request 查询，
 * 为鉴权开这个头不划算。真要收紧就缩短 {@code purify.auth.jwt.expire-minutes}。
 *
 * <p><b>没有服务端吊销</b>：退出登录只是前端把令牌丢掉，令牌本身在过期前一直有效。
 * 所以改密码<b>不会</b>让旧令牌失效——这是最容易被注意到的那种情况，
 * 写在这里免得以后当成 bug 查。
 */
@Slf4j
public class JwtService {

    /**
     * HS256 要求密钥至少 256 位（32 字节）。低于这个长度 jjwt 自己也会拒绝，
     * 但那时抛的是运行期异常、指向的是某次登录请求；这里提前到启动期检查，
     * 让「密钥没配」这件事在启动日志里就说清楚。
     */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long expireMinutes;

    /**
     * @throws IllegalStateException 密钥为空或过短。**故意让应用起不来**：
     *         换成「空就随机生成一个」的话，每次重启所有人被登出，
     *         而且「配置没填」这件事被完全藏起来了，等发现时已经不知道从哪儿查
     */
    public JwtService(AuthProperties properties) {
        String secret = properties.getJwt().getSecret();
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException(
                    "purify.auth.jwt.secret 没有配置。请在 application-local.yml 里填一个至少 "
                            + MIN_SECRET_BYTES + " 字节的随机串（生成：openssl rand -base64 48）。"
                            + "这里故意不让应用启动：空密钥等于任何人都能自己签一个令牌出来。");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("purify.auth.jwt.secret 只有 " + bytes.length
                    + " 字节，HS256 至少需要 " + MIN_SECRET_BYTES + " 字节。"
                    + "短密钥可以被离线暴力破解，等于没有签名。");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireMinutes = properties.getJwt().getExpireMinutes();
        log.info("[Jwt] 已启用，有效期 {} 分钟（约 {} 天）", expireMinutes, expireMinutes / 60 / 24);
    }

    /** 签发。role 用枚举名写进令牌，见 {@link UserRole} 里「为什么不用序号」的说明。 */
    public String issue(Long userId, String username, UserRole role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireMinutes * 60)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解出登录用户。任何问题——签名不对、格式不对、过期、sub 不是数字——
     * 都收敛成同一个 401。
     *
     * <p><b>不区分「过期」和「伪造」再报给用户</b>：对用户来说两者的动作都是重新登录，
     * 而对攻击者来说，区分开来等于告诉他「这个签名是对的，只是时间过了」。
     * 日志里会分得清楚，排查不受影响。
     *
     * @throws AuthException 令牌无效
     */
    public LoginUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String subject = claims.getSubject();
            if (!StringUtils.hasText(subject)) {
                throw AuthException.unauthorized("登录已失效，请重新登录");
            }
            // role 认不出来时 UserRole.parse 退回 NORMAL（不是 SUPER），
            // 这个方向是安全的：坏数据只会让人权限变小，不会变大
            return new LoginUser(subject, claims.get("username", String.class),
                    UserRole.parse(claims.get("role", String.class)));
        }
        catch (JwtException | IllegalArgumentException exception) {
            // 只记 debug：未登录时前端必然会打一堆无令牌的请求，而这些不是故障。
            // 记成 warn 会把真正的告警淹掉
            log.debug("[Jwt] 令牌校验不通过：{}", exception.getMessage());
            throw AuthException.unauthorized("登录已失效，请重新登录");
        }
    }

    /**
     * 从 {@code Authorization} 头里取出令牌。
     *
     * <p>只认 {@code Bearer <token>} 这一种形式，前缀大小写不敏感（各家客户端写法不一）。
     * 取不到返回 {@code null}，由调用方决定这是「匿名访问」还是「401」——
     * 这个类不该知道某个接口允不允许匿名。
     */
    public static String extractToken(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) {
            return null;
        }
        String value = authorizationHeader.trim();
        if (value.length() < 7 || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = value.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
