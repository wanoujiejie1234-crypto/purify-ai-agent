package com.purify.purifyaiagent.agent.loop;

import com.purify.purifyaiagent.agent.AgentStep;

import java.util.List;
import java.util.Optional;

/**
 * 循环检测器：只负责「怎么发现智能体在原地打转」，不负责「发现之后怎么办」。
 *
 * <p><b>为什么要做成接口。</b>「卡住」没有唯一判据：连续调同一个工具是一种，
 * A/B 来回横跳是另一种，反复说同一句话又是另一种。这些判据将来还会继续增加
 * （比如「连续多步工具全部报错」「工具结果长度不增」），所以它们必须是可插拔的——
 * 加一条判据 = 新写一个实现类并注册成 Bean，{@code LoopGuard} 和智能体本身一个字都不用改。
 *
 * <p><b>约定：实现必须是无状态的纯函数。</b>输入只有「到目前为止的步骤记录」，输出只有信号。
 * 智能体是单例 Bean，多个会话会并发调用它；状态一旦留在检测器里，两个会话的步数就会互相污染。
 * 「已经重复了几次」这类信息从 {@code steps} 里现算，不额外记。
 *
 * @see LoopHandler 对应的处置方
 */
public interface LoopDetector {

    /**
     * 看一眼最近的步骤，判断是不是出现了自己负责的那种循环。
     *
     * <p>只需要看倒数若干步，不必遍历全部；返回的 {@code streak} 要尽量准确，
     * 因为处置的轻重就是按它分级的。
     *
     * @param steps 本次 run 到目前为止的全部步骤，按时间从早到晚排列；不会为 null
     * @return 命中则返回信号，没命中返回空
     */
    Optional<LoopSignal> detect(List<AgentStep> steps);

    /**
     * 多个检测器同时命中时的顺序，小的先看。默认 0。
     *
     * <p>实际用不到「同时命中」：{@link LoopGuard} 取第一个命中的就返回了。
     * 但顺序决定了优先级——比如「连续 6 步同一个调用」同时符合「重复」和「A/B 交替」的形态时，
     * 前者证据更直白，应该排前面。
     */
    default int getOrder() {
        return 0;
    }
}
