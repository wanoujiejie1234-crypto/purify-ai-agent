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

    /** 元数据取值的长度上限，和 {@link #SAFE_METADATA_VALUE} 里的数字是同一个。 */
    public static final int MAX_METADATA_VALUE_LENGTH = 20;

    /**
     * 元数据<b>取值</b>的合法字符集：中英文（{@code \p{L}} 覆盖汉字）、数字、
     * 空格、下划线、连字符、点。长度上限见 {@link #MAX_METADATA_VALUE_LENGTH}。
     *
     * <p><b>为什么取值也要限制字符，而不是「参数化就够了」</b>：分类值确实有一半走的是
     * 参数（关键词那一路，见 {@code ClassificationFilter#toSqlArgument}），
     * 但另一半<b>不走</b>——向量那一路把值交给 Spring AI 的
     * {@code PgVectorFilterExpressionConverter}，它是这么拼的：
     * <pre>
     *   context.append(String.format("\"%s\"", value));      // 不过滤、不转义
     *   ... "WHERE metadata::jsonb @@ '" + native + "'::jsonpath"
     * </pre>
     * 也就是说值被原样塞进一个 SQL 单引号字符串里的 jsonpath 双引号字符串里。
     * 一个 {@code '} 能从 SQL 字面量里逃出来，一个 {@code "} 或 {@code \} 能从 jsonpath
     * 的字符串里逃出来。**这是本项目里唯一一处「用户输入进 SQL 字符串」的地方**，
     * 所以入口必须收窄成上面这个白名单——分类值曾经只可能来自 yml 里的固定几项，
     * 开放「自定义类型」之后它就真的成了用户输入。
     *
     * <p>提示文案（{@code error.kb.classificationInvalid}）里逐字描述了这条规则。
     * <b>改这个正则时要连那份文案一起改</b>，中英各一条。
     */
    private static final String SAFE_METADATA_VALUE =
            "^[\\p{L}\\p{N} _\\-.]{1," + MAX_METADATA_VALUE_LENGTH + "}$";

    /**
     * 这个值能不能安全地写进 pgvector 的元数据、并（对分类而言）当作等值过滤条件用。
     *
     * <p>写入端（{@code PgVectorIndexService}）和读取端（{@code PgVectorKnowledgeCategories}
     * 从库里发现分类时）都要过这一关，所以规则放在这里一份，两边引用同一个判断——
     * 各写一份的话，漂移的表现是「写入端放行了读取端又过滤掉」，
     * 用户看到的是「刚传的分类过一会儿自己消失了」。
     *
     * <p>顺带把「全是空白」也判成不安全：字符集那一关拦不住它（空格本身合法），
     * 而一个纯空白的分类值当成过滤条件用，等于查一个永远查不到的东西。
     * 写入端本来就会先 trim 再要求非空，这里收紧只是不让这个判断单独用时留个洞。
     */
    public static boolean isSafeMetadataValue(String value) {
        return value != null && !value.isBlank() && value.matches(SAFE_METADATA_VALUE);
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
