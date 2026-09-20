package com.purify.purifyaiagent.agent.loop;

import com.purify.purifyaiagent.agent.AgentStep;

import java.util.List;
import java.util.Optional;

/**
 * 判据二：两步一循环——A、B、A、B……。
 *
 * <p>光看「连续两次是不是一样」是发现不了这种循环的：每一步和上一步都不同，
 * 每一步单看都「换了做法」，但整条序列在 A 和 B 之间原地横跳，永远不会结束。
 * 典型的长相是「读文件 → 改文件 → 读文件 → 改文件」，改的内容还每次一样。
 *
 * <p>真正在动的只有「步骤序号」，任务没有任何推进。这种时候注入「换个策略」的提示也没用
 * （模型认为自己每次都在换），所以它同样需要单独一条判据，由处置器升级为问用户或中止。
 *
 * <p><b>{@code streak} 报的是「连续多少步落在这个模式里」</b>（A、B、A、B 是 4 步），
 * 而不是轮数。这一点和另外两条判据是统一的：它们的 streak 也都是「连续多少步在重复」，
 * 于是处置阈值（连续 5 步→问用户、7 步→中止）对三种循环都同样好使，不用为每条判据各配一套数。
 */
public class AlternatingToolCallDetector implements LoopDetector {

    /** 最少横跳几轮才报警。2 轮 = 4 步（A,B,A,B）。 */
    private final int threshold;

    public AlternatingToolCallDetector(int threshold) {
        this.threshold = Math.max(2, threshold);
    }

    @Override
    public Optional<LoopSignal> detect(List<AgentStep> steps) {
        int required = threshold * 2;
        if (steps.size() < required) {
            return Optional.empty();
        }

        int size = steps.size();
        AgentStep last = steps.get(size - 1);
        AgentStep previous = steps.get(size - 2);
        if (!last.hasToolCalls() || !previous.hasToolCalls()) {
            return Optional.empty();
        }

        String a = last.toolSignature();
        String b = previous.toolSignature();
        // 两个签名一样时整条序列其实是「一直重复同一个调用」，那是判据一的地盘。
        // 这里放行的话，一个完全由相同调用组成的序列会被两条判据同时认领，日志和处置都会变得含糊
        if (a.equals(b)) {
            return Optional.empty();
        }

        // 从末尾往回走，看这个 A、B 交替的形态能延续多少步
        int matched = 0;
        for (int i = size - 1; i >= 0; i--) {
            AgentStep step = steps.get(i);
            if (!step.hasToolCalls()) {
                break;
            }
            // 往回数第 matched 步，期望的签名在 a、b 之间交替
            String expected = (matched % 2 == 0) ? a : b;
            if (!expected.equals(step.toolSignature())) {
                break;
            }
            matched++;
        }

        if (matched < required) {
            return Optional.empty();
        }
        // streak 按「步」报而不是按「轮」：见类注释，和另外两条判据保持同一个量纲
        return Optional.of(new LoopSignal(LoopType.ALTERNATING_TOOL_CALL,
                a + " 与 " + b + " 交替", matched));
    }

    @Override
    public int getOrder() {
        // 排在判据一之后：连续相同调用是更直白的证据，先按它报
        return 20;
    }
}
