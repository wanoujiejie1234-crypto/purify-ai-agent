package com.purify.purifyaiagent.auth;

import com.purify.purifyaiagent.config.AuthProperties;
import com.purify.purifyaiagent.i18n.MessageResolver;
import com.purify.purifyaiagent.i18n.Messages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

/**
 * 发验证码邮件。
 *
 * <p><b>邮件没配好时不是失败，而是降级成「只打日志」。</b>这是本项目对其他可选依赖的一贯做法
 * （{@code ToolConfig} 对缺密钥的工具、{@code SlimApp} 对可选 Advisor 都是这样）：
 * 配不齐就少一个能力，应用照常起。这里的价值很具体——本地开发时不用为了试一次注册流程
 * 去申请一个邮箱授权码，看日志就行。
 *
 * <p>用 {@link ObjectProvider} 而不是直接注入 {@link JavaMailSender}：
 * {@code spring.mail.host} 没配时容器里压根没有这个 Bean，直接注入会让应用起不来。
 * {@code getIfAvailable()} 拿不到就返回 null，于是「没配」是一种正常状态，不是故障。
 *
 * <p><b>验证码绝不返回给调用方、更不放进接口响应体</b>。日志里那条是给开发者的后门，
 * 而响应体是给攻击者的——顺着它就能把 6 位数的搜索空间直接砍掉。
 * 日志那条也带 {@code [未发信]} 前缀，免得有人带着 {@code enabled: false} 上线还以为发出去了。
 */
@Slf4j
public class EmailSender {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final AuthProperties authProperties;
    private final MessageResolver messageResolver;

    public EmailSender(ObjectProvider<JavaMailSender> mailSenderProvider,
                       AuthProperties authProperties,
                       MessageResolver messageResolver) {
        this.mailSenderProvider = mailSenderProvider;
        this.authProperties = authProperties;
        this.messageResolver = messageResolver;
    }

    /**
     * 把验证码发给指定邮箱。
     *
     * <p><b>发送失败会抛出去</b>，不像项目里其他「记账类」的写操作那样只记日志。
     * 理由是这里的失败直接影响用户下一步能做什么：如果邮件没发出去却回了 200，
     * 用户会一直等一封不会来的邮件，然后以为是自己邮箱填错了或者系统坏了。
     * 让他当场看到「发送失败，请稍后重试」比让他等要好。
     */
    public void sendVerificationCode(String email, String code, VerificationPurpose purpose) {

        // 清理前后空白，避免 "xxx@qq.com " 这种地址导致 SMTP 报错
        email = email == null ? null : email.trim();

        // 基础邮箱格式校验
        if (!StringUtils.hasText(email) || !email.contains("@")) {
            throw new IllegalArgumentException("邮箱地址格式不正确");
        }

        // 邮件是**给用户看的**，所以它也跟着语言走。语言在请求线程上取一次
        // （发验证码是个同步接口），然后一路传到下面的拼装里
        Messages i18n = messageResolver.current();
        String subject = subjectOf(i18n, purpose);
        String body = bodyOf(i18n, code, purpose);

        JavaMailSender sender = authProperties.getMail().isEnabled()
                ? mailSenderProvider.getIfAvailable()
                : null;

        if (sender == null) {
            log.warn("[未发信] 邮件未启用，验证码只打在这里 → 邮箱={} 用途={} 验证码={}。",
                    email, purpose, code);
            return;
        }

        log.info("[邮件] 准备发送验证码：from={}，to={}，用途={}",
                fromAddress(sender), email, purpose);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress(sender));
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);

        sender.send(message);

        log.info("[邮件] 验证码已发送：邮箱={} 用途={}", email, purpose);
    }

    /**
     * 发件人地址。
     *
     * <p>优先用配置里显式的 {@code purify.auth.mail.from}，没配就退回
     * {@code spring.mail.username}——绝大多数邮箱服务商的 SMTP 要求
     * 「发件人 = 登录账号」，两者本来就该是同一个值，让配置少一项。
     * 两个都没有时返回 null，交给 JavaMail 用它自己的默认值（通常是本地主机名，
     * 多半会被服务商拒掉，但那时抛出的异常信息足够指向问题）。
     */
    private String fromAddress(JavaMailSender sender) {
        String configured = authProperties.getMail().getFrom();
        if (StringUtils.hasText(configured)) {
            return configured.trim();
        }
        if (sender instanceof org.springframework.mail.javamail.JavaMailSenderImpl impl) {
            return impl.getUsername();
        }
        return null;
    }

    private static String subjectOf(Messages i18n, VerificationPurpose purpose) {
        return i18n.get(purpose == VerificationPurpose.REGISTER
                ? "auth.mail.subjectRegister"
                : "auth.mail.subjectReset");
    }

    /**
     * 邮件正文。
     *
     * <p>刻意写得朴素：纯文本 + 把验证码单独放一行。不做 HTML 模板——
     * 验证码邮件是「看一眼就把邮件删掉」的东西，花哨的模板只会让它在
     * 垃圾邮件过滤器那里多挨几分，而这类邮件本来就最容易进垃圾箱。
     */
    private static String bodyOf(Messages i18n, String code, VerificationPurpose purpose) {
        return i18n.get(purpose == VerificationPurpose.REGISTER
                ? "auth.mail.bodyRegister"
                : "auth.mail.bodyReset", code);
    }
}
