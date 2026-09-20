package com.purify.purifyaiagent.agent.loop;

/**
 * 处置器给出的决定：这一次循环怎么处理。
 *
 * @param outcome 处置方式
 * @param message <b>要说给人听的那句话</b>，含义随 outcome 变化：
 *                <ul>
 *                  <li>{@link LoopOutcome#CONTINUE} —— 说给<b>模型</b>听：会被追加进下一次调用的
 *                      系统提示词，要求它换个策略；</li>
 *                  <li>{@link LoopOutcome#WAITING_FOR_USER} —— 说给<b>用户</b>听：作为提问直接
 *                      展示给用户，本轮 run 到此暂停；</li>
 *                  <li>{@link LoopOutcome#ABORT} —— 说给<b>用户</b>听：作为最终答复，说明卡在哪。</li>
 *                </ul>
 */
public record LoopAction(LoopOutcome outcome, String message) {

    public enum LoopOutcome {

        /** 接着跑，但先给模型一句提示。等价于 OpenManus 的 handle_stuck_state：改系统提示词，不打断循环。 */
        CONTINUE,

        /** 停下来问用户。缺的信息只有用户能给，再让模型自己试下去也是白试。 */
        WAITING_FOR_USER,

        /** 停下来收工。试到这个地步说明这条路走不通了，把情况说清楚比继续烧 token 更有用。 */
        ABORT
    }

    public static LoopAction hint(String message) {
        return new LoopAction(LoopOutcome.CONTINUE, message);
    }

    public static LoopAction askUser(String question) {
        return new LoopAction(LoopOutcome.WAITING_FOR_USER, question);
    }

    public static LoopAction abort(String reason) {
        return new LoopAction(LoopOutcome.ABORT, reason);
    }
}
