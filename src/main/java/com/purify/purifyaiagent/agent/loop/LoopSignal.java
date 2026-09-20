package com.purify.purifyaiagent.agent.loop;

/**
 * 一次循环命中的结果：检测器发现「像卡住了」时产出它。
 *
 * <p>它只描述<b>发现了什么</b>，不描述<b>该怎么办</b>——怎么办是 {@link LoopHandler} 的事。
 * 这条边界是这套机制能扩展的前提：换一种处置策略不用动检测器，反之亦然。
 *
 * @param type     命中的是哪一条判据
 * @param evidence 证据，要能直接打进日志看清楚「到底重复了什么」（比如
 *                 {@code maps_weather({"city":"杭州"})}）
 * @param streak   这种模式已经连续重复了多少次。处置器就是按它分级的：
 *                 次数少的时候提醒模型换策略，多了就转问用户，再多就中止。
 *                 各条判据对「一次」的定义略有不同（见各自的检测器注释），
 *                 但都表示「同一个模式出现了几遍」，所以可以共用同一套分级阈值。
 */
public record LoopSignal(LoopType type, String evidence, int streak) {

    /** 日志里用的简短描述，避免每个检测器各写一遍拼接。 */
    public String describe() {
        return type + "：连续 " + streak + " 次，证据=" + evidence;
    }
}
