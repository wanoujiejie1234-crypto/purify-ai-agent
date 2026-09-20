package com.purify.purifyaiagent.agent.loop;

import com.purify.purifyaiagent.agent.AgentRun;

/**
 * 循环处置器：只负责「发现卡住之后怎么办」，不负责「怎么发现」。
 *
 * <p>目前有三档，按严重程度递增，靠 {@link #getOrder()} 决定先问谁：
 * <ol>
 *   <li>{@code HintLoopHandler} —— 提醒模型换策略，继续跑（最轻，先试它）；</li>
 *   <li>{@code AskUserLoopHandler} —— 转成向用户提问，本轮暂停；</li>
 *   <li>{@code AbortLoopHandler} —— 兜底中止，把情况说清楚。</li>
 * </ol>
 * 前两个各自只认自己那一段 streak（见 {@link #supports(LoopSignal)}），
 * 最后那个谁都接，所以它必然是兜底的那个——这个顺序由 order 保证，不是靠数组顺序碰运气。
 *
 * <p><b>扩展方式</b>：新增一种处置（比如「把当前进度存档后换个模型重试」「连续重复就降低
 * temperature 重试」），写一个实现类注册成 Bean 即可。{@code LoopGuard} 和智能体都不用改。
 */
public interface LoopHandler {

    /**
     * 这个信号归不归我管。
     *
     * <p>判断依据只有信号本身（类型 + streak），不看 run 的状态——
     * 否则「谁能处理」就变成了「当前处于什么局面」，多个处置器之间会互相依赖，扩展时很难推理。
     */
    boolean supports(LoopSignal signal);

    /**
     * 执行处置。
     *
     * <p>可以读 {@code run}（比如取最近一步说了什么，用来把提问写得更具体），
     * 但不应该直接改它的状态：状态的迁移统一由 {@code BaseAgent} 按返回的 {@link LoopAction} 来做，
     * 这样「谁改了状态」永远只有一个地方能回答。
     */
    LoopAction handle(LoopSignal signal, AgentRun run);

    /** 多个处置器都能接住时先问谁，小的先问。默认 0。 */
    default int getOrder() {
        return 0;
    }
}
