package com.purify.purifyaiagent.agent.loop;

import com.purify.purifyaiagent.agent.AgentRun;

/**
 * 第二档处置：转成向用户提问，本轮 run 暂停。
 *
 * <p>为什么升级到问用户而不是继续提醒模型：提醒过一次还接着重复，说明卡住的原因不在
 * 「模型没想到换个做法」，而在「手上缺一个只有用户才知道的前提」——比如目标到底是什么、
 * 有没有忌口、要不要真的去写文件。这种情况再让模型自由发挥，它只能靠编。
 *
 * <p>提出来的问题会塞进本次 run 的 {@code HumanInterrupt}，和模型自己调 askHuman 走的是同一条
 * 通路（见 {@code AskHumanTool}）：调用方拿到的都是一句「等你回答」，用户带着同一个
 * 会话 ID 回一句话，就会带着完整上下文继续跑。两条路殊途同归，是因为「谁来问」不重要，
 * 「问完怎么接着跑」才需要统一。
 */
public class AskUserLoopHandler implements LoopHandler {

    /** 超过这个次数就不归我管了，交给兜底那一档（中止）。 */
    private final int abortAfter;

    public AskUserLoopHandler(int abortAfter) {
        this.abortAfter = abortAfter;
    }

    @Override
    public boolean supports(LoopSignal signal) {
        return signal.streak() < abortAfter;
    }

    @Override
    public LoopAction handle(LoopSignal signal, AgentRun run) {
        // 把「跑到第几步」「最近在做什么」一并说清楚：用户看到的不是一句抽象的报错，
        // 而是「它试了什么、卡在哪、我需要补什么」，这比让他去翻日志有用得多
        // 三段文案都走 run 上那份 Messages（这里跑在 Reactor 线程上，读不到请求语言）。
        // 拆成三段而不是塞进一个带可选参数的模板：中间那句「我最近一次的想法是」
        // 只在有内容时才出现，挤进同一个模板就得靠条件表达式凑参数，更难读
        String lastWords = run.lastAssistantText();
        StringBuilder question = new StringBuilder(
                run.i18n().get("agent.askUser", signal.streak(), signal.evidence()));
        if (!lastWords.isBlank()) {
            question.append(run.i18n().get("agent.askUserLastWords", abbreviate(lastWords)));
        }
        question.append(run.i18n().get("agent.askUserTail"));
        return LoopAction.askUser(question.toString());
    }

    @Override
    public int getOrder() {
        // 排在提醒之后：还能靠提醒救回来的，就不该打扰用户
        return 20;
    }

    private static String abbreviate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }
}
