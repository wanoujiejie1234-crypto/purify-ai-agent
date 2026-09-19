package com.purify.purifyaiagent.rag;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 两条知识库链路（百炼云知识库 / 本地 pgvector）共用的提示词与切片拼装格式。
 *
 * <p><b>为什么要抽这个类</b>：这两样东西原本一份在 {@code RagConfig} 里、一份在
 * {@code RoutingKnowledgeBaseAdvisor} 里，而后者还带着一句「父类那份是私有的，这里只能照抄一份」。
 * 再添一条链路就会出现第三份抄本——提示词一旦有多个副本，改了一处忘了另一处，
 * 表现是「同一个问题在两条链路上答得不一样」，极难排查。所以收敛到这里，只留一份。
 *
 * <p>类里的内容是从原处<b>原样搬过来</b>的，文本一个字符都没动：
 * 模型的提示词是按这个格式调出来的，改格式等于换提示词。
 */
public final class RagPrompts {

    private RagPrompts() {
    }

    /**
     * 拼给模型的用户消息模板。
     *
     * <p>{@code {context}} 和 {@code {query}} 两个占位符一个都不能少：
     * 构造 {@code ContextualQueryAugmenter} 时会做校验，缺了直接抛异常；
     * 反过来，模板里多写的花括号也会被当成占位符而报「变量未替换」。
     */
    public static final PromptTemplate USER_TEXT_ADVISE = new PromptTemplate("""
            # 知识库
            下面是知识库中检索到的材料，请优先依据它们回答。

            要求：
            1. 材料里有答案时，就按材料说，不要自行发挥；
            2. 材料里没有答案时，直接说明知识库中没有相关内容，再给一般性的健康建议；
            3. 不要提「根据材料」「根据上下文」这类话，直接给结论。

            $$材料：
            {context}

            问题：{query}

            答案：
            """);

    /**
     * 把召回的切片拼成一段文本。四个取值里 {@code index_id} 是拼装时按顺序现打的编号，
     * 另外三个来自切片自身的元数据——也就是说，<b>写入端必须给切片打上 {@code doc_name}
     * 和 {@code title} 这两个字段</b>，否则这里会渲染出「null」。
     *
     * <p>本地 pgvector 链路由 {@code PgVectorIndexService} 负责写这两个字段，
     * 与百炼侧控制台里打的是同名字段，所以两条链路共用这一份格式化器。
     */
    public static final Function<List<Document>, String> DOCUMENT_FORMATTER = documents -> documents.stream()
            .map(document -> """
                    [%s] 【文档名】%s
                    【标题】%s
                    【正文】%s
                    """.formatted(document.getMetadata().get("index_id"), document.getMetadata().get("doc_name"),
                    document.getMetadata().get("title"), document.getText()))
            .collect(Collectors.joining(System.lineSeparator()));
}
