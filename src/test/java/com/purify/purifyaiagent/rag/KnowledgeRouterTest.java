package com.purify.purifyaiagent.rag;

import com.purify.purifyaiagent.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索路由的单元测试。
 *
 * <p><b>不启动 Spring、不连库、不调模型</b>，只把 {@code application.yml} 里的
 * {@code purify.rag.router.*} 真的绑定一遍再喂给 {@link KnowledgeRouter}，
 * 所以它可以随时跑、秒出结果。路由判定要是错的，省下的检索费都会变成答非所问，
 * 这里就是拦这件事的地方。
 *
 * <p>连着 {@code application.yml} 一起测，是为了顺带看住「配置有没有真的绑上」：
 * 分类表一旦绑不上，{@link KnowledgeRouter} 会安静地退回「每个问题都查全库」——
 * 功能看着正常，优化却全没了，这种失败最不容易被发现。
 */
@Slf4j
class KnowledgeRouterTest {

    private static KnowledgeRouter knowledgeRouter;

    private static RagProperties ragProperties;

    @BeforeAll
    static void loadConfigFromYml() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);

        ragProperties = new Binder(ConfigurationPropertySources.from(sources))
                .bind("purify.rag", Bindable.of(RagProperties.class))
                .get();
        knowledgeRouter = new KnowledgeRouter(ragProperties);

        log.info("[KnowledgeRouterTest] 分类表：{}",
                ragProperties.getRouter().getCategories().stream().map(RagProperties.Category::getValue).toList());
    }

    @Test
    @DisplayName("配置绑定：分类表真的从 application.yml 读进来了")
    void config_isBound() {
        assertTrue(ragProperties.getRouter().isEnabled(), "路由应当默认开启");
        assertFalse(ragProperties.getRouter().getCategories().isEmpty(),
                "分类表是空的——多半是 yml 里的键名写错了，此时路由会安静地退化成「每个问题都查全库」");
        assertTrue(ragProperties.getRouter().getCategories().stream()
                        .allMatch(category -> category.getValue() != null && !category.getKeywords().isEmpty()),
                "每个分类都要有名字和关键词，否则它永远不会被命中");
    }

    @Test
    @DisplayName("只涉及一类的提问，只命中这一类")
    void route_singleCategory() {
        assertEquals(List.of("食物热量"), knowledgeRouter.route("鸡胸肉多少大卡").categories());
        assertEquals(List.of("运动热量"), knowledgeRouter.route("跑步半小时能消耗多少").categories());
        assertEquals(List.of("药物"), knowledgeRouter.route("奥利司他有什么副作用").categories());
    }

    @Test
    @DisplayName("同时涉及多类时都命中，此时退化成查全库")
    void route_multipleCategories() {
        // 命中两个分类 → 检索器看到条数不是 1 就不带过滤查全库
        // （带不带过滤都是一次请求，为多分类再发一次反而更费）
        List<String> matched = knowledgeRouter.route("跑步后吃什么比较好").categories();
        log.info("[route_multipleCategories] 命中分类：{}", matched);

        assertTrue(matched.contains("食物热量") && matched.contains("运动热量"),
                "「跑步后吃什么」应同时命中食物与运动两类，实际是 " + matched);
    }

    @Test
    @DisplayName("与吃/动/药都无关的提问不查知识库")
    void route_skipsChitchat() {
        assertFalse(knowledgeRouter.route("你好呀").retrieve(), "问候语不该触发检索");
        assertFalse(knowledgeRouter.route("你是谁").retrieve(), "问身份不该触发检索");
        assertFalse(knowledgeRouter.route("谢谢你").retrieve(), "道谢不该触发检索");

        // 断言的是默认配置（query-all-when-unmatched=false）下的行为。
        // 改成 true 之后上面这些会变成「不带过滤查全库」，这条断言就该跟着改。
        assertFalse(ragProperties.getRouter().isQueryAllWhenUnmatched(),
                "本用例断言的是「没命中就跳过」，配置改成查全库后请同步调整");
    }

    @Test
    @DisplayName("空提问不发检索")
    void route_blankQuestion() {
        assertFalse(knowledgeRouter.route(null).retrieve());
        assertFalse(knowledgeRouter.route("   ").retrieve());
    }
}
