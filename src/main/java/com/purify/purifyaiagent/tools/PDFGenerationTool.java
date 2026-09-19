package com.purify.purifyaiagent.tools;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.purify.purifyaiagent.config.AliyunOssProperties;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

/**
 * 把一段文字渲染成中文 PDF 并上传到阿里云 OSS，返回下载链接。
 * 体检报告、一周食谱这类「拿得走」的产出用它。
 *
 * <p><b>OSS 客户端是本类自己懒创建的，不是构造时注入的。</b>这样换来两件事：
 * <ul>
 *   <li>没配 OSS 的应用照样能启动——构造期不碰网络，也就不会因为凭证缺失或
 *       endpoint 写错把整个应用拖死；</li>
 *   <li>配了 OSS 的应用，客户端也只在<b>第一次真的调用</b>时才建起来。
 *       一次都没用过 PDF 的场景，不必养着一个连接池。</li>
 * </ul>
 * 代价是首次调用会慢一点（几十毫秒），对这个场景完全可以接受。
 * 客户端由 {@link #close()} 关闭，本类是 Spring 管理的 Bean，容器关闭时会调到。
 */
@Slf4j
public class PDFGenerationTool {

    private final AliyunOssProperties properties;

    /** 只存「去掉协议头」的域名，拼下载链接和建客户端都用它，避免两处各解析一遍。 */
    private final String endpointHost;

    /** 懒创建的客户端。用 volatile + 双重检查：工具有可能被并发调用。 */
    private volatile OSS ossClient;

    public PDFGenerationTool(AliyunOssProperties properties) {
        this.properties = properties;
        // 配置里可能写了 https://，也可能只写了域名，统一剥成裸域名
        this.endpointHost = properties.getEndpoint() == null
                ? ""
                : properties.getEndpoint().replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    @Tool(description = "把内容生成一份 PDF 文件并返回下载链接。"
            + "适合把食谱、计划、报告这类内容整理成用户能保存下来带走的东西。"
            + "生成好之后，务必把返回的下载链接原样放进你的最终回答里，用户要靠它去下载。")
    public String pdfGenerate(
            @ToolParam(description = "保存的文件名，例如 一周食谱.pdf，不要带路径") String fileName,
            @ToolParam(description = "要写进 PDF 的完整内容，支持换行") String content) {

        if (!properties.isConfigured()) {
            log.warn("[PDFGenerationTool] 被调用，但 aliyun.oss.* 没有配齐，无法生成 PDF");
            return "生成 PDF 失败：服务端没有配置对象存储，暂时用不了这个功能。"
                    + "请不要告诉用户「已生成」，可以改为把内容直接写在回答里。";
        }

        // 文件名由模型生成，去掉路径分隔符再加 UUID 前缀，避免路径穿越和同名覆盖
        String safeName = ToolFileNames.sanitize(fileName);
        if (!StringUtils.hasText(safeName)) {
            safeName = "document.pdf";
        }
        String objectKey = "pdf/" + UUID.randomUUID() + "_" + safeName;

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            try (PdfWriter pdfWriter = new PdfWriter(out);
                 PdfDocument pdf = new PdfDocument(pdfWriter);
                 Document document = new Document(pdf)) {
                document.setFont(cjkFont());
                document.add(new Paragraph(content == null ? "" : content));
            }
            // Document 关闭后内容才 flush 进 out，所以取字节必须放在这里
            byte[] bytes = out.toByteArray();

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType("application/pdf");
            metadata.setContentLength(bytes.length);
            client().putObject(properties.getBucket(), objectKey, new ByteArrayInputStream(bytes), metadata);

            String url = "https://" + properties.getBucket() + "." + endpointHost + "/" + objectKey;
            log.info("[PDFGenerationTool] 已生成 PDF：{}（{} 字节）", url, bytes.length);
            return "PDF 已生成，下载链接：" + url;
        } catch (Exception exception) {
            // 这里必须带上异常信息：上传失败几乎都是凭证、Bucket 名或权限的问题，
            // 只说一句「生成失败」的话，用户和开发者都无从下手
            log.error("[PDFGenerationTool] 生成 PDF 失败", exception);
            return "生成 PDF 失败：" + exception.getMessage() + "。请把失败原因如实告诉用户，不要谎称已生成。";
        }
    }

    @PreDestroy
    public void close() {
        OSS client = this.ossClient;
        if (client != null) {
            client.shutdown();
            log.info("[PDFGenerationTool] OSS 客户端已关闭");
        }
    }

    /**
     * 中文字体。
     *
     * <p>这里传的 {@code STSongStd-Light} 不是某个字体文件的名字，而是 iText 内置的
     * 「Adobe 亚洲字体包」注册名——它在 {@code font-asian} 包的 {@code cjk_registry.properties}
     * 里登记着，{@code PdfFontFactory} 认得出。看着像 iText 5 的写法，在 iText 8 里依然有效，
     * 前提是 {@code com.itextpdf:font-asian} 在类路径上（由 {@code itext-core} 带进来）。
     *
     * <p>不用系统字体（比如 Windows 的 simsun.ttc）是故意的：那样部署到 Linux 容器里就没有中文字体了，
     * 表现是整份 PDF 的中文全是方框。
     */
    private static PdfFont cjkFont() throws java.io.IOException {
        return PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
    }

    /** 第一次用到时才建客户端，之后复用。 */
    private OSS client() {
        OSS local = this.ossClient;
        if (local == null) {
            synchronized (this) {
                if (this.ossClient == null) {
                    // 显式带上 https：SDK 在 endpoint 不带协议头时默认走 http，
                    // 而 OSS 的公网接入点现在是要求 https 的，默认值会直接连不上
                    this.ossClient = new OSSClientBuilder().build("https://" + endpointHost,
                            properties.getAccessKeyId(), properties.getAccessKeySecret());
                    log.info("[PDFGenerationTool] 已创建 OSS 客户端：bucket={} endpoint={}",
                            properties.getBucket(), endpointHost);
                }
                local = this.ossClient;
            }
        }
        return local;
    }
}
