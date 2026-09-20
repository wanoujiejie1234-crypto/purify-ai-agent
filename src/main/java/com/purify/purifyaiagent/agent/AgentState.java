package com.purify.purifyaiagent.agent;

/**
 * 一次 run 的状态。对应 OpenManus 里 BaseAgent 的 state 字段（那边是 IDLE/RUNNING/FINISHED/ERROR）。
 *
 * <p>这里把「FINISHED」拆成了三种，因为对调用方来说它们的处理方式完全不同：
 * 正常答完可以直接展示；等用户回答要转成一个提问界面；中止则要告诉用户为什么没做完。
 * 合成一个「结束了」再让调用方去猜，是把判断成本丢给了每一个调用方。
 */
public enum AgentState {

    /** 循环正在跑。 */
    RUNNING,

    /** 停下来等用户回答。用户下次带着同一个会话 ID 发消息，就会从这里接着跑。 */
    WAITING_FOR_USER,

    /** 任务做完了，output 是最终答复。 */
    FINISHED,

    /** 被看门狗中止（反复重复）或步数超预算。output 说明卡在哪。 */
    ABORTED,

    /**
     * 命中敏感词，被应用层按策略拦下。output 是那段引导话术。
     *
     * <p><b>它既不是 {@link #ABORTED} 也不是 {@link #ERROR}，别合并进去。</b>
     * 对用户来说这三件事完全不同：ERROR 是「服务坏了」（该重试或报障），
     * ABORTED 是「任务没做完」（该换个说法再试），而 BLOCKED 是「顾问主动拒绝回答」
     * ——话术本身就是要展示的内容，界面不该给它画红框或者打叉。
     *
     * <p>合并的代价在 {@code GlobalExceptionHandler} 里已经踩过一次：同一个拦截
     * 在那条链路上特意返回 HTTP 200 而不是 4xx，就是为了让前端当普通消息渲染。
     */
    BLOCKED,

    /** 出错退出，比如模型接口报错、工具清单对不上。output 是给用户看的失败说明。 */
    ERROR;

    /**
     * 是不是已经结束了（相对于「还在跑」）。
     *
     * <p>注意 {@link #WAITING_FOR_USER} 也算结束：它结束的是<b>这一次 run</b>，
     * 用户回答会是下一次 run。循环里判断「还要不要继续走下一步」用的就是这个口径。
     */
    public boolean isTerminal() {
        return this != RUNNING;
    }
}
