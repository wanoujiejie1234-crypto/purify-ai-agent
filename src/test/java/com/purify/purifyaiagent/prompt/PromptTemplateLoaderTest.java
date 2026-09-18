package com.purify.purifyaiagent.prompt;

import com.purify.purifyaiagent.config.PromptProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PromptTemplateLoader} 的单元测试：不启 Spring、不连数据库、不调模型。
 *
 * <p>存在的意义是「模板文件里的变量名」这件事编译器管不了：
 * 把 {@code {nickname}} 写成 {@code {nickName}}，Java 侧照样编译通过，
 * 直到线上第一次请求才抛异常。这里把每个模板的变量契约固定下来。
 */
class PromptTemplateLoaderTest {

    private PromptTemplateLoader loader;

    @BeforeEach
    void setUp() {
        loader = new PromptTemplateLoader(new PromptProperties());
    }

    @Test
    @DisplayName("系统提示词模板：变量被替换，且不留残余花括号")
    void renderSystemTemplate() {
        String text = loader.render("slim-app-system", Map.of(
                "nickname", "小明",
                "today", "2026-09-17"));

        assertTrue(text.contains("称呼用户为「小明」"), "nickname 变量未被替换：\n" + text);
        assertTrue(text.contains("2026-09-17"), "today 变量未被替换：\n" + text);
        assertFalse(text.contains("{"), "渲染后仍有未替换的变量：" + text);
        assertTrue(text.contains("你是「轻语」"), "模板正文被读坏了");
    }

    @Test
    @DisplayName("看图模板：question 变量被替换")
    void renderImageExplainTemplate() {
        String text = loader.render("image-explain", Map.of("question", "这顿饭热量高吗？"));

        assertTrue(text.contains("这顿饭热量高吗？"), "question 变量未被替换：\n" + text);
    }

    @Test
    @DisplayName("无变量模板：不传变量也能渲染")
    void renderTemplateWithoutVariables() {
        String text = loader.render("vision-system", Map.of());

        assertTrue(text.contains("图片解读助手"), "模板正文被读坏了：\n" + text);
    }

    @Test
    @DisplayName("变量缺失：直接抛异常，而不是把 {xxx} 原样发给模型")
    void renderWithMissingVariable() {
        // StTemplateRenderer 默认校验模式是 THROW，这是好事：
        // 宁可启动/首次调用就报错，也不要让模型收到一段带占位符的提示词
        assertThrows(RuntimeException.class, () -> loader.render("image-explain", Map.of()));
    }

    @Test
    @DisplayName("模板不存在：报错信息里要带上找过的路径")
    void renderMissingTemplate() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> loader.get("not-exists"));

        assertTrue(exception.getMessage().contains("not-exists.st"), "报错信息应包含模板路径");
    }

    @Test
    @DisplayName("模板会被缓存：重复取到的是同一个实例")
    void templateIsCached() {
        assertEquals(loader.get("slim-app-system"), loader.get("slim-app-system"));
    }
}
