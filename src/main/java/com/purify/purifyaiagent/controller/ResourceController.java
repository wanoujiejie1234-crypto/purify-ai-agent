package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.auth.CurrentUser;
import com.purify.purifyaiagent.auth.LoginUser;
import com.purify.purifyaiagent.auth.RequireLogin;
import com.purify.purifyaiagent.constant.FileConstant;
import com.purify.purifyaiagent.exception.ApiException;
import com.purify.purifyaiagent.i18n.MessageResolver;
import com.purify.purifyaiagent.i18n.Messages;
import com.purify.purifyaiagent.model.ResourceView;
import com.purify.purifyaiagent.resource.ResourceKind;
import com.purify.purifyaiagent.resource.UserResource;
import com.purify.purifyaiagent.resource.UserResourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * 资料库：智能体在对话里产出的文件。
 *
 * <pre>
 *   GET    /api/resources              列出当前用户的产出
 *   GET    /api/resources/{id}/download 取一份文件
 *   DELETE /api/resources/{id}         删掉一条
 * </pre>
 *
 * <p>记录由工具在产出时写（见 {@code ResourceRecorder}），这里只负责读和删。
 *
 * <h2>两条下载路径</h2>
 *
 * <p>产出分两类，取法不一样：
 * <ul>
 *   <li>{@link ResourceKind#PDF} / {@link ResourceKind#DOWNLOAD} —— 有对外可打开的地址
 *       （OSS 或 {@code /files/download/}）。界面上直接用一个普通链接，**不需要走这里**；</li>
 *   <li>{@link ResourceKind#WRITTEN} —— 落在 {@code tmp/file}，那个目录故意没有对外映射
 *       （见 {@code FileConstant#WORK_FILE_DIR}）。只能从这个接口拿，所以它是<b>带鉴权的</b>。</li>
 * </ul>
 * 有地址的那两类也走这个接口时会 302 过去，不再读一遍本地磁盘——这样接口对三种产出
 * 都成立，前端哪天想统一口径也不用改后端。
 *
 * <h2>为什么归属失败一律 404</h2>
 *
 * <p>「这条记录不存在」和「存在但不是你的」返回<b>完全相同</b>的 404 和文案。
 * 分开写的话（比如不属于就 403），这个接口就成了一个 id 探测器：
 * 拿一串 id 试过去，从状态码就能知道哪些 id 真实存在。
 * 和 {@code chat.SessionAccess} 是同一套处理。
 */
@Slf4j
@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    /** 一次最多返回多少条。够用即可——资料库不是需要翻几十页的东西。 */
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    /**
     * 归属校验失败时的统一说法。
     *
     * <p>写成常量而不是在四处各写一遍：这个键是「不可区分」这件事的<b>全部</b>依据，
     * 两处各写一句、以后有人顺手改了一处的措辞，那个 id 探测口子就悄无声息地开了。
     *
     * <p>存键不存句子（见 {@code ApiException} 的类注释）：两种语言下各有一句话，
     * 但只要前端带着 {@code Accept-Language}，同一个请求里的响应就一定是同一种语言。
     */
    private static final String NOT_FOUND_KEY = "error.resource.notFound";

    private final UserResourceRepository repository;
    private final MessageResolver messageResolver;

    public ResourceController(UserResourceRepository repository, MessageResolver messageResolver) {
        this.repository = repository;
        this.messageResolver = messageResolver;
    }

    /** 当前用户的产出，最近的在前。 */
    @GetMapping
    @RequireLogin
    public List<ResourceView> list(@RequestParam(required = false) Integer limit,
                                   @CurrentUser LoginUser me) {
        int size = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        // 种类标签在这里翻（kindLabel）。取一次带下去，不在循环里反复取
        Messages messages = messageResolver.current();
        return repository.findByUser(me.id(), size).stream()
                .map(resource -> ResourceView.from(resource, messages))
                .toList();
    }

    /**
     * 取一份文件。
     *
     * <p>有对外地址的（PDF / 下载回来的）直接 302 过去，没有的（写出来的文件）
     * 才读本地磁盘往回写。
     */
    @GetMapping("/{id}/download")
    @RequireLogin
    public ResponseEntity<?> download(@PathVariable long id, @CurrentUser LoginUser me) {
        UserResource resource = requireOwned(id, me);

        if (resource.hasDirectUrl()) {
            // 302 而不是自己代理一遍：那些地址本来就是公开的，代理一层只是白白占着
            // 本服务的线程和带宽，而且大文件会在这里被完整地读一遍
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(resource.url()))
                    .build();
        }

        Path file = localFileOf(resource)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND_KEY));

        if (!Files.isReadable(file)) {
            // 文件被清理掉、或者手工删了。对用户来说和「这条记录没了」是同一件事，
            // 所以用同一句话——不必让他去分辨「记录在但文件不在」
            throw ApiException.notFound(NOT_FOUND_KEY);
        }

        ContentDisposition disposition = ContentDisposition.attachment()
                // 中文文件名必须走这个重载：它生成的是 RFC 5987 的 filename*，
                // 直接把中文塞进 header 是个非法值，结果是文件名乱码而且**不报错**
                .filename(resource.title(), StandardCharsets.UTF_8)
                .build();

        MediaType mediaType = MediaTypeFactory.getMediaType(resource.title())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        return ResponseEntity.ok()
                .header("Content-Disposition", disposition.toString())
                .contentType(mediaType)
                .body((Resource) new FileSystemResource(file));
    }

    /** 删一条。连着本地文件一起删。 */
    @DeleteMapping("/{id}")
    @RequireLogin
    public ResponseEntity<Void> delete(@PathVariable long id, @CurrentUser LoginUser me) {
        UserResource resource = requireOwned(id, me);

        if (repository.deleteOwned(me.id(), id) == 0) {
            // 并发：两次删除撞在一起，后到的这条没删到任何行。对调用方来说结果一样
            return ResponseEntity.noContent().build();
        }

        // 记录已经删了，文件删不掉也不该让请求失败（同 ChatRecordRepository#save 的取向）。
        // 注意 OSS 上的对象**不删**：那需要在这里引入一个带凭证的客户端，
        // 而删错一个 objectKey 是不可逆的。留着的对象由 bucket 的生命周期规则去清，
        // 比在业务代码里手滑一次安全
        localFileOf(resource).ifPresent(file -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException exception) {
                log.warn("[Resource] 删除本地文件失败（记录已删）：{} 原因={}", file, exception.getMessage());
            }
        });

        log.info("[Resource] 已删除：user={} id={} title={}", me.id(), id, resource.title());
        return ResponseEntity.noContent().build();
    }

    /** 取一条属于当前用户的记录，取不到就 404。 */
    private UserResource requireOwned(long id, LoginUser me) {
        return repository.findOwned(me.id(), id)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND_KEY));
    }

    /**
     * 本地文件的完整路径。只有落在本地的两类才有，PDF 在 OSS 上所以返回空。
     *
     * <p>目录由 kind 决定，不从库里的任何字段拼——那样等于让数据库决定读哪个目录。
     *
     * <p>还做了一次「解析出来的路径必须在目录之内」的检查。<b>落盘时文件名已经清洗过</b>
     * （见 {@code ToolFileNames}），所以正常走不到这里；但 {@code storage_key} 是库里的值，
     * 而库是可以手工改的。少了这道检查，一条被改过的记录就成了一个读任意文件的原语。
     */
    private static Optional<Path> localFileOf(UserResource resource) {
        String directory = switch (resource.kind() == null ? ResourceKind.WRITTEN : resource.kind()) {
            case WRITTEN -> FileConstant.WORK_FILE_DIR;
            case DOWNLOAD -> FileConstant.DOWNLOAD_DIR;
            // PDF 在 OSS 上，没有本地文件
            case PDF -> null;
        };
        if (directory == null || resource.storageKey() == null || resource.storageKey().isBlank()) {
            return Optional.empty();
        }

        Path base = Paths.get(directory).toAbsolutePath().normalize();
        Path file = base.resolve(resource.storageKey()).normalize();
        if (!file.startsWith(base)) {
            log.warn("[Resource] storage_key 指向目录之外，已拒绝：id={} key={}",
                    resource.id(), resource.storageKey());
            return Optional.empty();
        }
        return Optional.of(file);
    }
}
