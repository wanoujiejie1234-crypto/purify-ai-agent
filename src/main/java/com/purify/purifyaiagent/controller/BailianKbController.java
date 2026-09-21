package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.auth.RequireAdmin;
import com.purify.purifyaiagent.config.RagStore;
import com.purify.purifyaiagent.model.BailianChunkPage;
import com.purify.purifyaiagent.model.BailianDocumentPage;
import com.purify.purifyaiagent.model.BailianSyncRequest;
import com.purify.purifyaiagent.model.BailianSyncStatus;
import com.purify.purifyaiagent.model.BatchIndexResult;
import com.purify.purifyaiagent.rag.bailian.BailianKbSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 「百炼同步」接口 —— 知识库管理页上那张卡片的全部后端入口。
 *
 * <pre>
 *   GET  /api/knowledge/bailian/status                  配置齐不齐、缺哪几项、可选分类
 *   GET  /api/knowledge/bailian/documents               百炼文件清单 + 每份在本地的同步状态
 *   GET  /api/knowledge/bailian/documents/{id}/chunks   某一份在百炼侧的切片（审切片用）
 *   POST /api/knowledge/bailian/sync                    把选中的几份搬进本地向量库
 *   GET  /api/knowledge/bailian/probe                   管控面联调自检（排障用）
 * </pre>
 *
 * <p><b>装配条件与 {@code KnowledgeBaseController} 逐字一致</b>，两处都引用
 * {@link RagStore} 里的常量。这样「服务在不在」和「接口在不在」永远同一件事，
 * 不会出现「Bean 建了、接口 404」这种自相矛盾的状态——{@code RagStore} 的类注释
 * 里把这类状态的代价讲得很清楚。
 *
 * <p><b>{@link RequireAdmin} 覆盖下面全部方法</b>，理由和 {@code KnowledgeBaseController}
 * 相同：这些动作改的都不是自己一个人的数据。同步一份文档会写进全站共用的知识库，
 * 也会（在同名时）覆盖掉已有切片——影响所有人的问答。普通用户照样能聊天、
 * 能查知识库、能看检索自检，他们只是不能改它。
 *
 * <p>{@code /probe} 也在这个类里、也带同一个注解，但它的性质不同：它回显的是
 * <b>百炼那边</b>的原始返回。所以那里有一条硬规矩——<b>AK/SK 一律脱敏</b>，
 * 见 {@code BailianConsoleClient#mask}。这个响应会出现在管理页上，会被截图、
 * 会被贴进群里排障，secret 一旦出现在里面就等于泄露了。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge/bailian")
@RequireAdmin
@ConditionalOnProperty(prefix = "purify.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = RagStore.PREFIX, name = "store", havingValue = RagStore.PGVECTOR)
public class BailianKbController {

    private final BailianKbSyncService syncService;

    public BailianKbController(BailianKbSyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * 配置自检：配齐了没有、缺哪几项、可选分类有哪些。<b>不发远程请求</b>，
     * 所以前端可以在卡片挂载时放心调用。
     *
     * <p>{@code categories} 由后端直出，前端照着渲染。它原本在前端是硬编码的一个数组，
     * 而这三个值必须和 {@code purify.rag.router.categories} 一字不差——
     * 新卡片再抄一份就是第三处。多一处硬编码就多一处会漂移的地方。
     */
    @GetMapping("/status")
    public BailianSyncStatus status() {
        return syncService.status();
    }

    /**
     * 百炼的文件清单，每行带上它在本地的同步状态。
     *
     * <p><b>本地状态由一条 SQL 批量查出，远程只调一次。</b>这个页面上不会出现
     * 「百炼侧有多少片」——那个数要逐份调切片接口才拿得到，一页二十行就是二十次
     * 远程请求（还带 10 QPS 限流）。想看片数就展开那一行。
     *
     * @param status 百炼侧的文档状态筛选，默认只看 {@code FINISH}（只有它有切片可拉）。
     *               传 {@code ALL} 看全部——<b>别把未完成的藏起来</b>，
     *               用户会问「我刚传上去的那份去哪了」
     * @param name   按文件名模糊筛；空表示不筛
     */
    @GetMapping("/documents")
    public BailianDocumentPage documents(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String name) {
        return syncService.listDocuments(page, size, status, name);
    }

    /**
     * 某一份文档在百炼侧的切片。<b>正文不截断</b>——这个接口存在的意义就是让超级用户
     * 审「百炼到底切成了什么样」，截断了就审不了。
     *
     * <p>代价是响应可能很大，所以默认每页 20 条，而且由「展开某一行」按需触发，
     * 不在列表页预取。{@code pageSize} 超过 100 直接报错而不是静默截断。
     */
    @GetMapping("/documents/{fileId}/chunks")
    public BailianChunkPage chunks(@PathVariable String fileId,
                                   @RequestParam(defaultValue = "1") int pageNum,
                                   @RequestParam(defaultValue = "20") int pageSize) {
        return syncService.listChunks(fileId, pageNum, pageSize);
    }

    /**
     * 把选中的文档搬进本地向量库。
     *
     * <p><b>逐份独立，一份失败不影响其余</b>，响应体里同时给「成功了哪些」和
     * 「失败了为什么」。抛出去让整批 400 的话，已经成功的那几份用户就以为也白传了。
     *
     * <p><b>重复同步是安全的</b>（先按来源删干净再写），所以用户看到超时后重试
     * 不会写出重复切片。前端据此在超时提示里明说这一点。
     */
    @PostMapping("/sync")
    public BatchIndexResult sync(@RequestBody BailianSyncRequest request) {
        log.info("[百炼同步] 收到同步请求：{} 份", request == null || request.items() == null
                ? 0 : request.items().size());
        return syncService.sync(request == null ? null : request.items());
    }

    /**
     * 管控面联调自检：AK 有没有权限、IndexId 对不对、文件标识是哪个字段、元数据里有什么。
     *
     * <p>这四件事本地验证不了，只能实调一次。返回的是原始形状而不是规整过的 DTO——
     * 这个接口存在的意义就是让人看见「百炼到底回了什么」，规整化会把要找的差异抹掉。
     *
     * <p>和 {@code RagController#search}（{@code /api/rag/search}）是同一类东西：
     * 一个自检检索，一个自检同步。两者互相引用，改一个时记得看看另一个。
     */
    @GetMapping("/probe")
    public Map<String, Object> probe() {
        log.info("[百炼同步] 执行管控面联调自检");
        return syncService.probe();
    }
}
