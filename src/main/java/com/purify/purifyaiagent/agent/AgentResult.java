package com.purify.purifyaiagent.agent;

import com.purify.purifyaiagent.agent.loop.LoopType;

import java.util.List;

/**
 * 一次 run 的最终结果，阻塞式接口直接返回它。
 *
 * @param chatId   会话 ID，用户带着它回来就能接着这个会话说
 * @param state    收尾状态，决定 {@code output} 该怎么用（见各字段说明）
 * @param output   {@link AgentState#FINISHED} / {@link AgentState#ABORTED} 时是给用户的答复；
 *                 {@link AgentState#ERROR} 时是失败说明；
 *                 {@link AgentState#WAITING_FOR_USER} 时是暂停前模型说的最后一句话（可能为空），
 *                 真正要展示给用户的是 {@code question}
 * @param question 需要用户回答的问题；不需要回答时为 null。展示它的优先级高于 {@code output}
 * @param steps    一共跑了几步。它是「这次花了多少代价」最直观的度量
 * @param loopHits 被看门狗拦下过几次。> 0 说明这次跑得不算顺，日志里有详细证据
 * @param loopTypes 命中的循环种类（去重，按首次命中的顺序）。
 *                  单独列出来是因为「被拦过 3 次」和「被拦的 3 次是同一种循环」是两种完全不同的病情
 */
public record AgentResult(String chatId,
                          AgentState state,
                          String output,
                          String question,
                          int steps,
                          int loopHits,
                          List<LoopType> loopTypes) {

    public AgentResult {
        loopTypes = List.copyOf(loopTypes);
    }

    /** 调用方最常问的一句：这次是等用户说话，还是可以当作答复直接展示？ */
    public boolean waitingForUser() {
        return state == AgentState.WAITING_FOR_USER;
    }
}
