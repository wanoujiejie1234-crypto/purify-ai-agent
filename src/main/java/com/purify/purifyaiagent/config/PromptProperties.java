package com.purify.purifyaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Prompt 模板配置，对应 application.yml 中的 {@code purify.prompt.*}。
 *
 * <p>把模板放进资源文件而不是写在 Java 文本块里，是为了让「调提示词」这件事
 * 不需要改代码、不需要重新编译，产品同学也能直接改。
 */
@Data
@Component
@ConfigurationProperties(prefix = "purify.prompt")
public class PromptProperties {

    /**
     * 模板文件所在目录，支持 classpath: 与 file: 前缀。
     *
     * <p>模板名就是文件名去掉 {@code .st} 后缀，例如 {@code classpath:prompts/} 下的
     * {@code slim-app-system.st} 对应模板名 {@code slim-app-system}。
     */
    private String location = "classpath:prompts/";

    /** 系统提示词里对用户的称呼（中文），可以通过 {nickname} 变量注入模板。 */
    private String nickname = "朋友";

    /**
     * 英文模板里的称呼。留空表示沿用 {@link #nickname}。
     *
     * <p>不放进 {@code messages*.properties} 是因为它和上面的中文那份是**一对配置**：
     * 产品同学改称呼时想改的是「系统提示词里怎么叫用户」，两处放在一起才找得全。
     */
    private String nicknameEn = "there";
}
