package com.purify.purifyaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用自身的对外地址，对应 application.yml 中的 {@code purify.server.*}。
 *
 * <p>存在的理由只有一个：工具要能给用户回一个<b>点得开</b>的链接。
 * {@code ResourceDownloadTool} 把文件落在本地磁盘上，但用户是在浏览器里，
 * 一句「已下载到 D:\...\tmp\download\x.pdf」对他是没有用的，得给出
 * {@code http://主机:端口/files/download/x.pdf} 这样的地址。
 *
 * <p>工具不是 HTTP 请求的一部分，拿不到当前请求的 Host，所以这个前缀只能配。
 * 部署到别的机器或换了端口要记得改——不改的表现是链接指向 localhost，用户点不开。
 */
@Data
@Component
@ConfigurationProperties(prefix = "purify.server")
public class ServerProperties {

    /** 形如 {@code http://localhost:8080}，不要带结尾的斜杠。 */
    private String baseUrl = "http://localhost:8080";
}
