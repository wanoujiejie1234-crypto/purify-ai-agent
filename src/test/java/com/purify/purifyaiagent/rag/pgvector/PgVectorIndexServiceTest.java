package com.purify.purifyaiagent.rag.pgvector;

import com.purify.purifyaiagent.config.PgVectorProperties;
import com.purify.purifyaiagent.config.RagProperties;
import com.purify.purifyaiagent.exception.DocumentIndexException;
import com.purify.purifyaiagent.exception.UnsupportedDocumentException;
import com.purify.purifyaiagent.model.DocumentIndexResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 索引服务的单元测试。不连库、不调模型。
 *
 * <p>假向量库把 {@code delete} / {@code add} 按调用顺序记下来，用来锁住两件关键行为：
 * <ul>
 *   <li><b>重复上传先删后写</b>——顺序反了就会变成「越传越多」；</li>
 *   <li><b>元数据在读取之后统一覆盖</b>——{@code TextReader} 自己也会往 metadata 里写一个
 *       {@code source}（值是资源的描述串），不覆盖的话写入时是 A、删除时按 B 过滤，
 *       结果是删 0 行、不报错、每次上传都多一份切片。</li>
 * </ul>
 *
 * <p>{@link PgVectorIndexService#stats()} 是类里唯一真连库的方法，这个测试不覆盖它，
 * 所以构造时传的是返回固定条数的假 JdbcTemplate（见 {@link StubJdbcTemplate}）。
 */
class PgVectorIndexServiceTest {

    private static final String CLASSIFICATION = "食物热量";

    /** 按调用顺序记录 delete / add 的假向量库。 */
    private static final class RecordingVectorStore implements org.springframework.ai.vectorstore.VectorStore {

        private final List<String> calls = new ArrayList<>();

        private List<Document> lastAdded = List.of();

        private int deletedByFilterCount;

        @Override
        public void add(List<Document> documents) {
            calls.add("add");
            this.lastAdded = documents;
        }

        @Override
        public void delete(List<String> idList) {
            calls.add("deleteByIds");
        }

        @Override
        public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {
            calls.add("delete");
            deletedByFilterCount++;
        }

        @Override
        public List<Document> similaritySearch(org.springframework.ai.vectorstore.SearchRequest request) {
            throw new UnsupportedOperationException("索引服务不该调用检索");
        }
    }

    /** 只回答「这个来源有多少切片」的假 JdbcTemplate，用来跳过索引后的自检。 */
    private static final class StubJdbcTemplate extends JdbcTemplate {

        private final long count;

        StubJdbcTemplate(long count) {
            this.count = count;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, @Nullable Object... args) {
            return requiredType.cast(this.count);
        }
    }

    /** 带文件名的内存资源，模拟上传时的 {@code MultipartFile.getResource()}。 */
    private static Resource resource(String filename, String content) {
        return resource(filename, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Resource resource(String filename, byte[] content) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static RagProperties ragProperties() {
        RagProperties properties = new RagProperties();
        properties.getRouter().setFilterKey("classification");

        RagProperties.Category category = new RagProperties.Category();
        category.setValue(CLASSIFICATION);
        properties.getRouter().setCategories(List.of(category));
        return properties;
    }

    private static PgVectorProperties pgVectorProperties() {
        PgVectorProperties properties = new PgVectorProperties();
        properties.setSchemaName("public");
        properties.setTableName("rag_knowledge_chunk");
        return properties;
    }

    private static PgVectorIndexService service(RecordingVectorStore vectorStore, long selfCheckCount) {
        return new PgVectorIndexService(vectorStore, new StubJdbcTemplate(selfCheckCount),
                pgVectorProperties(), ragProperties());
    }

    private static final String TEXT_BODY = """
            减脂期每天应该吃多少蛋白质？一般建议每公斤体重摄入 1.2 到 1.6 克。
            以 60 公斤的人为例，每天大约需要 72 到 96 克蛋白质。
            鸡胸肉每 100 克约含 24 克蛋白质，鸡蛋每个约 6 克，牛奶每 100 毫升约 3 克。
            把这些分散到三餐里，比集中在一顿更容易吸收，也更容易坚持。
            """;

    private static final String MARKDOWN_BODY = """
            # 减脂期饮食

            ## 蛋白质怎么吃

            每公斤体重 1.2 到 1.6 克，分到三餐里。

            ## 主食怎么选

            优先粗粮，控制总量比控制种类更重要。
            """;

    @Test
    @DisplayName("txt：切片带上来源、文档名、分类、序号，标题用文件名兜底")
    void index_txt_setsMetadata() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        PgVectorIndexService service = service(vectorStore, 1);

        service.index("减脂食谱.txt", resource("减脂食谱.txt", TEXT_BODY), CLASSIFICATION);
        List<Document> chunks = indexedChunks(vectorStore);

        assertFalse(chunks.isEmpty());
        for (Document chunk : chunks) {
            assertEquals("减脂食谱.txt", chunk.getMetadata().get(PgVectorIndexService.META_SOURCE));
            assertEquals("减脂食谱.txt", chunk.getMetadata().get(PgVectorIndexService.META_DOC_NAME));
            assertEquals(CLASSIFICATION, chunk.getMetadata().get("classification"),
                    "分类字段是检索时过滤的依据，缺了就会永远查不到");
            assertEquals("减脂食谱", chunk.getMetadata().get(PgVectorIndexService.META_TITLE),
                    "txt 没有标题，应当用去掉后缀的文件名兜底，而不是留 null");
        }
        assertEquals(Integer.valueOf(0), chunks.get(0).getMetadata().get(PgVectorIndexService.META_CHUNK_INDEX));
    }

    @Test
    @DisplayName("md：标题取的是最近的小节标题，不会被文件名兜底覆盖")
    void index_markdown_keepsSectionTitle() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        PgVectorIndexService service = service(vectorStore, 1);

        service.index("饮食指南.md", resource("饮食指南.md", MARKDOWN_BODY), CLASSIFICATION);
        List<Document> chunks = indexedChunks(vectorStore);

        // 注意这里断言的是「小节标题」而不是一级标题「减脂期饮食」：
        // MarkdownDocumentReader 给每份小节文档打的 title 是**最近一个**标题，
        // 而一级标题本身不产出正文（它只设置 title），紧接着就被下一个小节标题覆盖了。
        // 这个行为对我们是有利的——【文档名】那栏已经有文件名，【标题】用小节名信息量更大。
        assertTrue(chunks.stream().anyMatch(chunk -> "蛋白质怎么吃".equals(chunk.getMetadata().get(PgVectorIndexService.META_TITLE))),
                "md 的标题应当来自正文标题；锁住这个行为，免得将来升级 Reader 后悄悄变掉");
        assertTrue(chunks.stream().noneMatch(chunk -> "饮食指南".equals(chunk.getMetadata().get(PgVectorIndexService.META_TITLE))),
                "没有任何一片该退化成文件名兜底——出现说明 Reader 没标上标题");
    }

    @Test
    @DisplayName("切片序号从 0 连续递增")
    void index_assignsSequentialChunkIndex() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        PgVectorIndexService service = service(vectorStore, 1);

        service.index("长文.md", resource("长文.md", MARKDOWN_BODY.repeat(20)), CLASSIFICATION);
        List<Document> chunks = indexedChunks(vectorStore);

        assertTrue(chunks.size() > 1, "长文应当切出多片，否则这条用例没验到序号");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(Integer.valueOf(i), chunks.get(i).getMetadata().get(PgVectorIndexService.META_CHUNK_INDEX));
        }
    }

    @Test
    @DisplayName("重复上传同一份文档：先按来源删干净，再写新的")
    void index_sameSource_deletesBeforeAdding() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        PgVectorIndexService service = service(vectorStore, 1);

        service.index("减脂食谱.txt", resource("减脂食谱.txt", TEXT_BODY), CLASSIFICATION);

        assertEquals(List.of("delete", "add"), vectorStore.calls,
                "顺序反了就会变成「越传越多」：先 add 再 delete 会把刚写进去的也删掉，"
                        + "不 delete 则每次上传都多一份切片");
        assertEquals(1, vectorStore.deletedByFilterCount);
    }

    @Test
    @DisplayName("返回值能给调用方一个明确交代")
    void index_returnsResult() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        PgVectorIndexService service = service(vectorStore, 1);

        DocumentIndexResult result = service.index("减脂食谱.txt",
                resource("减脂食谱.txt", TEXT_BODY), CLASSIFICATION);

        assertEquals("减脂食谱.txt", result.source());
        assertEquals(CLASSIFICATION, result.classification());
        assertEquals(vectorStore.lastAdded.size(), result.chunkCount());
        assertTrue(result.characterCount() > 0);
    }

    @Test
    @DisplayName("来源名前后有空格时归一化，保证写入和删除用的是同一个值")
    void index_trimsSource() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        PgVectorIndexService service = service(vectorStore, 1);

        DocumentIndexResult result = service.index("  减脂食谱.txt  ",
                resource("  减脂食谱.txt  ", TEXT_BODY), CLASSIFICATION);

        assertEquals("减脂食谱.txt", result.source());
    }

    @Test
    @DisplayName("不支持的后缀直接拒绝，并说清楚支持什么")
    void index_unsupportedExtension() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        PgVectorIndexService service = service(vectorStore, 1);

        UnsupportedDocumentException exception = assertThrows(UnsupportedDocumentException.class,
                () -> service.index("体检报告.pdf", resource("体检报告.pdf", TEXT_BODY), CLASSIFICATION));

        assertTrue(exception.getMessage().contains("pdf"), "报错要带上实际后缀，方便定位");
        assertTrue(vectorStore.calls.isEmpty(), "拒绝了就不该碰向量库");
    }

    @Test
    @DisplayName("分类不在配置表里直接拒绝：这种值写进去会让过滤永远查不到东西且不报错")
    void index_unknownClassification() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        PgVectorIndexService service = service(vectorStore, 1);

        assertThrows(UnsupportedDocumentException.class,
                () -> service.index("减脂食谱.txt", resource("减脂食谱.txt", TEXT_BODY), "随便写的分类"));
        assertThrows(UnsupportedDocumentException.class,
                () -> service.index("减脂食谱.txt", resource("减脂食谱.txt", TEXT_BODY), "  "));
    }

    @Test
    @DisplayName("内容为空直接拒绝，不往向量库里写垃圾")
    void index_blankContent() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        PgVectorIndexService service = service(vectorStore, 1);

        assertThrows(DocumentIndexException.class,
                () -> service.index("空文件.txt", resource("空文件.txt", "   \n\n  "), CLASSIFICATION));
        assertTrue(vectorStore.calls.isEmpty());
    }

    @Test
    @DisplayName("非 UTF-8 的文档被识别出来，而不是索引成一锅乱码")
    void index_nonUtf8Content() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        PgVectorIndexService service = service(vectorStore, 1);

        // 用 GBK 编码模拟 Windows 记事本存出来的文件：按 UTF-8 读会整篇变成替换字符。
        // 不拦的话检索时只会觉得「怎么什么都查不到」，很难联想到编码
        byte[] gbk = ("减脂期每天应该吃多少蛋白质？一般建议每公斤体重摄入 1.2 到 1.6 克。"
                + "鸡胸肉每 100 克约含 24 克蛋白质，鸡蛋每个约 6 克。").getBytes(Charset.forName("GBK"));

        DocumentIndexException exception = assertThrows(DocumentIndexException.class,
                () -> service.index("乱码.txt", resource("乱码.txt", gbk), CLASSIFICATION));

        assertTrue(exception.getMessage().contains("UTF-8"));
        assertTrue(vectorStore.calls.isEmpty());
    }

    @Test
    @DisplayName("重新索引后的自检对不上时只警告，不影响返回值")
    void index_selfCheckMismatchDoesNotFail() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        // 自检返回一个和实际切片数不同的值，模拟「库里还有残留」
        PgVectorIndexService service = service(vectorStore, 999);

        DocumentIndexResult result = service.index("减脂食谱.txt",
                resource("减脂食谱.txt", TEXT_BODY), CLASSIFICATION);

        assertTrue(result.chunkCount() > 0, "自检只是告警，返回值仍是本次真正写入的切片数");
    }

    @Test
    @DisplayName("按来源删除走的是同一个元数据字段")
    void deleteBySource() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        PgVectorIndexService service = service(vectorStore, 0);

        service.deleteBySource(" 减脂食谱.txt ");

        assertEquals(List.of("delete"), vectorStore.calls);
    }

    /** 从假向量库把这次真正写进去的切片取出来。 */
    private static List<Document> indexedChunks(RecordingVectorStore vectorStore) {
        assertTrue(vectorStore.calls.contains("add"), "索引成功时应当调用过 add");
        assertFalse(vectorStore.lastAdded.isEmpty(), "add 的切片不该是空的");
        return vectorStore.lastAdded;
    }
}
