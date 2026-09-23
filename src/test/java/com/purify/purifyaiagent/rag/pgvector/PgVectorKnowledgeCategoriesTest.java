package com.purify.purifyaiagent.rag.pgvector;

import com.purify.purifyaiagent.config.PgVectorProperties;
import com.purify.purifyaiagent.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分类目录的合并规则。
 *
 * <p>这一层坏掉的表现是最难查的那一种：<b>文档传得进去、列表里看得见，
 * 就是检索不到，而且什么都不报</b>。所以几条关键行为都在这里钉住——
 * 合并顺序、同名去重、脏值丢掉、以及查库失败时退回内置那一份。
 *
 * <p>JdbcTemplate 是 mock 的，所以这里验证的是「拿到库里的值之后怎么合并」，
 * 不是那条 DISTINCT SQL 本身能不能在 PostgreSQL 上跑（那件事只能实跑一次，
 * 启动日志里那行分类清单就是它的现场证明）。
 */
class PgVectorKnowledgeCategoriesTest {

    private static final String BUILTIN = "食物热量";

    private static RagProperties ragProperties() {
        RagProperties.Category category = new RagProperties.Category();
        category.setValue(BUILTIN);
        category.setKeywords(List.of("吃", "热量"));

        RagProperties properties = new RagProperties();
        properties.getRouter().setCategories(List.of(category));
        properties.getRouter().setDiscoverFromStore(true);
        properties.getRouter().setDiscoverCacheSeconds(30);
        return properties;
    }

    private static PgVectorKnowledgeCategories catalog(JdbcTemplate jdbcTemplate, RagProperties ragProperties) {
        return new PgVectorKnowledgeCategories(ragProperties, new PgVectorProperties(), jdbcTemplate);
    }

    /** 库里用过的值排在内置的后面，而且和内置同名的那一个不重复出现。 */
    @Test
    void 库里的分类接在内置分类后面且同名去重() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("护肤", BUILTIN, "睡眠"));

        assertThat(catalog(jdbcTemplate, ragProperties()).values())
                .containsExactly(BUILTIN, "护肤", "睡眠");
    }

    /**
     * 自建分类的关键词就是它自己的名字。
     *
     * <p>这是「自建类型能不能被检索到」的全部依据：路由是纯字符串包含匹配，
     * 关键词为空的话这个分类永远不会被任何提问命中。
     */
    @Test
    void 自建分类的关键词就是类型名自己() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of("护肤"));

        RagProperties.Category discovered = catalog(jdbcTemplate, ragProperties()).all().stream()
                .filter(category -> "护肤".equals(category.getValue()))
                .findFirst()
                .orElseThrow();

        assertThat(discovered.getKeywords()).containsExactly("护肤");
    }

    /**
     * 库里那个带引号的脏值不能进目录。
     *
     * <p>正常情况下写入端已经拦住了它，但库里可能有更早版本写进去的值。
     * 放进目录的后果不是报错，而是<b>它一旦被路由命中，那次检索的过滤条件就带着引号进 SQL</b>。
     */
    @Test
    void 库里不安全的分类值要被丢掉() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("护肤", "x';DROP TABLE t;--", "  ", "\"q\""));

        assertThat(catalog(jdbcTemplate, ragProperties()).values()).containsExactly(BUILTIN, "护肤");
    }

    /** 缓存期内不重复查库；{@code refresh()} 之后立刻重查。 */
    @Test
    void 缓存生效且refresh之后立刻重查() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of("护肤"));
        PgVectorKnowledgeCategories catalog = catalog(jdbcTemplate, ragProperties());

        assertThat(catalog.values()).contains(BUILTIN);
        assertThat(catalog.values()).contains(BUILTIN);
        verify(jdbcTemplate, times(1)).queryForList(anyString(), eq(String.class));

        catalog.refresh();
        assertThat(catalog.values()).contains(BUILTIN);
        verify(jdbcTemplate, times(2)).queryForList(anyString(), eq(String.class));
    }

    /** 关掉发现时一次库都不查——那就是「这个功能没加进来」的行为。 */
    @Test
    void 关掉发现时只回内置分类且不查库() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RagProperties properties = ragProperties();
        properties.getRouter().setDiscoverFromStore(false);

        assertThat(catalog(jdbcTemplate, properties).values()).containsExactly(BUILTIN);
        verify(jdbcTemplate, never()).queryForList(anyString(), eq(String.class));
    }

    /**
     * 查库失败退回内置那一份，<b>不往上抛</b>。
     *
     * <p>这个查询挂在每一轮对话的路由上，抛出去就等于「数据库抖一下，聊天全挂」。
     * 退回内置分类的表现是「自建类型这一轮命中不了」，比整轮对话 500 好得多。
     */
    @Test
    void 查库失败时退回内置分类而不抛异常() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("连不上"));

        assertThat(catalog(jdbcTemplate, ragProperties()).values()).containsExactly(BUILTIN);
    }
}
