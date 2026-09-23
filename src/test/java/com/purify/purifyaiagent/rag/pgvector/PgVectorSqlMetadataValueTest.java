package com.purify.purifyaiagent.rag.pgvector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 元数据取值的字符规则。
 *
 * <h2>为什么这条规则值得一个单独的测试</h2>
 *
 * <p>分类值是这个项目里<b>唯一一处「用户输入会被拼进 SQL 字符串」</b>的地方：
 * Spring AI 的 {@code PgVectorFilterExpressionConverter} 把值用
 * {@code String.format("\"%s\"", value)} 包一下就塞进
 * {@code metadata::jsonb @@ '…'::jsonpath}（不过滤、不转义，见
 * {@link PgVectorSql#isSafeMetadataValue} 的注释）。
 * 「其他…」这个功能一上线，它就从「yml 里那几个固定值」变成了真正的用户输入。
 *
 * <p>所以这里钉住的是两件事：几个能逃出字符串的字符必须被拒（安全），
 * 以及内置分类名和常见中文类型名必须被收（否则功能直接不可用）。
 * 这两条都属于「坏了也能编译、也能跑，只是要么有洞、要么静默查不到东西」，
 * 正适合用测试钉住。
 */
class PgVectorSqlMetadataValueTest {

    @Test
    void 内置分类名必须收下() {
        assertThat(PgVectorSql.isSafeMetadataValue("食物热量")).isTrue();
        assertThat(PgVectorSql.isSafeMetadataValue("运动热量")).isTrue();
        assertThat(PgVectorSql.isSafeMetadataValue("药物")).isTrue();
    }

    @Test
    void 自建类型名要能收下中英文数字和那几个标点() {
        assertThat(PgVectorSql.isSafeMetadataValue("护肤")).isTrue();
        assertThat(PgVectorSql.isSafeMetadataValue("sleep")).isTrue();
        assertThat(PgVectorSql.isSafeMetadataValue("睡眠 管理")).isTrue();
        assertThat(PgVectorSql.isSafeMetadataValue("睡眠-管理_v2.1")).isTrue();
        assertThat(PgVectorSql.isSafeMetadataValue("A1")).isTrue();
    }

    /**
     * 这四个是「能逃出引号」的那一类，单独列出来是因为它们各自对应一条真实的逃逸路径：
     * {@code '} 逃出 SQL 的单引号字面量，{@code "} 和 {@code \} 逃出 jsonpath 的字符串字面量，
     * {@code %} 是 LIKE 通配符（关键词那一路用得上，虽然那里走的是参数）。
     */
    @Test
    void 能逃出引号的字符必须拒掉() {
        assertThat(PgVectorSql.isSafeMetadataValue("a'b")).isFalse();
        assertThat(PgVectorSql.isSafeMetadataValue("a\"b")).isFalse();
        assertThat(PgVectorSql.isSafeMetadataValue("a\\b")).isFalse();
        assertThat(PgVectorSql.isSafeMetadataValue("a%b")).isFalse();
    }

    @Test
    void 几种典型的注入形状必须拒掉() {
        assertThat(PgVectorSql.isSafeMetadataValue("x' OR '1'='1")).isFalse();
        assertThat(PgVectorSql.isSafeMetadataValue("x'::jsonpath = 'x")).isFalse();
        assertThat(PgVectorSql.isSafeMetadataValue("食物热量';DROP TABLE rag_knowledge_chunk;--")).isFalse();
    }

    @Test
    void 换行和控制字符必须拒掉() {
        assertThat(PgVectorSql.isSafeMetadataValue("a\nb")).isFalse();
        assertThat(PgVectorSql.isSafeMetadataValue("a\tb")).isFalse();
        assertThat(PgVectorSql.isSafeMetadataValue("a\u0000b")).isFalse();
    }

    @Test
    void 长度上限是二十个字符() {
        String atLimit = "十".repeat(PgVectorSql.MAX_METADATA_VALUE_LENGTH);
        String overLimit = "十".repeat(PgVectorSql.MAX_METADATA_VALUE_LENGTH + 1);

        assertThat(PgVectorSql.isSafeMetadataValue(atLimit)).isTrue();
        assertThat(PgVectorSql.isSafeMetadataValue(overLimit)).isFalse();
    }

    @Test
    void 空的和纯空白的都不算合法值() {
        assertThat(PgVectorSql.isSafeMetadataValue(null)).isFalse();
        assertThat(PgVectorSql.isSafeMetadataValue("")).isFalse();
        assertThat(PgVectorSql.isSafeMetadataValue("   ")).isFalse();
    }
}
