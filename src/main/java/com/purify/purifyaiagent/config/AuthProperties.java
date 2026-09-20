package com.purify.purifyaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录 / 注册 / 权限的配置，对应 application.yml 的 {@code purify.auth.*}。
 *
 * <p>分成四块，因为它们的「填错了会怎样」完全不同：
 * <ul>
 *   <li>{@code jwt} —— 密钥留空会启动失败（由 {@code JwtService} 把关），有效期随便填；</li>
 *   <li>{@code mail} —— 填不填只决定验证码「发出去」还是「打日志」，不影响启动；</li>
 *   <li>{@code code} —— 验证码的位数、寿命、限流、尝试上限，四个都是安全参数；</li>
 *   <li>{@code root-agent} —— 种子账号的用户名和口令，见 {@code RootAgentInitializer}。</li>
 * </ul>
 *
 * <p>用 {@code @Component + @ConfigurationProperties} 而不是在某个 Config 里 new 出来：
 * 和 {@link SearchApiProperties}、{@link PromptProperties} 一致，因为这几块配置
 * 差不多每个类都要读一点，绑定成 Bean 比一路传参清楚。
 */
@Data
@Component
@ConfigurationProperties(prefix = "purify.auth")
public class AuthProperties {

    private Jwt jwt = new Jwt();
    private Mail mail = new Mail();
    private Code code = new Code();
    /** 字段名不能写成 rootAgent —— 中划线绑定到 Java 字段是 kebab-case，见下面的 @Data 生成的 setter。 */
    private RootAgent rootAgent = new RootAgent();

    @Data
    public static class Jwt {
        /** 签名密钥。真实值在 application-local.yml，这里留空。 */
        private String secret;
        /** 有效期（分钟）。 */
        private long expireMinutes = 10080;
    }

    @Data
    public static class Mail {
        /** 关掉时验证码只打日志不发送，本地没配邮箱也能走通注册流程。 */
        private boolean enabled;
        /** 发件人。留空时退回用 spring.mail.username。 */
        private String from;
    }

    @Data
    public static class Code {
        private int length = 6;
        private int ttlMinutes = 10;
        private int resendSeconds = 60;
        private int maxAttempts = 5;
    }

    @Data
    public static class RootAgent {
        private String username = "root_agent";
        private String password;
    }
}
