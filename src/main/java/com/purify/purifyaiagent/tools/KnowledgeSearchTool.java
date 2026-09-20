package com.purify.purifyaiagent.tools;

import com.purify.purifyaiagent.rag.KnowledgeSearch;
import com.purify.purifyaiagent.rag.RagPrompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

/**
 * 知识库检索工具：让智能体自己决定「我还想查点什么」。
 *
 * <p><b>它和开场那次预检索是两件事，不是重复。</b>预检索只覆盖「用户最初那句话」，
 * 而且受关键词路由约束（没命中吃/动/药相关词就跳过）；模型追查细节、换个角度、
 * 或者发现预检索给的材料不相关时，需要自己再查一次。那是 ReAct 语义，
 * 预检索是保底，两者互补。
 *
 * <p><b>它不进 {@code ToolConfig} 那份共享清单，轻语那边不能挂。</b>轻语每一轮已经由
 * {@code KnowledgeBaseAdvisor} 无条件检索过一次，再给模型一个检索工具，等于让它在同一个
 * 问题上把同一条检索路径走第二遍：多一次向量化、多一次重排，而拿回来的是与预检索重复
 * （甚至互相矛盾）的切片，Advisor 那边对此一无所知。智能体这边刚好相反——
 * 预检索是「保底」，这个工具是「主动」。
 *
 * <p><b>返回值复用 {@link RagPrompts#DOCUMENT_FORMATTER}</b>：模型看到的切片格式
 * 和预检索注入的一模一样，它在同一轮里对比两批材料时不用做格式转换。
 */
@Slf4j
public class KnowledgeSearchTool {

    private final KnowledgeSearch knowledgeSearch;

    public KnowledgeSearchTool(KnowledgeSearch knowledgeSearch) {
        this.knowledgeSearch = knowledgeSearch;
    }

    @Tool(name = "knowledgeSearch", description = """
            检索健康知识库（减脂饮食、运动消耗、常见药物三类资料），返回命中的资料原文片段。
            什么时候用：需要具体的数字或事实（食物热量、运动消耗、食谱、药物说明）；
            或者用户的问题在对话里被指代得很含糊，你想先查证再回答。
            什么时候不用：问用户本人的情况（多高多重、想减到多少）用 getUserProfile；
            问最新资讯、或者知识库以外的信息用联网搜索。
            一次只查一个明确的问题，用完整的疑问句，把「它」「那个」换成具体名词；
            要查几个方面就分开调几次，不要全塞进一个问题。
            如果上下文里已经有知识库材料，不要用本工具重复查同一个问题，只查材料没覆盖的部分。
            返回为空表示知识库没有这方面的内容，可以换个说法再查或者改用联网搜索，不要因此编造数字。""")
    public String knowledgeSearch(
            @ToolParam(description = "要检索的问题，用完整的疑问句，例如「一碗米饭的热量是多少千卡」")
            String query) {

        if (!StringUtils.hasText(query)) {
            return "检索失败：问题不能为空。请重新组织一个具体的问题。";
        }

        try {
            KnowledgeSearch.Result result = knowledgeSearch.search(query);

            if (!result.decision().retrieve()) {
                // 路由判定「这与知识库无关」。对模型来说这和「查了没命中」不是一回事：
                // 它该知道不是知识库没有，而是这个问题压根不该问知识库，
                // 说成「没查到」它就会去编一个数字
                return "这个问题按关键词判定与健康知识库无关，没有发起检索。"
                        + "如果确实需要事实性资料，请把问题说成具体的食物、运动或药物。";
            }

            if (result.documents().isEmpty()) {
                return "知识库没有检索到与「" + query + "」相关的资料。"
                        + "可以换个说法再查，或者改用联网搜索；不要凭空给出数字。";
            }

            log.info("[KnowledgeSearchTool] 「{}」命中 {} 条，耗时 {}ms",
                    query, result.documents().size(), result.elapsedMs());
            return "知识库检索「" + query + "」命中 " + result.documents().size() + " 条：\n\n"
                    + RagPrompts.DOCUMENT_FORMATTER.apply(result.documents());
        }
        catch (RuntimeException exception) {
            // 不往外抛：工具抛异常会被框架转成一段栈信息回给模型，模型只能照着复述给用户。
            // 返回一句可读的话，它还能自己决定换个工具（同 AskHumanTool 对缺失上下文的处理）
            log.warn("[KnowledgeSearchTool] 检索失败：{}", exception.getMessage());
            return "知识库检索暂时不可用（" + exception.getMessage() + "）。"
                    + "请改用其它工具，或者基于一般性知识回答并说明这一点。";
        }
    }
}
