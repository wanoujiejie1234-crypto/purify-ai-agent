package com.purify.purifyaiagent.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中英两套提示词模板的变量集必须一模一样。
 *
 * <h2>为什么需要一个测试盯着这件事</h2>
 *
 * <p>{@code PromptTemplateLoader} 渲染用的是 StringTemplate 的 {@code StTemplateRenderer}，
 * 而它的校验模式是 <b>THROW</b>：模板里写了 {@code {foo}} 而调用点没传 {@code foo}，
 * 会直接抛异常。听起来很安全，但**抛的时机**才是问题——
 * 它发生在第一次渲染这个模板的时候，也就是<b>第一个英文请求打进来的时候</b>，
 * 而不是启动的时候。
 *
 * <p>后果很具体：更新提示词时只在中文那份里加了个新占位符（比如 {timezone}），
 * Java 调用点跟着传了，中文一切正常；英文那份没加，于是**英文用户一开口就 500**。
 * 本地手测通常只用一种语言，这个 bug 能一路活到线上。
 *
 * <p>反过来也一样：英文模板里多写了一个占位符、而调用点没传，同样是英文用户先炸。
 *
 * <p>所以这里把两边的占位符集合直接比一次。这个测试跑起来不需要 Spring 上下文、
 * 不连任何外部服务，是纯文本比对——按本项目的约定，这类测试可以随便跑。
 *
 * <h2>怎么加新模板</h2>
 *
 * <p>在下面 {@code PAIRS} 里补一行即可。**忘了补**的话这个测试不会报错（它只知道
 * 清单里的事），所以加模板时的规矩仍然是「中文英文一起加」，
 * 这里只是给已经成对的那些上一道锁。
 */
class PromptTemplateParityTest {

    /**
     * 成对的模板名（不含 {@code .st}）。左边是中文那份，右边是英文那份。
     *
     * <p>英文那份的文件名统一是「中文名 + -en」，和
     * {@code PromptTemplateLoader#render(Messages, String, Map)} 的拼法一致。
     */
    private static final String[][] PAIRS = {
            {"slim-app-system", "slim-app-system-en"},
            {"vision-system", "vision-system-en"},
            {"image-explain", "image-explain-en"},
            {"purify-manus-system", "purify-manus-system-en"},
    };

    /**
     * StringTemplate 的占位符：{@code { 变量名 }}。
     *
     * <p>只认「字母开头、只含字母数字下划线」的，避免把模板正文里正常的
     * 大括号（比如 JSON 示例）当成变量。模板文件里本来也不该出现半角大括号的
     * 非变量用法——那正是 {@code PromptTemplateLoader} 注释里提醒过的坑。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");

    @Test
    void 中英模板的占位符必须一一对应() throws IOException {
        for (String[] pair : PAIRS) {
            String zh = read(pair[0]);
            String en = read(pair[1]);

            assertThat(placeholdersOf(en))
                    .as("英文模板 %s 的占位符要和 %s 完全一致："
                            + "少一个会在英文请求上抛异常，多一个同样是。"
                            + "改提示词时两个文件一起改", pair[1], pair[0])
                    .isEqualTo(placeholdersOf(zh));
        }
    }

    /**
     * 英文模板不能还是中文的。
     *
     * <p>最常见的低级错误不是占位符对不上，是「复制了一份，忘了翻」。
     * 那个不会报任何错，只会让英文用户收到一段中文的系统提示词——
     * 而模型多半就照着用中文回答了。这里按汉字占比卡一道。
     */
    @Test
    void 英文模板不能是中文的() throws IOException {
        for (String[] pair : PAIRS) {
            String en = read(pair[1]);
            long han = en.chars().filter(PromptTemplateParityTest::isHan).count();
            // 阈值放得很松（5%）：英文模板里出现几个汉字是允许的——
            // 比如给模型解释某个中文专有名词。整篇没翻的话这个比例会远超 5%
            assertThat((double) han / Math.max(1, en.length()))
                    .as("英文模板 %s 里汉字占比过高（%d 个），看起来是复制了中文那份但没翻", pair[1], han)
                    .isLessThan(0.05);
        }
    }

    private static Set<String> placeholdersOf(String template) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static boolean isHan(int codePoint) {
        return codePoint >= 0x4E00 && codePoint <= 0x9FFF;
    }

    private static String read(String name) throws IOException {
        Resource resource = new DefaultResourceLoader()
                .getResource("classpath:prompts/" + name + ".st");
        if (!resource.exists()) {
            throw new IOException("提示词模板不存在：prompts/" + name + ".st");
        }
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
