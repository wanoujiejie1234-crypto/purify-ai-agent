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
        return LoopAction.abort("这个任务我没能做完：连续 " + signal.streak() + " 步都在重复同样的操作（"
                + signal.evidence() + "），再试下去大概率还是同样的结果，我先停在这里，不继续消耗了。"
                + "\n已经跑过的 " + run.stepCount() + " 步都在这个会话里，你可以："
                + "\n- 换一种说法把目标讲得更具体一点，我接着试；"
                + "\n- 或者把任务拆小，先让我做其中一步。");
    }

    @Override
    public int getOrder() {
        // 最大的 order，保证它是最后被问到的那个——「谁都接」的实现必须排在末尾，
        // 否则它会把后面所有处置器的机会都吃掉
        return 100;
    }
}
