package com.purify.purifyaiagent.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code messages.properties} 和 {@code messages_en.properties} 的键必须一一对应。
 *
 * <h2>为什么需要盯着它</h2>
 *
 * <p>{@code ResourceBundle} 找不到键时<b>不会报错</b>：它会退回默认那份（中文），
 * 于是英文用户看到一句中文。而 {@link Messages#get} 的兜底是「返回键本身」——
 * 只有在<b>两份都没有</b>时才会走到那一步。也就是说：
 * 英文文件里漏了一条，不会 500、不会打日志、不会让任何测试变红，
 * 只会让某个英文字段在某条不常走的错误分支上突然变成中文。
 *
 * <p>反过来，英文文件里多一条、中文文件里没有，中文用户会在那条分支上看到英文。
 *
 * <h2>占位符也要对</h2>
 *
 * <p>{@code {0}} {@code {1}} 的参数顺序是**和调用点约定好的**：调用方传的是
 * {@code ("error.auth.codeWrong", left)}，英文那份写成 {@code {1}} 就会直接抛
 * {@code MissingFormatArgumentException}（MessageSource 用 {@code MessageFormat} 渲染），
 * 而那个异常在 <b>英文字段才走到的分支上</b>才出现。
 *
 * <p>所以除了比键，还要比每条文案里的占位符集合。注意中文里出现「{0}」和
 * 英文里出现「{0}」是同一个位置，数量不一样就是漏了。
 */
class MessageBundleParityTest {

    private static final String BASE = "classpath:messages";
    private static final String EN_SUFFIX = "_en";

    @Test
    void 中英两个资源包的键必须一一对应() throws IOException {
        Set<String> zh = keysOf(BASE);
        Set<String> en = keysOf(BASE + EN_SUFFIX);

        Set<String> onlyZh = new TreeSet<>(zh);
        onlyZh.removeAll(en);
        Set<String> onlyEn = new TreeSet<>(en);
        onlyEn.removeAll(zh);

        assertThat(onlyZh)
                .as("这些键只有中文，英文会出现「界面是英文、这句话是中文」"
                        + "（ResourceBundle 找不到就退回默认那份，不报错）")
                .isEmpty();
        assertThat(onlyEn)
                .as("这些键只有英文，中文界面上会出现一句英文")
                .isEmpty();
    }

    @Test
    void 同一条文案的占位符必须一致() throws IOException {
        Map<String, String> zh = bundleOf(BASE);
        Map<String, String> en = bundleOf(BASE + EN_SUFFIX);

        for (Map.Entry<String, String> entry : zh.entrySet()) {
            String key = entry.getKey();
            if (!en.containsKey(key)) {
                // 键缺失由上面那个测试负责报，这里不重复报一遍
                continue;
            }
            assertThat(placeholdersOf(en.get(key)))
                    .as("键 %s 的占位符对不上：中文是 %s，英文是 %s。"
                            + "参数顺序是调用点传的，对不上会在渲染时抛异常",
                            key, placeholdersOf(entry.getValue()), placeholdersOf(en.get(key)))
                    .isEqualTo(placeholdersOf(entry.getValue()));
        }
    }

    /**
     * 读一份资源包。
     *
     * <p>不是直接 {@code getBundle}：那样读到的是「按当前 JVM 默认语言解析之后」的结果，
     * 而这里要的是**两个文件本身的原始内容**——测试要能在一个语言环境下同时检查两份。
     */
    private static Map<String, String> bundleOf(String location) throws IOException {
        Resource resource = new DefaultResourceLoader().getResource(location + ".properties");
        assertThat(resource.exists())
                .as("资源包不存在：%s.properties", location)
                .isTrue();

        Properties properties = new Properties();
        // 必须显式指定 UTF-8。Properties 的 load(InputStream) 按 ISO-8859-1 解码，
        // 中文会变成乱码——而 Spring Boot 的 spring.messages.encoding 默认是 UTF-8，
        // 两边读法不一致的话这个测试会报一堆假失败
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            result.put(name, properties.getProperty(name));
        }
        return result;
    }

    private static Set<String> keysOf(String location) throws IOException {
        return bundleOf(location).keySet();
    }

    /**
     * 一条文案里的 {0} {1} 这类位置参数。
     *
     * <p>只看**位置参数**，不看 {@code {name}}：这个项目的资源包里一条命名参数都没用，
     * 全是位置参数（因为 MessageSource 走的是 MessageFormat）。
     * 真有人用了命名参数，这个正则也不会误判成位置参数。
     */
    private static Set<String> placeholdersOf(String message) {
        Set<String> placeholders = new TreeSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{(\\d+)}")
                .matcher(message == null ? "" : message);
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        return placeholders;
    }
}
