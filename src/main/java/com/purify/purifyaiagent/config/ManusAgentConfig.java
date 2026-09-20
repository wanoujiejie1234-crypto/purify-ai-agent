package com.purify.purifyaiagent.config;

import com.purify.purifyaiagent.agent.AgentMemory;
import com.purify.purifyaiagent.agent.PurifyManus;
import com.purify.purifyaiagent.agent.loop.AbortLoopHandler;
import com.purify.purifyaiagent.agent.loop.AlternatingToolCallDetector;
import com.purify.purifyaiagent.agent.loop.AskUserLoopHandler;
import com.purify.purifyaiagent.agent.loop.HintLoopHandler;
import com.purify.purifyaiagent.agent.loop.LoopDetector;
import com.purify.purifyaiagent.agent.loop.LoopGuard;
import com.purify.purifyaiagent.agent.loop.LoopHandler;
import com.purify.purifyaiagent.agent.loop.RepeatedAnswerDetector;
import com.purify.purifyaiagent.agent.loop.RepeatedToolCallDetector;
import com.purify.purifyaiagent.advisor.SensitiveWordChecker;
import com.purify.purifyaiagent.agent.tool.AskHumanTool;
import com.purify.purifyaiagent.chat.ChatRecordRepository;
import com.purify.purifyaiagent.prompt.PromptTemplateLoader;
import com.purify.purifyaiagent.rag.KnowledgeSearch;
import com.purify.purifyaiagent.tools.AgentToolRegistry;
import com.purify.purifyaiagent.tools.KnowledgeSearchTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * PurifyManus 智能体的装配处，和 {@code AdvisorConfig} / {@code ToolConfig} 是同一套做法：
 * 组件本身写成普通类（依赖走构造器，方便单独 new 出来测），在这里统一注册成 Bean。
 *
 * <p><b>这个文件就是「怎么扩展智能体」的答案。</b>想加一条循环判据？加一个
 * {@link LoopDetector} 的 Bean。想加一种处置方式？加一个 {@link LoopHandler} 的 Bean。
 * {@link LoopGuard} 和 {@code PurifyManus} 都不用改——它们拿到的是
 * 「容器里所有的检测器/处置器」这个列表，而不是点名要某几个。
 *
 * <p>两个顺序约定，配错了不会报错但行为会不对：
 * <ul>
 *   <li>{@code LoopHandler} 的 order 决定先问谁，兜底那档（谁都接）必须是最大的；</li>
 *   <li>{@code LoopDetector} 的 order 决定先按哪条判据认定，证据越直白的排越前。</li>
 * </ul>
 * 这两条写在各自的实现类里（{@code getOrder()}），不在这里排——顺序是判据/策略自身的属性，
 * 拆到两处反而容易改漏。
 */
@Slf4j
@Configuration
public class ManusAgentConfig {

    @Bean
    public AgentMemory agentMemory(ManusProperties properties) {
        return new AgentMemory(properties.getMemoryWindow());
    }

    // ==================== 循环检测：怎么发现「卡住了」 ====================

    @Bean
    public LoopDetector repeatedToolCallDetector(ManusProperties properties) {
        return new RepeatedToolCallDetector(properties.getLoop().getRepeatThreshold());
    }

    @Bean
    public LoopDetector alternatingToolCallDetector(ManusProperties properties) {
        return new AlternatingToolCallDetector(properties.getLoop().getAlternateThreshold());
    }

    @Bean
    public LoopDetector repeatedAnswerDetector(ManusProperties properties) {
        return new RepeatedAnswerDetector(properties.getLoop().getAnswerThreshold());
    }

    // ==================== 循环处置：发现之后怎么办（由轻到重） ====================

    @Bean
    public LoopHandler hintLoopHandler(ManusProperties properties) {
        return new HintLoopHandler(properties.getLoop().getAskUserAfter());
    }

    @Bean
    public LoopHandler askUserLoopHandler(ManusProperties properties) {
        return new AskUserLoopHandler(properties.getLoop().getAbortAfter());
    }

    /**
     * 兜底那档。{@code supports()} 对任何信号都返回 true，所以它的 order 必须是最大的，
     * 否则排在它后面的处置器永远轮不到。
     */
    @Bean
    public LoopHandler abortLoopHandler() {
        return new AbortLoopHandler();
    }

    @Bean
    public LoopGuard loopGuard(List<LoopDetector> detectors, List<LoopHandler> handlers) {
        // 两个 List 都由容器按类型收集：这里是「有哪些判据/策略」唯一的汇总点，
        // 加实现类不需要改这个方法的参数
        return new LoopGuard(detectors, handlers);
    }

    // ==================== 智能体本体 ====================

    /**
     * 向用户提问的工具。
     *
     * <p>它和 {@code ToolConfig} 里那些工具不是一回事：那些是「干活」的，这个是「暂停」的——
     * 调用它之后本轮 run 会停下来等用户回答。也正因为行为依赖智能体的循环，它只在
     * PurifyManus 上挂载，不进 {@code ToolConfig} 那份共享清单——挂到 SlimApp 上，
     * 模型调了它也没人在听，只会拿到一句「没有接入用户交互通道」。
     */
    @Bean
    public AskHumanTool askHumanTool() {
        return new AskHumanTool();
    }

    /**
     * 智能体本体。
     *
     * <p>工具清单 = {@code ToolConfig} 那份共享清单（本地工具 + MCP 工具）+ 本类专属的
     * {@link AskHumanTool}。共享清单在这里是<b>整份拿来用</b>的，所以加一个工具、
     * 加一个 MCP server，智能体这边一个字都不用动——这正是「对扩展开放」的那一半。
     *
     * <p>{@link ToolCallingManager} 在这里 new 出来直接传下去，而不是注册成 Bean：
     * 容器里已经有 Spring AI 自动配置的同类型 Bean，再声明一个，「按类型注入」就会变成
     * 两个候选、启动直接失败。这类「工具类依赖」显式传递比放进容器更省事，
     * 和 {@code ChatMemory} 那边不用第二个 Bean 是同一个原因。
     */
    @Bean
    public PurifyManus purifyManus(ManusProperties properties,
                                   LoopGuard loopGuard,
                                   AgentMemory agentMemory,
                                   ChatModel chatModel,
                                   // 显式点名，理由同 SlimApp 里那处：MCP 自动配置也会产出同类型的 Bean
                                   @Qualifier("agentToolCallbacks") ToolCallbackProvider agentToolCallbacks,
                                   AskHumanTool askHumanTool,
                                   PromptTemplateLoader promptTemplateLoader,
                                   PromptProperties promptProperties,
                                   ChatRecordRepository chatRecordRepository,
                                   SensitiveWordChecker sensitiveWordChecker,
                                   ObjectProvider<KnowledgeSearch> knowledgeSearchProvider) {

        List<ToolCallback> tools = new ArrayList<>(List.of(agentToolCallbacks.getToolCallbacks()));
        tools.addAll(List.of(ToolCallbacks.from(askHumanTool)));

        // 知识库没接入时不注册检索工具，而不是注册一个每次调用都只会报「没配置」的工具——
        // 那种工具比没有更糟：模型会看到它、会去调它、会拿回一句报错，最后把报错转述给用户。
        // 同 ToolConfig 里「联网搜索没配 Key 就不注册」的规矩
        KnowledgeSearch knowledgeSearch = knowledgeSearchProvider.getIfAvailable();
        if (knowledgeSearch != null) {
            // 这个工具不持有需要关闭的资源，直接在这里 new（同 ToolConfig 里联网搜索的做法）
            tools.addAll(List.of(ToolCallbacks.from(new KnowledgeSearchTool(knowledgeSearch))));
        }

        // 合并之后必须重新查一遍重名：共享清单内部的重名 ToolConfig 已经查过，
        // 但「askHuman / knowledgeSearch 有没有和某个 MCP 工具撞名」只有合并之后才知道，
        // 而 MCP 工具是运行期从 server 拉回来的
        AgentToolRegistry.assertNoDuplicateNames(tools);

        return new PurifyManus(
                properties.getMaxSteps(),
                loopGuard,
                agentMemory,
                chatModel,
                tools.toArray(ToolCallback[]::new),
                ToolCallingManager.builder().build(),
                promptTemplateLoader,
                promptProperties,
                chatRecordRepository,
                knowledgeSearch,
                sensitiveWordChecker);
    }
}
