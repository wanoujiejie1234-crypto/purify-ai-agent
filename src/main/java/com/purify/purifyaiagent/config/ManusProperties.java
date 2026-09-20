package com.purify.purifyaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PurifyManus 智能体的配置。
 *
 * <p>这些值都给了默认值，{@code application.yml} 里不写也能跑——默认值是照着「大多数任务
 * 三五步就结束」定的。值得按使用情况调的是 {@code loop} 那一段：阈值定得太松，模型多绕两步
 * 才被发现；定得太紧，正常的「重试一次」也会被当成卡住。
 */
@Data
@Component
@ConfigurationProperties(prefix = "purify.manus")
public class ManusProperties {

    /**
     * 一次任务最多走几步。
     *
     * <p>它是循环检测之外的最后一道闸，不是主要手段：正常情况下应该由「模型答完」
     * 或者看门狗先收尾，走到这个数说明前面几道都没兜住。
     */
    private int maxSteps = 12;

    /** 每个会话最多记住多少条消息。一次工具调用占两条（模型说要调、工具回结果），按整轮裁剪。 */
    private int memoryWindow = 40;

    /** 循环检测与处置的阈值。 */
    private Loop loop = new Loop();

    @Data
    public static class Loop {

        /** 连续几步调用同一组工具、参数完全相同才认为在重复。低于 2 没有意义（第一次调用也算重复）。 */
        private int repeatThreshold = 3;

        /**
         * A/B 来回横跳几轮才认为在重复。2 轮 = 4 步（A、B、A、B）。
         *
         * <p>比 {@code repeatThreshold} 小是有意的：横跳时每一步单独看都在「换做法」，
         * 等到它重复三轮，token 已经烧掉不少了。
         */
        private int alternateThreshold = 2;

        /** 连续几步说的话一模一样才算重复。 */
        private int answerThreshold = 3;

        /**
         * 重复到这个次数就升级为「问用户」。比它小的交给「提醒模型换策略」。
         *
         * <p>别设得比 {@code abortAfter} 大：那样「问用户」这一档永远不会被问到
         * （它的条件是 streak &lt; abortAfter），中间那档形同虚设。
         */
        private int askUserAfter = 5;

        /** 重复到这个次数就直接中止。比它小的仍然走「问用户」。 */
        private int abortAfter = 7;
    }
}
