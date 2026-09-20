package com.purify.purifyaiagent.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

/**
 * 让模型能<b>主动</b>向用户提问、要反馈。
 *
 * <p>它和别的工具不一样：别的工具是「干活」，它是「暂停」。模型调用它之后，
 * 本轮 run 会在这一步结束时停下来，把问题交给用户；用户回的那句话会成为下一次 run 的输入，
 * 而记忆里已经存着「我调过 askHuman，正在等回答」，所以模型知道自己刚才问了什么、为什么问。
 *
 * <p>调用它的时机写在系统提示词里（信息不足、需求有歧义、要做一个有副作用的操作之前），
 * 这里只负责把问题交出去——判断该不该问是模型的事，不是工具的事。
 *
 * <p><b>取不到上下文时不抛异常。</b>这个工具只在 PurifyManus 里挂载，理论上上下文一定在；
 * 但万一将来被挂到别处（那里没有循环在听它说话），返回一句可读的话比抛异常好：
 * 模型的 React 循环会因为异常中断，而它本来是可以直接作答的。这和
 * {@code UserProfileTool} 对缺失 userId 的处理是同一条原则。
 */
@Slf4j
public class AskHumanTool {

    @Tool(name = "askHuman", description = """
            向用户提问并等待他的回答。适用场景：缺少只有用户才知道的信息（偏好、约束、目标）、
            需求有歧义、需要在多个方案里选一个、或者准备执行有副作用的操作（写文件、发请求）之前
            想先确认。一次只问最关键的一两个问题，要问得具体。
            调用后本轮任务会暂停，用户回答后你会带着完整上下文继续，所以不必在问题里复述全部背景。
            能从已有信息或工具里查到的，不要问用户。""")
    public String askHuman(@ToolParam(description = "要问用户的问题，一句话说清楚，不要罗列问卷")
                           String question,
                           ToolContext toolContext) {

        Object value = toolContext == null ? null : toolContext.getContext().get(HumanInterrupt.KEY);
        if (!(value instanceof HumanInterrupt interrupt)) {
            log.warn("[AskHumanTool] 工具上下文里没有 {}，本次提问无法送达用户",
                    HumanInterrupt.KEY);
            return "提问没有送达用户：当前对话没有接入用户交互通道。"
                    + "请直接根据已有信息作答；如果确实缺信息，就在回答里说明还缺什么。";
        }
        if (!StringUtils.hasText(question)) {
            return "提问失败：问题不能为空。请重新组织一个具体的问题。";
        }

        interrupt.ask(question);
        log.info("[AskHumanTool] 模型请求向用户提问：{}", question);
        // 这句话是回给模型看的，用户看不到（用户看到的是 interrupt 里的问题）。
        // 说清楚「本轮到此为止」很关键，否则模型会以为自己已经拿到了答案，接着往下编
        return "问题已经提交给用户，本轮到此暂停，用户回答后会继续。现在不要继续猜测答案。";
    }
}
