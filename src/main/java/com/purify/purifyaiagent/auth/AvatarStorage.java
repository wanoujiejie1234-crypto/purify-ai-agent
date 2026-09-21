package com.purify.purifyaiagent.auth;

import com.purify.purifyaiagent.constant.FileConstant;
import com.purify.purifyaiagent.exception.ApiException;
import com.purify.purifyaiagent.media.ImageTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 把用户头像写到本地磁盘，返回可直接放进 {@code <img src>} 的地址。
 *
 * <p><b>存本地而不是传 OSS</b>，和 PDF 那条链路的选择不同。理由：头像是个几十 KB 的
 * 小图，而 OSS 那条路的价值在于「用户要把文件带走」，头像从来不需要被带走；
 * 换来的是少一次外网调用、少一份凭证依赖，也不用为头像的密钥失效再查一遍配置
 * （OSS 那套被占位值坑过一次，见 application-local.yml）。
 * 代价是重新部署会丢——头像本来就是可以重传的东西，这个代价可以接受。
 *
 * <p><b>返回相对地址而不是拼好域名。</b>{@code ResourceDownloadTool} 拼的是绝对地址，
 * 那是因为它返回的链接要能被用户复制到聊天软件里去；头像只在页面里用，
 * 相对地址跟着当前 origin 走，换域名、换端口、走反向代理都不用改，
 * 也不依赖 {@code purify.server.base-url} 配对。
 */
@Slf4j
public class AvatarStorage {

    /** 对外的访问前缀，要和 {@code StaticResourceConfig} 挂出来的路径一致。 */
    private static final String URL_PREFIX = "/files/avatar/";

    private final Path directory;

    public AvatarStorage() {
        this(Paths.get(FileConstant.AVATAR_DIR));
    }

    /** 供测试直接用临时目录。 */
    public AvatarStorage(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    /**
     * 存一份头像，返回形如 {@code /files/avatar/<uuid>.png} 的地址。
     *
     * <p>文件名**完全由服务端生成**（UUID + 从白名单里取的扩展名），
     * 用户给的原始文件名一个字符都不参与——这样就不用去防路径穿越了，
     * 也不用担心「同一个用户传两次同名文件互相覆盖」。
     * 用 UUID 而不是 userId 命名，还顺带避免了「换了头像但浏览器拿的是缓存」：
     * 地址变了，缓存自然失效。
     */
    public String store(MultipartFile file, long maxBytes) {
        if (file == null || file.isEmpty()) {
            throw ApiException.invalidImage("error.auth.avatarRequired");
        }
        ImageTypes.requireWithinSize(file, maxBytes, "头像");
        // 先判定类型再落盘：明显不是图片的请求不该在磁盘上留下任何痕迹
        String extension = ImageTypes.safeExtension(file);

        String fileName = UUID.randomUUID() + "." + extension;
        Path target = directory.resolve(fileName);

        try {
            Files.createDirectories(directory);
            file.transferTo(target);
        } catch (IOException exception) {
            // 把原因带出来：最常见的是目录没写权限，只说一句「上传失败」没法排查
            log.error("[Avatar] 写入头像失败：{}", target, exception);
            throw ApiException.invalidImage("error.auth.avatarSaveFailed", exception.getMessage());
        }

        log.info("[Avatar] 已保存头像：{}（{} 字节）", target, file.getSize());
        return URL_PREFIX + fileName;
    }

    /**
     * 删掉上一张头像。
     *
     * <p><b>失败只记日志不上抛。</b>用户换头像这个动作已经成功了，
     * 为了一个删不掉的旧文件把整个请求变成 500，是本末倒置
     * （同 {@code ChatRecordRepository#save} 的取向）。
     */
    public void deleteQuietly(String url) {
        String fileName = fileNameOf(url);
        if (fileName == null) {
            return;
        }
        try {
            Files.deleteIfExists(directory.resolve(fileName));
        } catch (RuntimeException | IOException exception) {
            log.warn("[Avatar] 删除旧头像失败（不影响本次更换）：{} 原因={}", url, exception.getMessage());
        }
    }

    /**
     * 从头像地址里取出文件名，取不到返回 {@code null}。
     *
     * <p>必须挡住带路径分隔符的值：这个字段来自数据库，而数据库里的值理论上是我们自己写的，
     * 但「理论上」不是安全边界——一旦有人手工改过库、或者哪天写入路径多了个口子，
     * 这里就成了一个用 {@code ../../} 删任意文件的原语。只认纯文件名，
     * 有分隔符就当作不是我们的地址，直接不删。
     */
    private static String fileNameOf(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return null;
        }
        String name = url.substring(URL_PREFIX.length());
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            log.warn("[Avatar] 头像地址不像本服务生成的，跳过删除：{}", url);
            return null;
        }
        return name;
    }
}
