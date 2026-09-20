package com.purify.purifyaiagent.agent;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能体一步的记录：这一步模型说了什么、调了哪些工具、工具返回了什么。
 *
 * <p>它是「循环检测」的全部输入。检测器只看这个 record，不看记忆、不看 Prompt——
 * 这样检测逻辑可以脱离模型和上下文单独测（见 {@code RepeatedToolCallDetectorTest}），
 * 也就不用为了测一条判据去起一个 Spring 容器。
 *
 * @param index       第几步，从 1 开始
 * @param text        这一步模型输出的文本。有工具调用时通常是一句「我先查一下……」，
 *                    也可能是空串——模型只回工具调用、不说话是合法的
 * @param toolCalls   这一步模型要求调用的工具，按模型给的顺序；没调工具时为空列表
 * @param toolResults 上面那些工具各自返回的内容，与 {@code toolCalls} 一一对应。
 *                    失败的调用返回的也是「失败原因」这段文本（工具异常由框架转成文本回给模型）
 */
public record AgentStep(int index, String text, List<AssistantMessage.ToolCall> toolCalls, List<String> toolResults) {

    public AgentStep {
        // 这两个列表来自框架和工具，外面可能还会被引用。拷一份进来，
        // 否则别处一个 clear() 就能悄悄改掉已经记下的历史，而检测器是拿它当证据的
        toolCalls = List.copyOf(toolCalls);
        toolResults = List.copyOf(toolResults);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * 这一步「调了什么」的指纹，用来判断两步是不是在做同一件事。
     *
     * <p>工具名 + 原始参数拼起来，顺序保持模型给的顺序——同一个顺序才是同一件事，
     * 参数顺序换了（比如先读文件再写文件 vs 先写再读）本来就是不同的做法。
     *
     * <p>参数只做 trim 而不解析 JSON：模型对同一件事生成的参数串是逐字节一致的，
     * 为了「键顺序不同」这种还没出现过的情况引入一个 JSON 解析，代价大于收益。
     */
    public String toolSignature() {
        return toolCalls.stream()
                .map(call -> call.name() + "(" + normalize(call.arguments()) + ")")
                .collect(Collectors.joining("|"));
    }

    private static String normalize(String arguments) {
        return arguments == null ? "" : arguments.trim();
    }
}
