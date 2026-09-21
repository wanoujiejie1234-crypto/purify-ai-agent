package com.purify.purifyaiagent.rag.pgvector;

import com.purify.purifyaiagent.config.PgVectorProperties;

import java.util.Collections;

/**
 * pgvector 表上拼 SQL 的那几处公共防线。
 *
 * <p><b>为什么要有这个类</b>：这套防线原本是 {@link PgVectorIndexService} 的私有方法。
 * 加了关键词检索那一路之后，同样几条规矩要在两处生效——表名由配置拼出、
 * 元数据字段名由配置拼出、值一律走参数。抄第二份的话，两份迟早会不一样，
 * 而漂移的表现是「有一路能防住注入、另一路防不住」，看代码看不出来。
 *
 * <p><b>这里只管拼字符串，不碰连接</b>：全是静态方法，没有状态，可以随便单测。
 */
public final class PgVectorSql {

    /**
     * 表名/模式名/字段名的合法字符。
     *
     * <p>这些标识符要直接拼进 SQL（它们没法走占位符），所以先在这里挡一道：
     * 配置写错了应当即刻失败，而不是等到某次查询报一句看不懂的语法错误。
     */
    private static final String SAFE_IDENTIFIER = "^[a-zA-Z0-9_]{1,64}$";

    private PgVectorSql() {
    }

    /**
     * 校验一个要拼进 SQL 的标识符，并原样返回它（方便内联使用）。
     *
     * @param identifier   待校验的标识符
     * @param propertyName 出错时要报出来的配置项名——报「schema-name 不合法」
     *                     比报「某个标识符不合法」有用得多
     */
    public static String assertSafeIdentifier(String identifier, String propertyName) {
        if (identifier == null || !identifier.matches(SAFE_IDENTIFIER)) {
            throw new IllegalStateException(
                    propertyName + " 只能是字母、数字、下划线，且不超过 64 个字符，当前是：" + identifier);
        }
        return identifier;
    }

    /**
     * 元数据字段名要拼进 SQL，和表名一样先挡一道。
     *
     * <p>取值来自配置（{@code router.filter-key}），是开发者自己写的、不是外部输入，
     * 所以这不是一个真实的注入面。但拼 SQL 的地方各自裸拼字符串迟早会出事——
     * 表名那边已经立了「配置写错应当即刻失败」的规矩，这里沿用同一条。
     * 顺带还有个好处：字段名写错（比如多加了个引号）会在第一次查询时就报出来，
     * 而不是变成一句语法错误让人猜是哪里拼坏的。
     */
    public static String safeMetadataKey(String metadataKey, String propertyName) {
        return assertSafeIdentifier(metadataKey, propertyName);
    }

    /** {@code schema.table}，两段都过一遍标识符校验。 */
    public static String qualifiedTableName(PgVectorProperties properties) {
        return assertSafeIdentifier(properties.getSchemaName(), "purify.rag.pgvector.schema-name")
                + "."
                + assertSafeIdentifier(properties.getTableName(), "purify.rag.pgvector.table-name");
    }

    /**
     * 把一个检索词包成 {@code LIKE} 用的模式：转义 + 两端加 {@code %}。
     *
     * <p>转义是防御性的：{@link KeywordTermExtractor} 在归一化阶段已经把非「汉字 /
     * 字母数字」的字符全切掉了，正常走不到这里需要转义的分支。但「包 %」这件事
     * 天生和通配符语义冲突，把转义和包裹放在同一个方法里，将来放宽提取器的
     * 归一化规则时不会漏掉这一边。
     *
     * <p>调用方的 SQL 里要配 {@code ESCAPE '\'}（Java 字符串里写 {@code "ESCAPE '\\'"}）。
     */
    public static String likePattern(String term) {
        String escaped = term.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /**
     * 生成 {@code n} 个逗号分隔的占位符，给 {@code IN (...)} 之类的场景用。
     *
     * <p>单独抽出来是因为「占位符个数」和「参数个数」必须严格相等，
     * 而这两处在代码里隔着好几行——对不上时的报错是运行时才出现的
     * {@code Parameter index out of range}，不在编译期暴露。
     */
    public static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(Math.max(count, 0), "?"));
    }
}
