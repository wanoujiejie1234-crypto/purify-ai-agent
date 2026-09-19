package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.config.PgVectorProperties;
import com.purify.purifyaiagent.exception.UnsupportedDocumentException;
import com.purify.purifyaiagent.model.DocumentIndexResult;
import com.purify.purifyaiagent.model.KnowledgeBaseStats;
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
 * <p><b>只在 {@code purify.rag.store=pgvector} 时存在</b>：走百炼云知识库时，
 * 文档是在百炼控制台里上传的，本地根本没有可写的向量库，这几个接口留着只会让人误以为能用。
 * 条件与 {@code PgVectorRagConfig} 保持一致，所以「Bean 在不在」和「接口在不在」
 * 永远是一致的。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@ConditionalOnProperty(prefix = "purify.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "purify.rag", name = "store", havingValue = "pgvector")
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
        if (file == null || file.isEmpty()) {
            throw new UnsupportedDocumentException("请上传一个非空的文件");
        }

        // 全局的 spring.servlet.multipart.max-file-size 是 10MB，那个值是按手机拍的照片定的；
        // 文本文件 1MB 已经约 50 万字，超了基本是传错了东西，在这里按实际大小先拦一道
        long maxFileSize = pgVectorProperties.getMaxFileSize();
        if (file.getSize() > maxFileSize) {
            throw new UnsupportedDocumentException(
                    "文件 " + file.getSize() + " 字节，超过上限 " + maxFileSize + " 字节（"
                            + (maxFileSize / 1024) + "KB）。请拆分后分几次上传。");
        }

        String filename = file.getOriginalFilename();
        log.info("[知识库] 收到上传：filename={} size={}B classification={}",
                filename, file.getSize(), classification);

        return indexService.index(filename, file.getResource(), classification);
    }

    /** 按来源删除该文档的全部切片。删不到不报错。 */
    @DeleteMapping("/documents")
    public void delete(@RequestParam String source) {
        log.info("[知识库] 删除来源：{}", source);
        indexService.deleteBySource(source);
    }

    /** 当前索引规模：切片总数、文档份数，以及按来源/分类的分组计数。 */
    @GetMapping("/stats")
    public KnowledgeBaseStats stats() {
        return indexService.stats();
    }
}
