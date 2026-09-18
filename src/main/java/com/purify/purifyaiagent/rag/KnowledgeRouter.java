package com.purify.purifyaiagent.rag;

import com.purify.purifyaiagent.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索路由：先判断「这个问题要不要查知识库、该查哪一类」，再决定发不发检索请求。
 *
 * <p><b>为什么需要它</b>：知识库里的切片用元数据 {@code classification} 分成了
 * 食物热量 / 运动热量 / 药物三类，但检索接口默认是把整个库一起捞的——
 * 每问一句「你好」都要跑一次向量召回加一次重排序模型调用，纯属浪费。
 * 有了路由，闲聊、寒暄、与瘦身无关的问题压根不发起检索；只涉及一类的问题
 * 则带上过滤条件，只捞这一类。
 *
 * <p>判定方式是<b>纯字符串匹配</b>，不调模型、不产生任何远程开销——
 * 如果为了省一次检索而先花一次模型调用去分类，那就本末倒置了。
 * 关键词表放在 yml（{@code purify.rag.router.categories}）里，随时可改，不用重新打包。
 *
 * <p>这个类只做判定，不做检索：具体怎么按分类过滤由 {@link RoutingDocumentRetriever} 负责。
 */
@Slf4j
public class KnowledgeRouter {

    /**
     * 放进 {@code Query.context()} 的键。
     *
     * <p>用 context 而不是给检索器加字段，是因为检索器是单例、可能被并发调用，
     * 而「这次该查哪一类」是每次请求独有的信息——挂在请求上才不会被别的请求串味。
     */
    public static final String CATEGORIES_KEY = "purify.rag.categories";

    private final RagProperties ragProperties;

    public KnowledgeRouter(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 判定该怎么检索。
     *
     * @param question 用户这一轮的原始提问
     * @return 见 {@link Decision}；永远不会返回 {@code null}
     */
    public Decision route(String question) {
        List<RagProperties.Category> categories = ragProperties.getRouter().getCategories();

        if (categories.isEmpty()) {
            // 没配分类表就退回「不带过滤查全库」，而不是不查——
            // 配置缺失不该让知识库整个失效
            return Decision.all();
        }

        if (!StringUtils.hasText(question)) {
            // 没有提问就没有可检索的关键词，查了也是白查
            return Decision.skip();
        }

        List<String> matched = new ArrayList<>();
        for (RagProperties.Category category : categories) {
            if (!StringUtils.hasText(category.getValue()) || category.getKeywords() == null) {
                continue;
            }
            boolean hit = category.getKeywords()
                    .stream()
                    .anyMatch(word -> StringUtils.hasText(word) && question.contains(word));
            if (hit) {
                matched.add(category.getValue());
            }
        }

        if (matched.isEmpty()) {
            if (ragProperties.getRouter().isQueryAllWhenUnmatched()) {
                log.debug("[RAG路由] 没命中任何分类，按配置退回全库检索：{}", question);
                return Decision.all();
            }
            return Decision.skip();
        }

        log.debug("[RAG路由] 命中分类 {}：{}", matched, question);
        return Decision.of(matched);
    }

    /**
     * 一次检索的路由结果。
     *
     * @param retrieve   要不要发起检索；false 表示这个问题与知识库无关
     * @param categories 该按哪几个分类过滤；为空表示不带过滤条件查全库
     */
    public record Decision(boolean retrieve, List<String> categories) {

        /** 不查知识库，按普通对话处理。 */
        public static Decision skip() {
            return new Decision(false, List.of());
        }

        /** 查，但不按分类过滤。 */
        public static Decision all() {
            return new Decision(true, List.of());
        }

        /** 查，并带上这些分类作为过滤条件。 */
        public static Decision of(List<String> categories) {
            return new Decision(true, List.copyOf(categories));
        }
    }
}
