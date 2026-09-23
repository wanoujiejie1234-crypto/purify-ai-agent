package com.purify.purifyaiagent.rag;

import com.purify.purifyaiagent.config.RagProperties;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 只有 yml 里那份配置的分类目录 —— 百炼链路用的就是这一份。
 *
 * <p><b>为什么百炼链路上没有「从库里发现」</b>：那条链路的切片在百炼云端，
 * 本地没有一张能查出「已经用过哪些分类」的表可查。而它的写入端也不在本项目里
 * （文档是在百炼控制台上传的、分类是在控制台里打的），所以这里无从发现，
 * 也不该假装能发现——给一个查不出东西的查询只会让 {@link #values()} 时不时变空。
 *
 * <p>反过来说，这份实现存在的意义就是：<b>「分类目录」这个概念有第二种实现</b>，
 * 而不是「反正只有一个实现，抽象一层做什么」。
 */
public class StaticKnowledgeCategories implements KnowledgeCategories {

    /** 构造时定下来的快照。配置对象在运行期不会变，所以不需要每次重算。 */
    private final List<RagProperties.Category> categories;

    public StaticKnowledgeCategories(RagProperties ragProperties) {
        this.categories = List.copyOf(ragProperties.getRouter().getCategories());
    }

    @Override
    public List<RagProperties.Category> all() {
        return categories;
    }

    @Override
    public List<String> values() {
        return categories.stream()
                .map(RagProperties.Category::getValue)
                .filter(StringUtils::hasText)
                .toList();
    }

    @Override
    public boolean isKnown(String value) {
        return StringUtils.hasText(value) && values().contains(value);
    }
}
