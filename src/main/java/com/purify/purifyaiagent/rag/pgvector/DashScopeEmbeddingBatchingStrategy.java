package com.purify.purifyaiagent.rag.pgvector;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

/**
 * 同时受「条数」和「token 数」两个上限约束的分批策略。
 *
 * <p><b>为什么默认策略不够用</b>：{@code AbstractVectorStoreBuilder} 默认给的是
 * {@code TokenCountBatchingStrategy}，它<b>只按 token 切</b>——每批上限 8191 token、
 * 再预留 10%，也就是约 7400 token。而一份几百字的切片才二十来个 token，
 * 于是一个批里能塞进三百多条。偏偏 DashScope 的 Embedding 接口在客户端就有
 * {@code Assert.isTrue(texts.size() <= 25)} 的硬断言（服务端对 v3/v4 更严，只允许 10 条），
 * 结果是：三十几条的文档一切正常，稍微大一点的文档就在上传时报
 * {@code The input texts limit 25.}——一个看起来和「文档太大」毫无关系的错误。
 *
 * <p>值得强调的是 {@code PgVectorStore} 的 {@code maxDocumentBatchSize} <b>解决不了这个问题</b>：
 * 那个参数只影响写库时 JDBC {@code batchUpdate} 的分片，跟 embedding 请求怎么组装无关。
 *
 * <p><b>切分顺序不能反</b>：先按条数切、每一段再交给 token 策略。
 * 反过来的话 token 策略会先产出一个三百条的批，再按条数切就已经晚了
 * （而且会在同一次分批里产生两次切分，边界含义变得难解释）。
 */
public class DashScopeEmbeddingBatchingStrategy implements BatchingStrategy {

    /**
     * DashScope 客户端的硬上限，见 {@code DashScopeApi#embeddings} 里的断言。
     * 超过它的配置不是「效果差一点」而是必然抛异常，所以在构造时就挡掉。
     */
    private static final int CLIENT_SIDE_LIMIT = 25;

    /** 单次请求最多几条切片。 */
    private final int maxDocumentsPerRequest;

    /** 按 token 切的策略，负责每段内部再分一次。 */
    private final BatchingStrategy tokenStrategy;

    public DashScopeEmbeddingBatchingStrategy(int maxDocumentsPerRequest, BatchingStrategy tokenStrategy) {
        Assert.isTrue(maxDocumentsPerRequest > 0, "maxDocumentsPerRequest 必须大于 0");
        // 在这里挡掉而不是等接口报错：DashScope 客户端那句 "The input texts limit 25."
        // 不会告诉你是哪个配置写错了
        Assert.isTrue(maxDocumentsPerRequest <= CLIENT_SIDE_LIMIT,
                "单次 Embedding 请求的条数上限是 " + CLIENT_SIDE_LIMIT + "，当前配成了 " + maxDocumentsPerRequest);
        Assert.notNull(tokenStrategy, "tokenStrategy 不能为 null");

        this.maxDocumentsPerRequest = maxDocumentsPerRequest;
        this.tokenStrategy = tokenStrategy;
    }

    @Override
    public List<List<Document>> batch(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        return partition(documents, maxDocumentsPerRequest).stream()
                .flatMap(segment -> tokenStrategy.batch(segment).stream())
                .toList();
    }

    /** 按固定条数切段，保持原有顺序。 */
    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(List.copyOf(list.subList(i, Math.min(i + size, list.size()))));
        }
        return partitions;
    }
}
