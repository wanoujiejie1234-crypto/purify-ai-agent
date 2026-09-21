package com.purify.purifyaiagent.rag.pgvector;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）：把几路召回结果合成一个候选集。
 *
 * <p>算法只有一行：{@code score(文档) = Σ 1 / (k + 名次)}——每一路给文档一个分数，
 * 同一份文档在几路里出现就累加几次，最后按总分排序。
 *
 * <h2>为什么用名次而不是分数</h2>
 *
 * <p>向量那一路给的是余弦距离，关键词那一路给的是命中词数，<b>两者根本不可比</b>——
 * 想把它们加权求和，得先造一个「这个分数多重、那个分数多重」的映射，而那个映射
 * 没有理论依据、只能靠试。名次则是天然可比的：两路都排第 3 的文档，就是比
 * 一路第 1、另一路没有的更值得看。
 *
 * <p>代价是<b>丢掉了分数的全部精度</b>。这也是「关键词那一路不用 BM25」的原因之一：
 * BM25 的价值在 IDF 加权和 tf 饱和的<b>打分</b>上，而那份打分会在这里被整个扔掉
 * （见 {@code PgVectorProperties.Keyword} 的注释）。
 *
 * <h2>k 的作用</h2>
 *
 * <p>{@code k} 压平「第一名」和「第十名」的差距。取默认的 60、两路各 20 条时，
 * 分数落在 {@code 1/61 ≈ 0.0164} 到 {@code 1/80 = 0.0125} 这个很窄的区间里，
 * 于是「两路都命中」几乎必然压过「单路第一」（{@code 2/80 = 0.025 > 1/61}）——
 * 这正是 RRF 想要的语义。
 *
 * <p>纯函数，无状态，可以随便单测。
 */
public final class RrfFusion {

    /**
     * 融合分数的元数据键。
     *
     * <p>写进 metadata 是安全的：{@code RagPrompts.DOCUMENT_FORMATTER} 只读
     * {@code index_id / doc_name / title} 和正文，这个键不会泄露给模型。
     * 它服务于日志和 {@code /api/rag/search} 自检接口——「这条为什么排在这儿」
     * 是有价值的排查信息。
     */
    public static final String META_SCORE = "rrf_score";

    /** 命中了几路的元数据键，值是逗号分隔的路名。 */
    public static final String META_ARMS = "rrf_arms";

    /** 每一路各自排第几的元数据键前缀，完整键形如 {@code rrf_rank_vector}。 */
    public static final String META_RANK_PREFIX = "rrf_rank_";

    private RrfFusion() {
    }

    /**
     * 一路召回结果。
     *
     * @param name      路名，会出现在日志和元数据里（{@code vector} / {@code keyword}）
     * @param documents 这一路的结果，<b>顺序即名次</b>（第一个是第 1 名）。
     *                  全部文档必须带 id——去重靠它，见 {@link #fuse}
     */
    public record Arm(String name, List<Document> documents) {
    }

    /**
     * 融合。
     *
     * @param arms  各路的召回结果
     * @param k     平滑常数，见类注释。负值会被夹到 0
     * @param limit 最多返回几条
     * @return 融合后的文档，<b>顺序即最终排名</b>。同一份文档只出现一次，
     *         保留的是<b>先出现那一路</b>的那个 {@code Document} 实例（先传进来的路优先）
     */
    public static List<Document> fuse(List<Arm> arms, int k, int limit) {
        if (arms == null || arms.isEmpty() || limit <= 0) {
            return List.of();
        }
        int safeK = Math.max(k, 0);

        Map<String, Candidate> candidates = new LinkedHashMap<>();
        int arrival = 0;
        for (Arm arm : arms) {
            if (arm == null || arm.documents() == null) {
                continue;
            }
            int rank = 0;
            for (Document document : arm.documents()) {
                if (document == null) {
                    continue;
                }
                // 名次从 1 开始。写成 1/(k+0) 是这一路最容易犯的错——
                // 它会让第一名的权重凭空翻倍，而且结果看起来仍然「正常」
                rank++;

                Candidate candidate = candidates.get(document.getId());
                if (candidate == null) {
                    candidate = new Candidate(document, arrival++);
                    candidates.put(document.getId(), candidate);
                }
                candidate.score += 1.0 / (safeK + rank);
                candidate.ranks.put(arm.name(), rank);
                candidate.bestRank = Math.min(candidate.bestRank, rank);
            }
        }

        List<Candidate> ranked = new ArrayList<>(candidates.values());
        // 三级排序键，缺一不可：分数定大方向，「最好名次」让两路都排第一的压过
        // 一路排第一的，而「首次出现的序号」保证**完全确定性**——
        // 分数相同时若靠 Map 的迭代顺序决定，同样的输入两次调用可能给出不同结果，
        // 而 RRF 的名次是下游重排的输入，不确定的话测试会 flaky
        ranked.sort(Comparator.comparingDouble((Candidate candidate) -> candidate.score).reversed()
                .thenComparingInt(candidate -> candidate.bestRank)
                .thenComparingInt(candidate -> candidate.arrival));

        List<Document> result = new ArrayList<>(Math.min(ranked.size(), limit));
        for (Candidate candidate : ranked) {
            if (result.size() >= limit) {
                break;
            }
            stamp(candidate);
            result.add(candidate.document);
        }
        return List.copyOf(result);
    }

    /** 把融合的结果写进元数据：总分、命中哪几路、每路各自第几。 */
    private static void stamp(Candidate candidate) {
        Map<String, Object> metadata = candidate.document.getMetadata();
        metadata.put(META_SCORE, candidate.score);
        metadata.put(META_ARMS, String.join(",", candidate.ranks.keySet()));
        candidate.ranks.forEach((name, rank) -> metadata.put(META_RANK_PREFIX + name, rank));
    }

    /** 融合过程中的一份文档。可变，只在 {@link #fuse} 内部活着。 */
    private static final class Candidate {

        private final Document document;

        /** 首次出现的序号，用来在分数相同时保证确定性。 */
        private final int arrival;

        /** 每一路给它的名次，插入顺序即路的顺序。 */
        private final Map<String, Integer> ranks = new LinkedHashMap<>();

        private double score;

        /** 在所有路里最好的那个名次。 */
        private int bestRank = Integer.MAX_VALUE;

        private Candidate(Document document, int arrival) {
            this.document = document;
            this.arrival = arrival;
        }
    }
}
