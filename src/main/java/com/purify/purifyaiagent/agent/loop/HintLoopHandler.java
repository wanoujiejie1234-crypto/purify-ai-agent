package com.purify.purifyaiagent.agent.loop;

import com.purify.purifyaiagent.agent.AgentRun;

/**
 * 第一档处置：提醒模型换个策略，然后接着跑。
 *
 * <p>这是 OpenManus 里 {@code handle_stuck_state} 的做法——检测到重复之后，
 * 往系统提示词里追加一句「你刚才在重复，换个思路」，不打断循环。之所以先试它，
 * 是因为大部分「重复」只是模型一时钻进牛角尖：它确实需要一次明确的提醒，
 * 而提醒的代价只有一次模型调用，比直接抛给用户或者中止都便宜。
 *
 * <p>提示是追加进<b>系统提示词</b>而不是当成一条用户消息，这一点和 OpenManus 一致：
 * 它是元指令（关于怎么做，不是关于做什么），混进对话历史里会污染记忆，
 * 用户下次翻历史会看到一句莫名其妙的话。
 */
public class HintLoopHandler implements LoopHandler {

    /** 超过这个次数就不归我管了，交给下一档（问用户）。 */
    private final int askUserAfter;

    public HintLoopHandler(int askUserAfter) {
        this.askUserAfter = askUserAfter;
    }

    @Override
    public boolean supports(LoopSignal signal) {
        return signal.streak() < askUserAfter;
    }

    @Override
    public LoopAction handle(LoopSignal signal, AgentRun run) {
        // 文案走 run 上那份 Messages，**不读 LocaleContextHolder**：
        // 这个处理器跑在 Reactor 的调度线程上，那里读不到请求的语言，
        // 只会静默地回落成默认语言。要说的话在 messages*.properties 的 agent.hint
        // （开头那个空行也在那边，它是要接在系统提示词后面的分隔）
        return LoopAction.hint(run.i18n().get("agent.hint", signal.streak(), signal.evidence()));
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
