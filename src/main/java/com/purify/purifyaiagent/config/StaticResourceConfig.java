package com.purify.purifyaiagent.config;

import com.purify.purifyaiagent.constant.FileConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把下载目录挂成静态资源，让 {@code ResourceDownloadTool} 返回的链接真的打得开。
 *
 * <p>没有这个类的话，那个工具的一切都是对的——文件确实下载到了磁盘上，
 * 返回的 URL 也确实拼得工工整整——只是 <b>没人处理这个路径，访问就是 404</b>。
 * 而工具的描述里明确要求模型「把链接放进最终回答」，于是用户会拿到一个点开是 404 的链接。
 * 这种「所有环节都成功，结果不可用」的问题最难发现，所以映射这一层必须补齐。
 *
 * <p>只映射 {@code /files/download/**} 这一个目录，不映射 {@code tmp} 的其它子目录：
 * {@code FileOperationTool} 写的文件也在 {@code tmp} 下，那些不该随便对外暴露。
 * 路径里的文件名在落盘前已经被清洗过（见 {@code ToolFileNames}），
 * 不含路径分隔符，因此不存在 {@code ../} 绕出去读别的文件的问题。
 */
@Slf4j
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    /** 对外的访问前缀，要和 {@code ResourceDownloadTool} 拼出来的地址一致。 */
    private static final String DOWNLOAD_URL_PATTERN = "/files/download/**";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 用 toUri() 而不是拼 "file:" + 路径：Windows 上路径分隔符是反斜杠，
        // 直接拼出来的是 file:D:\...\download，Spring 解析不了。
        // 目录还不存在时 toUri() 不带结尾斜杠，而资源位置少了它就匹配不到子路径，所以补上
        String location = Paths.get(FileConstant.DOWNLOAD_DIR).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }

        registry.addResourceHandler(DOWNLOAD_URL_PATTERN).addResourceLocations(location);
        log.info("[StaticResourceConfig] 已把 {} 映射到 {}（下载工具的链接指向这里）",
                DOWNLOAD_URL_PATTERN, Path.of(FileConstant.DOWNLOAD_DIR).toAbsolutePath());
    }
}
