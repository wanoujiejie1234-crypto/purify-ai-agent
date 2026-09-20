package com.purify.purifyaiagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPA 兜底转发的路径规则。
 *
 * <p><b>这个测试存在的唯一理由是一个已经踩过的坑。</b>最初那版规则是
 * {@code /{path:[^\.]*}} 和 {@code /{path:[^\.]*}/**}，本意是「路径里有点就不是前端路由」，
 * 但 {@code [^\.]*} 只约束第一个路径段——{@code /assets/index-abc.js} 的第一段
 * {@code assets} 没有点，点在第二段，于是整条被兜底规则接走、转发成了 index.html。
 * 而视图控制器的优先级高于静态资源处理器，所以构建产物里的 JS 永远轮不到，
 * 浏览器拿到的是 {@code Content-Type: text/html}，整站白屏。
 *
 * <p>那是一个「只在构建后由 Spring Boot 托管时才出现、dev server 完全正常」的故障，
 * 靠手动点是很容易漏掉的，所以在这里钉死：<b>静态资源前缀必须逐个排除</b>。
 *
 * <p>直接用 Spring 自己的 {@link PathPatternParser} 验规则本身，不起 Spring 容器——
 * 这里要测的是「哪条路径会被兜底规则接走」，与容器、与数据库都无关。
 */
class StaticResourceConfigTest {

    /** 兜底转发注册的两条规则，与 {@code StaticResourceConfig#addViewControllers} 一致。 */
    private static final PathPattern SINGLE = parse(StaticResourceConfig.SINGLE_SEGMENT);

    private static final PathPattern NESTED = parse(StaticResourceConfig.SINGLE_SEGMENT + "/**");

    private static PathPattern parse(String pattern) {
        return new PathPatternParser().parse(pattern);
    }

    /** 会被兜底规则接走 = 转发到 index.html。 */
    private static boolean forwarded(String path) {
        return SINGLE.matches(org.springframework.http.server.PathContainer.parsePath(path))
                || NESTED.matches(org.springframework.http.server.PathContainer.parsePath(path));
    }

    @Test
    @DisplayName("构建产物里的 /assets/*.js 必须交给静态资源，不能被兜底接走")
    void assetsAreNotSwallowedByTheFallback() {
        // 这一条就是那个白屏 bug。文件名带内容哈希，形态与 Vite 实际产出一致
        assertThat(forwarded("/assets/index-D2ngB27d.js")).isFalse();
        assertThat(forwarded("/assets/ChatRoom-CM-seahr.js")).isFalse();
        assertThat(forwarded("/assets/index-C3s_E0HB.css")).isFalse();
    }

    @Test
    @DisplayName("接口路径不能被兜底接走")
    void apiPathsAreNotSwallowedByTheFallback() {
        assertThat(forwarded("/api/sessions")).isFalse();
        assertThat(forwarded("/api/rag/search")).isFalse();
        assertThat(forwarded("/api/slim/chat")).isFalse();
        // 下载工具的链接，由另一个 resource handler 服务
        assertThat(forwarded("/files/download/report.pdf")).isFalse();
    }

    @Test
    @DisplayName("真实存在的静态文件不能被兜底接走")
    void realFilesAreNotSwallowedByTheFallback() {
        assertThat(forwarded("/index.html")).isFalse();
        assertThat(forwarded("/favicon.ico")).isFalse();
    }

    @Test
    @DisplayName("前端路由（含刷新）要被兜底接走，否则 F5 就是 404")
    void spaRoutesAreForwarded() {
        assertThat(forwarded("/slim")).isTrue();
        assertThat(forwarded("/manus")).isTrue();
        assertThat(forwarded("/knowledge")).isTrue();
        // 将来加了嵌套路由，刷新也不能 404
        assertThat(forwarded("/settings/profile")).isTrue();
    }

    @Test
    @DisplayName("根路径不归兜底管，它由 Spring Boot 的欢迎页机制服务")
    void rootIsServedByTheWelcomePage() {
        // 这条断言写反过一次，所以单独钉一下：兜底规则要求「至少有一个路径段」，
        // 而 `/` 一个段都没有，压根匹配不上。它会走 Spring Boot 的欢迎页
        // （static/index.html 存在时自动把 `/` 映射过去），两条路各管各的，都能到 index.html，
        // 但把它们混为一谈会让人以为「兜底规则漏了根路径」而去改正则，反而改坏
        assertThat(forwarded("/")).isFalse();
    }
}
