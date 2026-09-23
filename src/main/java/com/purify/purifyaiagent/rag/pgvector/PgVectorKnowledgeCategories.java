package com.purify.purifyaiagent.rag.pgvector;

import com.purify.purifyaiagent.config.PgVectorProperties;
import com.purify.purifyaiagent.config.RagProperties;
import com.purify.purifyaiagent.rag.KnowledgeCategories;
import com.purify.purifyaiagent.rag.StaticKnowledgeCategories;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地向量库的分类目录：<b>yml 里内置的那几项 ∪ 库里已经用过的值</b>。
 *
 * <p>这一层是「自定义类型」这个功能能不能真正用起来的关键。只放开上传校验的话，
 * 自定义类型确实能入库、也能在文档列表里看到，但检索那边有三道门都只认 yml 里那几项：
 * <ol>
 *   <li>{@code KnowledgeRouter} 的关键词表里没有它 → 没有任何提问会被判成「该查这一类」，
 *       于是带过滤的那次检索永远不会带上它（未命中任何分类时是<b>跳过检索</b>，
 *       见 {@code query-all-when-unmatched} 的默认值 false）；</li>
 *   <li>{@code ClassificationFilter} 认为它是未知分类 → 退回全库检索；</li>
 *   <li>{@code RoutingDocumentRetriever} 找不到对应的检索器 → 同样退回全库。</li>
 * </ol>
 * 后两道「退回全库」是安全的方向，但第一道不是：<b>文档静静地躺在那儿，
 * 一辈子不会被检索到，而且不报任何错</b>。所以这里的做法是让「库里用过的值」
 * 也成为已知分类，并拿类型名本身当它的关键词。
 *
 * <h2>为什么从库里发现，而不是再建一张类型注册表</h2>
 *
 * <p>「有哪些分类」这件事实在是<b>已经在切片元数据里了</b>——每片都带着
 * {@code classification}。再建一张表就要面对「注册表和实际数据不一致」这个新问题
 * （表里有、库里一份文档都没有；或者反过来），而那种不一致的表现同样是
 * 「下拉框里有的分类查不到东西」。查 DISTINCT 不会不一致，也不需要新增建表脚本、
 * 不需要给「删掉最后一个文档之后这个类型还在不在」定规矩：
 * 库里没有这个值了，它自然就不在下拉框里。
 *
 * <h2>代价：检索热路径上多一次（有缓存的）查询</h2>
 *
 * <p>路由每轮对话都要问一次「有哪些分类」。这个查询走的是切片表的
 * {@code metadata->>'classification'}，表大了是顺序扫描，所以结果缓存在
 * {@link RagProperties.Router#getDiscoverCacheSeconds()} 秒内复用，
 * 写入和删除之后由 {@code PgVectorIndexService} 显式 {@link #refresh()}，
 * 让「刚传上去的类型」立刻可用。
 *
 * <p><b>这个查询失败绝不能让对话挂掉</b>：拿不到动态那部分就退回内置那几项
 * （也就是这个功能加进来之前的行为），只打一条 WARN。数据库连不上时
 * 检索本来也会失败，但失败点应该是检索本身，而不是「路由都算不出来」。
 */
@Slf4j
public class PgVectorKnowledgeCategories implements KnowledgeCategories {

    /** 内置分类（yml 那份）。合并结果里它们永远排在最前面。 */
    private final StaticKnowledgeCategories builtin;

    private final JdbcTemplate pgJdbcTemplate;

    /** 发现查询的 SQL，构造时拼好——表名和字段名都是配置，不会在运行期变。 */
    private final String discoverSql;

    private final long cacheMillis;

    /** 关掉发现时只回内置那份，等价于这个功能没加进来。 */
    private final boolean discoverEnabled;

    /**
     * 当前快照。整个对象是不可变的，所以读到一半被换掉也不会看到「all 和 values 不是一套」。
     *
     * <p>{@code volatile} 是必须的：写发生在任意请求线程上，读发生在任意别的请求线程上。
     */
    private volatile Snapshot snapshot;

    /**
     * 已经报过「这个值不安全、我丢掉了」的那些值。
     *
     * <p>去重是为了不打日志洪水：快照每 {@code cacheMillis} 重算一次，
     * 一个脏值会在每次重算时被重新看见，一条一条打下去能把日志刷满。
     */
    private final Set<String> reportedUnsafe = ConcurrentHashMap.newKeySet();

    public PgVectorKnowledgeCategories(RagProperties ragProperties,
                                       PgVectorProperties pgVectorProperties,
                                       JdbcTemplate pgJdbcTemplate) {
        this.builtin = new StaticKnowledgeCategories(ragProperties);
        this.pgJdbcTemplate = pgJdbcTemplate;
        this.discoverEnabled = ragProperties.getRouter().isDiscoverFromStore();
        // 下限 0：配成 0 或负数时按「每次都重新发现」处理，而不是负数的 TTL 让缓存永远命中
        this.cacheMillis = Math.max(ragProperties.getRouter().getDiscoverCacheSeconds(), 0) * 1000L;

        // 表名和字段名要拼进 SQL，走同一个标识符防线（和写入端、关键词那一路是同一份）。
        // 放在构造期而不是查询期：配置写错应当即刻失败，而不是在某一轮对话里变成
        // 一句看不懂的语法错误
        String table = PgVectorSql.qualifiedTableName(pgVectorProperties);
        String key = PgVectorSql.safeMetadataKey(ragProperties.getRouter().getFilterKey(),
                "purify.rag.router.filter-key");
        // LIMIT 挡一道：这一列上不一定有索引，一张大表上的 DISTINCT 会把所有值捞进内存。
        // 分类是个位数到几十的量级，取 500 已经远超实际需要
        this.discoverSql = "SELECT DISTINCT metadata->>'" + key + "' AS classification FROM " + table
                + " WHERE metadata->>'" + key + "' IS NOT NULL ORDER BY 1 LIMIT 500";
    }

    @Override
    public List<RagProperties.Category> all() {
        return snapshot().all();
    }

    @Override
    public List<String> values() {
        return snapshot().values();
    }

    @Override
    public boolean isKnown(String value) {
        return StringUtils.hasText(value) && snapshot().values().contains(value);
    }

    /**
     * 丢掉缓存，下次读的时候重新发现。
     *
     * <p>入库和删除之后调。没有它的话，用户「传完立刻去问」会最多
     * {@code discover-cache-seconds} 秒内问不到刚传的那一类——
     * 而那正是最容易被当成 bug 报上来的时刻。
     */
    @Override
    public void refresh() {
        snapshot = null;
    }

    /** 拿快照；过期或没有就重算一次。 */
    private Snapshot snapshot() {
        Snapshot current = snapshot;
        long now = System.currentTimeMillis();
        if (current != null && now - current.loadedAt() < cacheMillis) {
            return current;
        }
        return reload(now);
    }

    /**
     * 重算快照。
     *
     * <p>{@code synchronized} + 进锁后再判一次（双检）：并发请求同时发现缓存过期时，
     * 只有第一个真的去查库，其余几个等它算完直接复用。不这么做的话，
     * 一次缓存过期会同时打出好几条一模一样的 DISTINCT 查询。
     */
    private synchronized Snapshot reload(long now) {
        Snapshot current = snapshot;
        if (current != null && now - current.loadedAt() < cacheMillis) {
            return current;
        }
        Snapshot fresh = build(now);
        snapshot = fresh;
        return fresh;
    }

    private Snapshot build(long now) {
        List<RagProperties.Category> all = new ArrayList<>(builtin.all());
        List<String> values = new ArrayList<>(builtin.values());
        Set<String> seen = new LinkedHashSet<>(values);

        for (String discovered : discover()) {
            if (!seen.add(discovered)) {
                // 和内置分类同名：以内置那份为准，它带着正经的关键词表
                continue;
            }
            all.add(categoryOf(discovered));
            values.add(discovered);
        }

        return new Snapshot(now, List.copyOf(all), List.copyOf(values));
    }

    /**
     * 库里已经用过的分类值。
     *
     * <p><b>失败一律退回空列表</b>，理由见类注释：让路由少几个分类，
     * 比让整轮对话因为一个「有哪些分类」的查询而 500 要好得多。
     */
    private List<String> discover() {
        if (!discoverEnabled) {
            return List.of();
        }
        try {
            List<String> found = pgJdbcTemplate.queryForList(discoverSql, String.class);
            List<String> usable = new ArrayList<>(found.size());
            for (String raw : found) {
                if (!StringUtils.hasText(raw)) {
                    continue;
                }
                String value = raw.trim();
                if (PgVectorSql.isSafeMetadataValue(value)) {
                    usable.add(value);
                }
                else if (reportedUnsafe.add(value)) {
                    // 库里有个不安全的分类值。写入端已经拦住了新的，
                    // 剩下的只可能是更早的版本写进去的、或者人手改库改出来的。
                    // 丢掉它而不是拿去拼检索条件——那个条件拼出来不报错，
                    // 只会静默地查不到东西
                    log.warn("[pgvector] 库里的分类「{}」含不允许的字符，不纳入路由与过滤。"
                            + "它下面的切片在带分类过滤的提问里检索不到，建议改名后重传", value);
                }
            }
            return usable;
        }
        catch (DataAccessException exception) {
            log.warn("[pgvector] 读取已有分类失败，本次只用内置分类（{}）：{}",
                    builtin.values(), exception.getMessage());
            return List.of();
        }
    }

    /**
     * 把一个发现的分类值包装成分类：<b>关键词就是它自己的名字</b>。
     *
     * <p>用户只填了一个类型名，没有地方填关键词。而路由是纯字符串包含匹配，
     * 所以「类型名出现在提问里」是唯一能自动成立的命中条件。
     * 这意味着「面膜多久敷一次」这种不含类型名的提问不会命中「护肤」这一类——
     * 这是明说过的取舍，不是缺陷；要让这类提问也命中，把类型名起得更贴近
     * 提问里的说法（或者把关键词补进 {@code purify.rag.router.categories}）。
     */
    private static RagProperties.Category categoryOf(String value) {
        RagProperties.Category category = new RagProperties.Category();
        category.setValue(value);
        category.setKeywords(List.of(value));
        return category;
    }

    /** 一次发现的结果。三个字段必须成套更换，所以打成一个不可变对象。 */
    private record Snapshot(long loadedAt, List<RagProperties.Category> all, List<String> values) {
    }
}
