package com.purify.purifyaiagent.agent;

import com.purify.purifyaiagent.agent.loop.LoopAction;
import com.purify.purifyaiagent.agent.loop.LoopGuard;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

/**
 * 智能体的骨架：把「一步」的定义留给子类，其余全在这里。
 *
 * <p>结构对着 OpenManus 的 {@code BaseAgent}（那边是 {@code run()} 里的 {@code while} 循环 +
 * {@code step()} 抽象方法），但有两处按 Java 这边的实际情况做了调整：
 *
 * <p><b>一、循环用事件流表达，而不是 while 循环。</b>
 * 一次 run 要同时满足两个调用方：阻塞式接口只要结果，流式接口要把每一步中间过程实时推给用户。
 * 如果写成 {@code while} 再让流式那条路去「边跑边收集」，就得把中间状态存在某个共享变量里，
 * 于是又回到「状态放在哪」的老问题。写成 {@code Flux} 之后，两种用法是同一个实现的两个投影：
 * 阻塞式 = 把事件流跑完再看结果，流式 = 把事件流原样转发出去。
 *
 * <p><b>二、每步之后有看门狗。</b>OpenManus 是在循环里调 {@code is_stuck()} 判断要不要
 * 给模型加提示；这里把它换成了一个可扩展的 {@link LoopGuard}（检测和处置各自可插拔），
 * 并且多了一个 OpenManus 没有的出口：停下来问用户。
 *
 * <p>循环的推进过程：
 * <pre>
 *   step()            子类实现的一步：模型想做什么 + 把它做掉，并把消息追加进 run
 *     ↓
 *   落记忆             每步都存一次，客户端中途断开也不会丢上下文
 *     ↓
 *   看门狗             LoopGuard.inspect(run)：没命中就继续，命中就按处置决定走
 *     ↓
 *   预算检查           步数用完了就收工，避免「判据没命中但一直在跑」的漏网之鱼
 *     ↓
 *   回 step() 或收尾    终态在下一轮开头统一转成一个终态事件抛出去
 * </pre>
 *
 * <p>子类只需要实现 {@link #step(AgentRun)}，并且保证它要么让 run 变成终态、
 * 要么至少往前走了一步（否则这个循环会一直转到预算耗尽）。
 */
@Slf4j
public abstract class BaseAgent {

    /** 步数用完后给用户的说明。 */
    private static final String BUDGET_MESSAGE =
            "这个任务我已经连着做了 %d 步还没收尾，先停在这里，免得一直耗下去。"
                    + "你可以把要求说得更具体一点，或者把任务拆成几步，我接着做。";

    private final String name;
    private final int maxSteps;
    private final LoopGuard loopGuard;
    private final AgentMemory memory;

    protected BaseAgent(String name, int maxSteps, LoopGuard loopGuard, AgentMemory memory) {
        this.name = name;
        this.maxSteps = Math.max(1, maxSteps);
        this.loopGuard = loopGuard;
        this.memory = memory;
    }

    public String getName() {
        return name;
    }

    /**
     * 阻塞式跑一次：把事件流跑完，然后看结果。
     *
     * <p>事件被丢掉了——阻塞式调用方要的是最终答复，中间过程在日志里。
     * 想看中间过程就用 {@link #runStream}。
     *
     * @param userId 发起这次 run 的用户 id。它和 {@code chatId} 都是不透明的字符串，
     *               <b>写反了能编译通过</b>，而表现是用户画像被存到了会话 id 上——
     *               所有调用点都要保持 {@code (chatId, userId, ...)} 这个顺序
     */
    public AgentResult runBlocking(String chatId, String userId, String input) {
        AgentRun run = newRun(chatId, userId, input);
        execute(run).then().block();
        AgentResult result = run.toResult();
        log.info("[{}] 会话 {} 跑完：state={} 共 {} 步，循环命中 {} 次，答复={}",
                name, chatId, result.state(), result.steps(), result.loopHits(), abbreviate(result.output()));
        return result;
    }

    /**
     * 流式跑一次：返回的就是整个过程的事件流，订阅它才会真正开跑。
     *
     * <p>模型调用也因此走流式（见 {@code ToolCallAgent#think}）——否则「流式」只是把一次
     * 算完的结果分段发出去，用户该等多久还是等多久。
     */
    public Flux<AgentEvent> runStream(String chatId, String userId, String input) {
        AgentRun run = newRun(chatId, userId, input);
        run.setStreaming(true);
        return execute(run)
                // 整个循环挪到弹性线程池上跑，而不是留在订阅它的那个线程上。
                // 订阅发生在 Netty 的事件循环线程上，而这套链路里有多处阻塞调用：
                // step() 内部的 ToolCallingManager.executeToolCalls 是同步的（读写文件、
                // 抓网页、MCP 的 sync client 都是阻塞 IO），开场预检索还要发两次远程请求。
                // 不挪的话，一个会话的检索会同时卡住这台机器上其它所有请求——
                // 而这个毛病在本地单人点的时候完全看不出来。
                // boundedElastic 的线程数是 CPU 核数×10，用满之后排队而不是拒绝；
                // 真正的并发上限由 PurifyManus 的 runningConversations 闸门兜着。
                .subscribeOn(Schedulers.boundedElastic())
                .doOnComplete(() -> log.info("[{}] 会话 {} 流式结束：state={} 共 {} 步，循环命中 {} 次",
                        name, chatId, run.state(), run.stepCount(), run.loopHits()))
                // 客户端断开（浏览器关页面、curl 被 Ctrl-C）会走到这里：记忆在每步之后已经存过了，
                // 这里只留一行日志，好知道「这次没跑完」是用户走了还是真的挂了
                .doOnCancel(() -> log.info("[{}] 会话 {} 流式被取消（客户端断开？），停在 {} 步",
                        name, chatId, run.stepCount()));
    }

    /**
     * 走一步：模型的思考 + 行动。
     *
     * <p>实现要做到三件事：把这一步的消息（模型的话、工具的回答）追加进 {@code run}、
     * 用 {@code run.record(...)} 留下可被检测器读取的记录、在该收尾时调用
     * {@code run.finish(...)}。
     */
    protected abstract Flux<AgentEvent> step(AgentRun run);

    /**
     * 开一次 run：取历史，如果上次是问到一半停的，把那个问题一并交给模型。
     *
     * <p>为什么要在这里补一句：用户回的那句话本身是「光秃秃」的——「换成查上海」。
     * 模型自己调 askHuman 时，问题在工具调用的参数里，历史里看得到；
     * 但看门狗升级出来的提问（{@code AskUserLoopHandler}）不是模型说的，历史里没有。
     * 补上这段开场白之后，两条来路在模型眼里就一样了。
     */
    private AgentRun newRun(String chatId, String userId, String input) {
        String question = memory.takePendingQuestion(chatId);
        return AgentRun.start(chatId, userId, withQuestion(input, question), memory.get(chatId));
    }

    private static String withQuestion(String input, String question) {
        if (question == null || question.isBlank()) {
            return input;
        }
        return "（你上一轮向用户提了这个问题：「" + question + "」，下面是用户的回答）\n" + input;
    }

    /**
     * 循环本体。每次调用推进「一步 + 一步之后的检查」，直到 run 进入终态。
     *
     * <p>用 {@code Flux.defer} 递归而不是 {@code while}：每一步的事件必须等上一步跑完才能产生，
     * defer 保证了「订阅时才构造下一步」，同时不会像 while 那样把整个流程压成一个不可中断的整体——
     * 客户端一断开，正在跑的那次模型调用之后的步骤就不会再被订阅了。
     */
    private Flux<AgentEvent> execute(AgentRun run) {
        return Flux.defer(() -> prologue(run))
                .concatWith(Flux.defer(() -> loop(run)));
    }

    /**
     * 开场：一次 run 只跑一次的准备动作。默认什么都不做。
     *
     * <p><b>为什么需要这个钩子</b>：{@link #step} 一次 run 会被调用多次，而「开场预检索」
     * 这类动作属于这一轮对话的入口，跑第二次就是白花一次远程调用（重排模型按次计费）。
     * 写成「在 step 里判断是不是第一步」也能实现，但那样这个约束就散在每个实现方的脑子里，
     * 放在这里则由循环本身保证——想违反都违反不了。
     *
     * <p>返回事件流而不是 void，是为了和 {@link #step} 同一个形状：做了什么、
     * 要不要让用户看见，都在流里说清楚。
     *
     * <p><b>实现里要收尾就调 {@code run.finish(...)}，不要在这里另发终态事件。</b>
     * 紧接着的 {@code loop} 会在开头看到终态、统一发一条出去；两处都发，
     * 用户会连着收到两条一模一样的说明（同 {@code ToolCallAgent#think} 的注释）。
     *
     * <p>本方法体允许阻塞：流式那条路由 {@link #runStream} 的 subscribeOn 保证
     * 它不会占住事件循环线程。
     */
    protected Flux<AgentEvent> prologue(AgentRun run) {
        return Flux.empty();
    }

    /** 循环本体：反复「走一步 + 一步之后的检查」，直到 run 进入终态。 */
    private Flux<AgentEvent> loop(AgentRun run) {
        return Flux.defer(() -> {
            if (run.isTerminal()) {
                AgentEvent terminal = AgentEvent.terminal(run.state(), run.output(), run.question());
                log.info("[{}] 会话 {} 收尾：{}（{} 步）", name, run.chatId(), run.state(), run.stepCount());
                return Flux.just(terminal);
            }
            return Flux.just(AgentEvent.step(run.nextStepIndex()))
                    .concatWith(step(run))
                    .concatWith(Flux.defer(() -> advance(run)))
                    .concatWith(Flux.defer(() -> loop(run)));
        });
    }

    /**
     * 一步之后的三件事：落记忆 → 看门狗 → 步数预算。
     *
     * @return 这个过程本身要抛出去的事件（看门狗发声、预算用完），正常情况为空
     */
    private Flux<AgentEvent> advance(AgentRun run) {
        // 先落记忆再看门：即使下一步就出事，这一步的成果也已经存下来了。
        // 放在「每步之后」而不是「整次 run 之后」，是为了让客户端断开、进程被杀这类中断
        // 也不会丢掉已经跑过的上下文
        memory.save(run.chatId(), run.messages());

        // 模型自己调了 askHuman（或者子类用别的方式要求暂停）：它已经问出口了，本轮到此为止
        if (run.isTerminal()) {
            return Flux.empty();
        }
        if (run.interrupt().hasPending()) {
            run.finish(AgentState.WAITING_FOR_USER, run.lastAssistantText());
            rememberPendingQuestion(run);
            log.info("[{}] 会话 {} 暂停等用户回答：{}", name, run.chatId(), run.question());
            return Flux.empty();
        }

        Optional<LoopAction> action = loopGuard.inspect(run);
        if (action.isPresent()) {
            return apply(run, action.get());
        }

        if (run.stepCount() >= maxSteps) {
            // 看门狗没吭声不代表没卡住——判据终究是有限条，而且模型完全可能每步都换着花样
            // 绕圈子。这是最后一道闸：跑到预算就收工，不留「无限循环」这个可能
            String message = BUDGET_MESSAGE.formatted(maxSteps);
            run.finish(AgentState.ABORTED, message);
            log.warn("[{}] 会话 {} 用完 {} 步预算，收工", name, run.chatId(), maxSteps);
            return Flux.just(AgentEvent.loopSignal(message));
        }
        return Flux.empty();
    }

    /**
     * 执行处置决定。
     *
     * <p>三个分支里只有「继续」不是终态；另外两个都会让 run 收尾，循环在下一轮开头
     * 把它转成一个终态事件发出去。事件本身在这里先发一条：用户该看到「看门狗介入了」，
     * 只看到最后的问句或中止说明，会以为是模型自己决定不干了。
     */
    private Flux<AgentEvent> apply(AgentRun run, LoopAction action) {
        AgentEvent event = AgentEvent.loopSignal(action.message());
        switch (action.outcome()) {
            case CONTINUE -> run.addHint(action.message());
            case WAITING_FOR_USER -> {
                run.interrupt().ask(action.message());
                run.finish(AgentState.WAITING_FOR_USER, run.lastAssistantText());
            }
            case ABORT -> run.finish(AgentState.ABORTED, action.message());
        }
        // 「问用户」这一档的问题不是模型说的，消息列表里找不到，必须单独记一份。
        // 漏掉这一句的后果很隐蔽：用户回答时模型只看到一句没头没尾的话，
        // 而它对此毫无察觉（这个分支只有看门狗会走到，所以两条来路都得记）
        rememberPendingQuestion(run);
        return Flux.just(event);
    }

    /**
     * 暂停等用户时，把问题单独记一份，供下一次 run 开场用。
     *
     * <p>为什么不能只靠消息列表：模型自己调 askHuman 时，问题在工具调用的参数里，历史里天然留着；
     * 而看门狗升级出来的提问没有对应的工具调用。两条来路都记一次，续跑的逻辑就不用区分它们。
     */
    private void rememberPendingQuestion(AgentRun run) {
        if (run.state() == AgentState.WAITING_FOR_USER) {
            memory.rememberQuestion(run.chatId(), run.question());
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
