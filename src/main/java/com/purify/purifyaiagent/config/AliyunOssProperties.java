package com.purify.purifyaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 阿里云 OSS 配置，对应 application.yml / application-local.yml 中的 {@code aliyun.oss.*}。
 *
 * <p>给 {@code PDFGenerationTool} 用：生成的方案 PDF 要落到对象存储上，再回一个能点的链接。
 *
 * <p><b>拆成两个文件配</b>：{@code access-key-id} / {@code access-key-secret} 是凭证，
 * 放在被 gitignore 挡住的 application-local.yml 里；{@code endpoint} / {@code bucket}
 * 是环境信息、不是机密，放在 application.yml 里，这样换一台机器只要补凭证。
 *
 * <p>四项只要缺一项，{@link #isConfigured()} 就是 false，PDF 工具不会被注册——
 * 「没配」和「配错了」是两回事：前者应该安静地少一个工具，后者应该在调用时报错。
 */
@Data
@Component
@ConfigurationProperties(prefix = "aliyun.oss")
public class AliyunOssProperties {

    /** 访问凭证 ID。 */
    private String accessKeyId;

    /** 访问凭证密钥。 */
    private String accessKeySecret;

    /**
     * 服务接入点，例如 {@code oss-cn-hangzhou.aliyuncs.com}。
     *
     * <p>填域名即可，带 {@code https://} 也能容忍：SDK 自己会拼协议头。
     */
    private String endpoint;

    /** 存放 PDF 的 Bucket 名称。 */
    private String bucket;

    /** 四项配齐了才算可用。 */
    public boolean isConfigured() {
        return StringUtils.hasText(accessKeyId)
                && StringUtils.hasText(accessKeySecret)
                && StringUtils.hasText(endpoint)
                && StringUtils.hasText(bucket);
    }
}
