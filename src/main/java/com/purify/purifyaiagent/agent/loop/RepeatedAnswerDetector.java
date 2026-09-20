package com.purify.purifyaiagent.agent.loop;

import com.purify.purifyaiagent.agent.AgentStep;

import java.util.List;
import java.util.Optional;

/**
 * 判据三：连续多步模型说的话一模一样。
 *
 * <p>它抓的是「工具在换、话没换」这种没进展：模型每步都换个工具调一遍，但配的那句
 * 「我先查一下相关信息」一个字都不变，说明它并没有因为上一步的结果改变判断。
 * 光看工具签名是发现不了的——工具确实每次都不同。
 *
 * <p>比较前会先归一化（去掉首尾空白、把连续空白压成一个空格），避免只因为换行或缩进不同
 * 就当成两句话。
 *
 * <p>只有「模型说了话」的步骤参与计数，空文本直接中断计数：模型只回工具调用不说话是合法的，
 * 把它也算成「重复」会误伤正常的连续调用。
 */
public class RepeatedAnswerDetector implements LoopDetector {

    private final int threshold;

    public RepeatedAnswerDetector(int threshold) {
        this.threshold = Math.max(2, threshold);
    }

    @Override
    public Optional<LoopSignal> detect(List<AgentStep> steps) {
        if (steps.isEmpty()) {
            return Optional.empty();
        }

        String last = normalize(steps.get(steps.size() - 1).text());
        if (last.isEmpty()) {
            return Optional.empty();
        }

        int streak = 0;
        for (int i = steps.size() - 1; i >= 0; i--) {
            String text = normalize(steps.get(i).text());
            if (text.isEmpty() || !text.equals(last)) {
                break;
            }
            streak++;
        }

        if (streak < threshold) {
            return Optional.empty();
        }
        return Optional.of(new LoopSignal(LoopType.REPEATED_ANSWER, abbreviate(last), streak));
    }

    @Override
    public int getOrder() {
        // 排最后：工具签名比文本更硬——同样一句话配同样的调用，判据一已经报过了
        return 30;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    /** 证据要进日志，太长的正文截断，否则一次重复就能刷满一屏。 */
    private static String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }
}
