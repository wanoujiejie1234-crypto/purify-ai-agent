package com.purify.purifyaiagent.config;

import com.purify.purifyaiagent.auth.AuthInterceptor;
import com.purify.purifyaiagent.auth.AuthService;
import com.purify.purifyaiagent.auth.AvatarStorage;
import com.purify.purifyaiagent.auth.EmailSender;
import com.purify.purifyaiagent.auth.JwtService;
import com.purify.purifyaiagent.auth.RootAgentInitializer;
import com.purify.purifyaiagent.auth.SchemaGuard;
import com.purify.purifyaiagent.auth.UserRepository;
import com.purify.purifyaiagent.auth.VerifyCodeRepository;
import com.purify.purifyaiagent.auth.VerifyCodeService;
import com.purify.purifyaiagent.i18n.MessageResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 登录 / 注册 / 权限的装配。
 *
 * <p>和项目里其他 {@code *Config} 一样，类的本体都写成不依赖 Spring 的普通类
 * （构造器注入依赖），在这里集中 new 成 Bean——这样它们能单独 new 出来测，
 * 也避免 {@code @Service} 散落在各处之后，「谁依赖谁」要翻遍全项目才知道。
 *
 * <p>表结构在 {@code db/auth-schema-mysql.sql}，由 {@code spring.sql.init} 在启动时执行；
 * 脚本里建不全的那三样（{@code role} 列和两个唯一索引）由 {@link SchemaGuard} 补，
 * 理由写在那个类里。
 */
@Configuration
public class AuthConfig {

    /** 补 {@code user} 表缺的 {@code role} 列与两个唯一索引。库名由它自己从连接里取。 */
    @Bean
    public SchemaGuard authSchemaGuard(JdbcTemplate jdbcTemplate) {
        return new SchemaGuard(jdbcTemplate);
    }

    @Bean
    public UserRepository userRepository(JdbcTemplate jdbcTemplate) {
        return new UserRepository(jdbcTemplate);
    }

    /**
     * 头像的落盘。目录和对外地址都由它自己管，见 {@link AvatarStorage}——
     * 存本地而不是传 OSS 的理由也写在那里。
     */
    @Bean
    public AvatarStorage avatarStorage() {
        return new AvatarStorage();
    }

    @Bean
    public VerifyCodeRepository verifyCodeRepository(JdbcTemplate jdbcTemplate) {
        return new VerifyCodeRepository(jdbcTemplate);
    }

    /**
     * 密码编码器。
     *
     * <p>BCrypt 是这几年的默认选择：自带随机盐（所以两个人用同样的密码，
     * 存出来的哈希也不一样，彩虹表失效），并且<b>刻意慢</b>——
     * 每次验证要几十到几百毫秒，这让离线暴力破解的成本高到不划算。
     * 慢在这里是特性不是缺陷。
     *
     * <p>强度用默认的 10。调高会更安全，但每次登录和注册都要多等，
     * 而这个项目的账号量级远不到需要为此加成本的程度。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 令牌签发与校验。
     *
     * <p>密钥没配时它会在<b>启动期</b>抛异常（见 {@link JwtService} 的构造器）。
     * 这是有意的：空密钥等于任何人都能自己签一个令牌出来，
     * 而那是个不该被「先跑起来再说」的配置。
     */
    @Bean
    public JwtService jwtService(AuthProperties authProperties) {
        return new JwtService(authProperties);
    }

    /**
     * 发验证码邮件。
     *
     * <p>注入的是 {@link ObjectProvider} 而不是 {@link JavaMailSender} 本身：
     * {@code spring.mail.host} 没配时容器里没有这个 Bean，直接注入会让应用起不来。
     * 用 ObjectProvider，拿不到就是「邮件功能没开」，应用照常启动
     * （和 {@code SlimApp} 对可选 Advisor 的处理是同一个套路）。
     */
    @Bean
    public EmailSender emailSender(ObjectProvider<JavaMailSender> mailSenderProvider,
                                   AuthProperties authProperties,
                                   MessageResolver messageResolver) {
        return new EmailSender(mailSenderProvider, authProperties, messageResolver);
    }

    @Bean
    public VerifyCodeService verifyCodeService(VerifyCodeRepository verifyCodeRepository,
                                                EmailSender emailSender,
                                                AuthProperties authProperties) {
        return new VerifyCodeService(verifyCodeRepository, emailSender, authProperties);
    }

    /**
     * 注册和找回密码的事务边界。
     *
     * <p>用 {@link TransactionTemplate} 而不是 {@code @Transactional}，
     * 理由写在 {@link AuthService} 的类注释里：本项目的类都是手工装配的普通类，
     * 没有一个走 Spring 的注解代理。
     */
    @Bean
    public TransactionTemplate authTransactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public AuthService authService(UserRepository userRepository,
                                   VerifyCodeService verifyCodeService,
                                   PasswordEncoder passwordEncoder,
                                   JwtService jwtService,
                                   TransactionTemplate authTransactionTemplate) {
        return new AuthService(userRepository, verifyCodeService, passwordEncoder,
                jwtService, authTransactionTemplate);
    }

    @Bean
    public AuthInterceptor authInterceptor(JwtService jwtService) {
        return new AuthInterceptor(jwtService);
    }

    /**
     * 开机确保超级用户存在。{@code @Order(2)}，排在 {@code SchemaGuard} 后面
     * （它要按用户名查，而那个查询的正确性依赖唯一索引）。
     *
     * <p>它每次启动都会重置口令并解除禁用/软删，理由见 {@link RootAgentInitializer}——
     * 简短版：没有别的途径能造出超级用户，所以这个账号必须保证每次开机都是可用的。
     */
    @Bean
    public RootAgentInitializer rootAgentInitializer(UserRepository userRepository,
                                                     PasswordEncoder passwordEncoder,
                                                     AuthProperties authProperties) {
        return new RootAgentInitializer(userRepository, passwordEncoder, authProperties);
    }
}
