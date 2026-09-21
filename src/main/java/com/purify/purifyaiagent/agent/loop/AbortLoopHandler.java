package com.purify.purifyaiagent.agent.loop;

import com.purify.purifyaiagent.agent.AgentRun;

/**
 * 兜底处置：停下来收工，并说清楚卡在哪。
 *
 * <p>{@link #supports(LoopSignal)} 对任何信号都返回 true，所以它必须排在最后
 * （见 {@link #getOrder()}）——只有前面几档都不接手时才会走到这里。
 *
 * <p>为什么是「兜底」而不是「最高档」：前面每一档都用 streak 的上界把自己限定在一个区间里，
 * 谁也没规定区间必须首尾相接。将来在中间插一档、或者把某一档的阈值改小，
 * 都可能留出一个没人管的窟窿。有一个谁都接的兜底，这种改动最多是「处置方式不如预期」，
 * 不会变成「命中了但没人处理，循环继续跑」。
 *
 * <p>收工时给的不是一句「我失败了」，而是「试过什么、为什么停了、你可以怎么办」——
 * 用户抱怨的从来不是智能体做不到，而是它做不到还不说。
 */
public class AbortLoopHandler implements LoopHandler {

    @Override
    public boolean supports(LoopSignal signal) {
        return true;
    }

    @Override
    public LoopAction handle(LoopSignal signal, AgentRun run) {
        // 文案走 run 上那份 Messages，**不读 LocaleContextHolder**：这里跑在 Reactor 的
        // 调度线程上，读不到请求语言。三个占位符依次是「连续步数」「判据给的证据」「已跑步数」
        return LoopAction.abort(run.i18n().get(
                "agent.abort", signal.streak(), signal.evidence(), run.stepCount()));
    }

    @Override
    public int getOrder() {
        // 最大的 order，保证它是最后被问到的那个——「谁都接」的实现必须排在末尾，
        // 否则它会把后面所有处置器的机会都吃掉
        return 100;
    }
}
