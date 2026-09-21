package com.purify.purifyaiagent.agent.loop;

import com.purify.purifyaiagent.agent.AgentRun;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 循环看门狗：每次模型走完一步，都拿这次的步骤记录问一遍所有检测器；
 * 命中就再问一遍所有处置器，由第一个接得住的给出决定。
 *
 * <p>它本身不含任何判据、也不含任何策略，只负责把两边串起来并按 order 排序——
 * 判据和策略都在各自的实现类里，加一个实现类就是一个新的记法/做法。
 *
 * <p>所以这个类是整条链路里<b>唯一不会被修改</b>的那个：只要「先检测、再处置」这个骨架成立，
 * 它下面的零件怎么增删都行。这也是把「检测」和「处置」拆成两个接口的原因——
 * 拆开之后，两个方向的扩展互不影响，而串起来的地方只有这一个文件。
 */
@Slf4j
public class LoopGuard {

    private final List<LoopDetector> detectors;
    private final List<LoopHandler> handlers;

    /**
     * @param detectors 检测器，由 Spring 按类型收集；调用方不需要关心有几个、都是谁
     * @param handlers  处置器，同上
     */
    public LoopGuard(List<LoopDetector> detectors, List<LoopHandler> handlers) {
        // 排序放在构造期做一次：每次检查都排一遍没有意义，而 getOrder() 是纯函数，排完不会失效
        this.detectors = detectors.stream().sorted(Comparator.comparingInt(LoopDetector::getOrder)).toList();
        this.handlers = handlers.stream().sorted(Comparator.comparingInt(LoopHandler::getOrder)).toList();

        log.info("[LoopGuard] 循环检测器 {} 个：{}；处置器 {} 个：{}",
                this.detectors.size(), this.detectors.stream().map(d -> d.getClass().getSimpleName()).toList(),
                this.handlers.size(), this.handlers.stream().map(h -> h.getClass().getSimpleName()).toList());
    }

    /**
     * 检查一次，命中就返回处置决定。
     *
     * <p>取<b>第一个</b>命中的检测器就返回，不再往下问：多条判据同时成立时，
     * 不同的判据会给出不同的证据和 streak，混在一起只会让日志和处置都变得难以解释。
     *
     * @return 空表示一切正常，循环可以继续
     */
    public Optional<LoopAction> inspect(AgentRun run) {
        for (LoopDetector detector : this.detectors) {
            Optional<LoopSignal> found = detector.detect(run.steps());
            if (found.isEmpty()) {
                continue;
            }

            LoopSignal signal = found.get();
            run.recordLoopHit();
            for (LoopHandler handler : this.handlers) {
                if (!handler.supports(signal)) {
                    continue;
                }
                LoopAction action = handler.handle(signal, run);
                // 打到 warn：这条日志是排查「智能体为什么停下来问用户 / 为什么中止」的唯一线索，
                // 光看最终回答是分不清它正常收尾还是被看门狗拿下的
                log.warn("[LoopGuard] 命中 {} | 由 {} 处置为 {} | 提示词/问题：{}",
                        signal.describe(), handler.getClass().getSimpleName(), action.outcome(), action.message());
                return Optional.of(action);
            }

            // 走到这里说明所有处置器都不认这个信号。理论上不该发生（兜底那档谁都接），
            // 但真发生了也只是这一轮不做干预，不能让整个对话垮掉，所以打日志继续跑
            log.error("[LoopGuard] 没有任何处置器接得住信号 {}，本轮不做干预。"
                    + "检查一下处置器是否都注册成了 Bean，以及兜底那档的 order 是不是最大的", signal.describe());
        }
        return Optional.empty();
    }
}
