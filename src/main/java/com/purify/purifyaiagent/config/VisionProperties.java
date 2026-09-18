package com.purify.purifyaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 多模态（看图）相关配置，对应 application.yml 中的 {@code purify.vision.*}。
 *
 * <p>为什么单独配一个模型名：文本对话用的 {@code qwen-plus}、{@code qwen-max} 都是
 * 「纯文本」模型，把图片塞进去不会报错，而是被静默忽略——模型答得头头是道，
 * 其实根本没看到图。必须换成 VL（Vision-Language）系列才会真正读图。
 *
 * <p>换模型只是必要条件，不是充分条件：VL 模型在 DashScope 上走的是另一个端点
 * （{@code /aigc/multimodal-generation/generation}，普通模型走 {@code /aigc/text-generation/generation}），
 * 而选哪个端点由 {@code DashScopeChatOptions.multiModel} 决定，它的默认值是 {@code false}。
 * 模型名换成 qwen-vl-max 却忘了打开这个开关，就会收到 HTTP 400 "url error"。
 * 该开关在 {@code SlimApp} 里随看图 ChatClient 一起设置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "purify.vision")
public class VisionProperties {

    /** 视觉模型名，可选项如 qwen-vl-max / qwen-vl-plus。 */
    private String model = "qwen-vl-max";

    /**
     * 是否按高分辨率解析图片。
     *
     * <p>体检单、运动记录截图这类「小字图」必须开启，否则字会被压糊；
     * 普通食物照片关掉可以省一些 token。
     */
    private boolean highResolutionImages = true;
}
