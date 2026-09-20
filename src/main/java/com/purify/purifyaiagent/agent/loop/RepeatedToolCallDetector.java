package com.purify.purifyaiagent.agent.loop;

import com.purify.purifyaiagent.agent.AgentStep;

import java.util.List;
import java.util.Optional;

/**
 * 判据一：连续多步调用同一组工具、且参数完全相同。
 *
 * <p>这是最常见的一种死循环，对应 OpenManus 里 {@code ToolCallAgent.is_stuck()} 的判据。
 * 与它相比有两点不同：
 * <ul>
 *   <li>返回的是「重复了几次」而不是一个布尔值——处置的轻重全靠这个数（见 {@link LoopSignal#streak()}）；</li>
 *   <li>参数不同就不算重复：同一个工具换个城市查，那是正常的多步任务，不是卡住。</li>
 * </ul>
 *
 * <p>「一步」允许包含多个工具调用，此时要求<b>整组调用都一样</b>才算重复。
 * 只有这一组里的某一个变了，说明模型至少换了一部分做法，交给别的判据去看。
 */
public class RepeatedToolCallDetector implements LoopDetector {

    /** 最少重复几次才值得报警。2 次太敏感——模型先查天气再写文件时，中间重试一次很常见。 */
    private final int threshold;

    public RepeatedToolCallDetector(int threshold) {
        // 阈值低于 2 就没有意义了：任何一次工具调用都能算「重复 1 次」
        this.threshold = Math.max(2, threshold);
    }

    @Override
    public Optional<LoopSignal> detect(List<AgentStep> steps) {
        if (steps.isEmpty()) {
            return Optional.empty();
        }

        // 必须先有工具调用才谈得上重复。最后一步是「模型给出最终答复」（没有工具调用）时，
        // 循环本来就结束了，不需要任何干预
        AgentStep last = steps.get(steps.size() - 1);
        if (!last.hasToolCalls()) {
            return Optional.empty();
        }

        String signature = last.toolSignature();
        int streak = 0;
        for (int i = steps.size() - 1; i >= 0; i--) {
            AgentStep step = steps.get(i);
            // 中间夹了一步没调工具的，就说明中间发生过别的事，连续计数到此为止
            if (!step.hasToolCalls() || !signature.equals(step.toolSignature())) {
                break;
            }
            streak++;
        }

        if (streak < threshold) {
            return Optional.empty();
        }
        return Optional.of(new LoopSignal(LoopType.REPEATED_TOOL_CALL, signature, streak));
    }

    @Override
    public int getOrder() {
        // 证据最直白的一条，放最前面：能按它判定的，就不用再问别的判据了
        return 10;
    }
}
