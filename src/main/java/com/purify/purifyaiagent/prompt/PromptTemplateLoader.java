package com.purify.purifyaiagent.prompt;

import com.purify.purifyaiagent.config.PromptProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板加载器：把资源文件里的模板读成 {@link PromptTemplate}，并做进程内缓存。
 *
 * <p>两个实现要点：
 * <ul>
 *   <li><b>缓存</b>：{@code PromptTemplate} 的构造函数会把资源内容一次性读进内存
 *       （见其 Resource 构造函数），所以缓存住实例就能避免每次请求都读盘/读 jar。</li>
 *   <li><b>变量必须齐全</b>：模板渲染用的是 StringTemplate 语法的
 *       {@code StTemplateRenderer}，它的默认校验模式是 {@code THROW}——
 *       模板里写了 {@code {foo}} 却没传 {@code foo} 会直接抛异常，
 *       报错信息里会列出缺失的变量名。因此调用方要保证变量传全。</li>
 * </ul>
 *
 * <p>顺带一个容易踩的坑：ST 把半角花括号当作变量分隔符，所以模板正文里
 * 如果需要出现 {@code {} （例如 JSON 示例），要么改用全角括号，要么换一个渲染器。
 */
@Slf4j
@Component
public class PromptTemplateLoader {

    /** 模板文件后缀。用 .st 是为了让编辑器按 StringTemplate 语法高亮。 */
    private static final String TEMPLATE_SUFFIX = ".st";

    private final String location;
    private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();

    public PromptTemplateLoader(PromptProperties properties) {
        String configured = properties.getLocation();
        this.location = configured.endsWith("/") ? configured : configured + "/";
        log.info("[PromptTemplateLoader] 模板目录：{}", this.location);
    }

    /** 按模板名取模板（带缓存）。 */
    public PromptTemplate get(String name) {
        return cache.computeIfAbsent(name, this::read);
    }

    /** 按模板名渲染出最终文本，{@code variables} 需要覆盖模板里的全部变量。 */
    public String render(String name, Map<String, Object> variables) {
        return get(name).render(variables);
    }

    private PromptTemplate read(String name) {
        Resource resource = new DefaultResourceLoader().getResource(location + name + TEMPLATE_SUFFIX);
        if (!resource.exists()) {
            throw new IllegalStateException("Prompt 模板文件不存在：" + location + name + TEMPLATE_SUFFIX);
        }
        PromptTemplate template = PromptTemplate.builder().resource(resource).build();
        log.info("[PromptTemplateLoader] 已加载模板 [{}]，共 {} 字符", name, template.getTemplate().length());
        return template;
    }
}
