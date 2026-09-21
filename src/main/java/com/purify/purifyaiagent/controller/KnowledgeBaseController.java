package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.auth.RequireAdmin;
import com.purify.purifyaiagent.config.PgVectorProperties;
import com.purify.purifyaiagent.config.RagStore;
import com.purify.purifyaiagent.exception.ApiException;
import com.purify.purifyaiagent.model.BatchIndexResult;
import com.purify.purifyaiagent.model.ChunkPreview;
import com.purify.purifyaiagent.model.DocumentIndexResult;
import com.purify.purifyaiagent.model.KnowledgeBaseStats;
import com.purify.purifyaiagent.model.KnowledgeDocumentPage;
import com.purify.purifyaiagent.rag.pgvector.PgVectorIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地向量库的文档管理接口 —— 流程图里「文档收集」那个入口。
 *
 * <p>三个动作：
 * <pre>
 *   POST   /api/knowledge/documents   上传一份文档建索引（重复上传同一文件名会覆盖）
 *   DELETE /api/knowledge/documents   按来源删除一份文档的全部切片
 *   GET    /api/knowledge/stats       看当前索引的规模
 * </pre>
 *
 * <p><b>只在本地向量库被装配时存在</b>（{@code store=pgvector}）：
 * 走百炼云知识库时，文档是在百炼控制台里上传的，本地根本没有可写的向量库，
 * 这几个接口留着只会让人误以为能用。
 *
 * <p>条件与 {@code PgVectorRagConfig} <b>必须保持一致</b>，所以「{@code PgVectorIndexService}
 * 在不在」和「接口在不在」永远是同一件事。漏了的话 Bean 建了、接口却 404，
 * 本地那一路从此没有灌数据的入口。两边都引用 {@link RagStore} 里的常量，
 * 就是为了让这个一致性由编译器保证，而不是靠人记着。
 *
 * <p><b>{@link RequireAdmin}：这一整个模块只对超级用户开放。</b>类级注解覆盖了下面全部方法
 * （上传、批量上传、预览、删除、列表、统计），因为这些动作改的都<b>不是自己一个人的数据</b>——
 * 知识库是全站共用的，删一份文档会影响所有人的问答。普通用户照样能<b>聊</b>天、
 * 照样能<b>查</b>知识库，他们只是不能改它。
 *
 * <p>注意 {@code RagController}（{@code /api/rag/search}）也标了同一个注解——
 * 那个接口返回的就是知识库内容，属于同一个模块，不一起管起来等于留了个后门。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequireAdmin
@ConditionalOnProperty(prefix = "purify.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = RagStore.PREFIX, name = "store", havingValue = RagStore.PGVECTOR)
public class KnowledgeBaseController {

    private final PgVectorIndexService indexService;

    private final PgVectorProperties pgVectorProperties;

    public KnowledgeBaseController(PgVectorIndexService indexService, PgVectorProperties pgVectorProperties) {
        this.indexService = indexService;
        this.pgVectorProperties = pgVectorProperties;
    }

    /**
     * 上传一份文档并建立索引。
     *
     * <p>用 multipart 而不是把正文塞进请求体，是为了和 {@code /api/slim/image} 保持一致，
     * 用 curl 试的时候少想一件事：
     * <pre>
     *   curl -X POST http://localhost:8080/api/knowledge/documents \
     *        -F "file=@减脂食谱.md" -F "classification=食物热量"
     * </pre>
     *
     * <p>{@code classification} 必须与 {@code purify.rag.router.categories} 里配的分类值
     * 一字不差——检索时是按这个字段做等值过滤的，值写错了查不到任何东西而且不报错，
     * 所以在入口就拦下来。
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentIndexResult upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam String classification) {
        log.info("[知识库] 收到上传：filename={} size={}B classification={}",
                file.getOriginalFilename(), file.getSize(), classification);

        return uploadOne(file, classification);
    }

    /**
     * 批量上传建索引。一次请求收多份文件，逐份独立处理。
     *
     * <p>用途是「目录批量导入」：管理页里选中一个目录，浏览器会把里面的文件一次性传上来
     * （{@code webkitdirectory} 那个能力），所以不需要服务端去扫磁盘路径——
     * 让接口按路径读服务器上的文件，等于开了一个任意文件读取的口子，
     * 而这个项目的部署环境未必只跑在本机。
     *
     * <p><b>某一份失败不影响其余。</b>详略见 {@link BatchIndexResult} 的注释。
     *
     * <p>{@code classifications} 如果给了，要和 {@code files} 一一对应；
     * 只给一个值则整批都用它——目录导入时最常见的就是「这个目录全归一类」。
     */
    @PostMapping(value = "/documents/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BatchIndexResult uploadBatch(@RequestParam("files") List<MultipartFile> files,
                                        @RequestParam String classification,
                                        @RequestParam(required = false) List<String> classifications) {
        if (files == null || files.isEmpty()) {
            throw ApiException.unsupportedDocument("error.kb.noFiles");
        }
        List<String> perFile = resolveClassifications(files.size(), classification, classifications);

        log.info("[知识库] 批量上传：{} 份文件", files.size());

        List<BatchIndexResult.Item> items = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String filename = file == null ? "(空)" : file.getOriginalFilename();
            String category = perFile.get(i);
            try {
                DocumentIndexResult result = uploadOne(file, category);
                items.add(BatchIndexResult.Item.ok(
                        result.source(), result.chunkCount(), result.characterCount()));
            }
            catch (RuntimeException exception) {
                // 单份失败只记一行，不往上抛：抛出去会让整批 400，
                // 已经成功的那几份用户就以为也白传了
                log.warn("[知识库] 批量上传中 {} 失败：{}", filename, exception.getMessage());
                items.add(BatchIndexResult.Item.failed(filename, exception.getMessage()));
            }
        }

        long succeeded = items.stream().filter(BatchIndexResult.Item::ok).count();
        log.info("[知识库] 批量上传完成：成功 {} 份，失败 {} 份", succeeded, items.size() - succeeded);
        return new BatchIndexResult((int) succeeded, (int) (items.size() - succeeded), items);
    }

    /**
     * 索引前预览：这份文档会被切成什么样，<b>不写库</b>。
     *
     * <p>切片参数调一次要重新打包、重启、上传、再等向量化跑完，才知道切得好不好；
     * 而切得不好的表现往往是「检索就是查不到」，排查方向很多。先预览能省掉这一轮试错。
     */
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChunkPreview preview(@RequestParam("file") MultipartFile file,
                                @RequestParam String classification) {
        if (file == null || file.isEmpty()) {
            throw ApiException.unsupportedDocument("error.kb.emptyFile");
        }
        return indexService.preview(file.getOriginalFilename(), file.getResource(), classification);
    }

    /** 按来源删除该文档的全部切片。删不到不报错。 */
    @DeleteMapping("/documents")
    public void delete(@RequestParam String source) {
        log.info("[知识库] 删除来源：{}", source);
        indexService.deleteBySource(source);
    }

    /**
     * 文档列表：一份文档一行，按最近入库时间倒序。
     *
     * <p>和 {@code /stats} 的分工：那个回答「有多少」（总量与分类分布），
     * 这个回答「有哪些」（逐份明细），管理页要能单独删掉某一份就得靠它。
     */
    @GetMapping("/documents")
    public KnowledgeDocumentPage documents(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return indexService.listDocuments(page, size);
    }

    /** 当前索引规模：切片总数、文档份数，以及按来源/分类的分组计数。 */
    @GetMapping("/stats")
    public KnowledgeBaseStats stats() {
        return indexService.stats();
    }

    /**
     * 把「整批一个分类」和「逐份指定」两种传法归一成一份与文件一一对应的列表。
     *
     * <p>归一放在循环之前，是因为这个判断在循环里会变成三个嵌套的三元表达式——
     * 而它恰恰是最容易写错、又最不容易看出来的地方（第 i 份用了谁的分类，错了只会
     * 让某几份文档归错类，索引照建、不报任何错）。
     */
    private static List<String> resolveClassifications(int fileCount,
                                                       String classification,
                                                       List<String> classifications) {
        // 没给逐个指定的列表，或者只给了一个：整批都用它。
        // 「只给一个」是目录导入最常见的情形，是合法传法而不是调用方写错了
        if (classifications == null || classifications.isEmpty()) {
            return Collections.nCopies(fileCount, classification);
        }
        if (classifications.size() == 1) {
            return Collections.nCopies(fileCount, classifications.get(0));
        }
        if (classifications.size() == fileCount) {
            return classifications;
        }
        // 其余数量对不上就是调用方的 bug。与其猜它想怎么配，不如直接报出来
        throw ApiException.unsupportedDocument("error.kb.classificationCountMismatch", classifications.size(), fileCount);
    }

    /**
     * 单份上传的公共部分：体积校验 + 建索引。
     *
     * <p>抽出来是因为 {@link #upload} 和 {@link #uploadBatch} 都要做这两件事，
     * 而体积上限那条容易被漏掉——批量那条要是绕过了它，就成了「单传会被拦、
     * 批量却能塞进去」的漏洞。
     */
    private DocumentIndexResult uploadOne(MultipartFile file, String classification) {
        if (file == null || file.isEmpty()) {
            throw ApiException.unsupportedDocument("error.kb.emptyFile");
        }
        long maxFileSize = pgVectorProperties.getMaxFileSize();
        if (file.getSize() > maxFileSize) {
            throw ApiException.unsupportedDocument("error.kb.fileTooLarge", file.getSize(), maxFileSize, maxFileSize / 1024);
        }
        return indexService.index(file.getOriginalFilename(), file.getResource(), classification);
    }
}
