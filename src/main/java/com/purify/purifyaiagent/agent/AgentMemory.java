package com.purify.purifyaiagent.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体的会话记忆：按会话 ID 记住这个会话说过什么，进程内保存。
 *
 * <p><b>为什么不复用项目里那个 {@code ChatMemory} Bean。</b>那条链路底下是
 * {@code JdbcChatMemoryRepository}，它入库时只存 {@code message.getText()} 和消息类型，
 * 读回来的时候 {@code ASSISTANT} 变成 {@code new AssistantMessage(content)}——<b>工具调用丢光</b>，
 * {@code TOOL} 变成 {@code new ToolResponseMessage(List.of())}——<b>工具返回也丢光</b>。
 * 普通一问一答看不出问题（那些消息本来就只有文本），但 ReAct 的历史一旦这样往返一次，
 * 就只剩一条没有对应的工具回答的 assistant 消息，模型下一次调用直接报错。
 * 所以智能体必须自己记住「谁调了什么、拿回了什么」，这里扮演的就是 OpenManus 里 {@code Memory} 的角色。
 *
 * <p>代价是重启就没了，这是清楚的取舍，不是遗漏。真要做持久化，得把工具调用序列化成可还原的
 * 结构（而不是存成文本）——那是另一件事，不在这次的范围里。
 *
 * <p>除了消息，它还记得「这个会话上次是问到一半停的」——那句问题单独存一份，
 * 因为看门狗升级出来的提问不是模型说的，消息列表里没有它的位置（见 {@link #rememberQuestion}）。
 *
 * <p><b>裁剪按「整轮」而不是按「条」。</b>窗口满了要从最早的丢起，但一次工具调用是
 * 「assistant 说要调 → tool 把结果回给它」两条消息，只丢前一条会留下一条没有出处的工具记录。
 * 所以裁剪以 {@link UserMessage} 为界：一次用户提问连同它引发的所有后续消息算一轮，
 * 要么整轮留着，要么整轮丢掉。
 */
@Slf4j
public class AgentMemory {

    /** 每个会话最多留多少条消息。四个会话乘这个数，内存占用可以忽略。 */
    private static final int DEFAULT_WINDOW = 40;

    private final Map<String, List<Message>> conversations = new ConcurrentHashMap<>();
    private final Map<String, String> pendingQuestions = new ConcurrentHashMap<>();
    private final int window;

    public AgentMemory() {
        this(DEFAULT_WINDOW);
    }

    public AgentMemory(int window) {
        this.window = Math.max(2, window);
    }

    /**
     * 取某个会话的历史。
     *
     * <p>返回的是一份拷贝：调用方（{@code AgentRun}）会往上面追加消息，
     * 让它直接改这份内部列表，就等于让一次跑到一半的 run 改写了记忆里已经存好的状态。
     */
    public List<Message> get(String chatId) {
        return List.copyOf(conversations.getOrDefault(chatId, List.of()));
    }

    /**
     * 用工作列表覆盖某个会话的记忆，顺便裁剪到窗口大小。
     *
     * <p>覆盖而不是追加：工作列表本来就来自历史 + 本次新增，是这份记忆的完整快照。
     * 追加的话，一次 run 里每步都保存一次就会把消息重复堆进去。
     */
    public void save(String chatId, List<Message> messages) {
        List<Message> pruned = prune(messages);
        conversations.put(chatId, pruned);
        if (pruned.size() < messages.size()) {
            log.debug("[AgentMemory] 会话 {} 记忆裁剪：{} → {} 条", chatId, messages.size(), pruned.size());
        }
    }

    public void clear(String chatId) {
        conversations.remove(chatId);
        pendingQuestions.remove(chatId);
    }

    /**
     * 记下「这次是问到一半停的」，等用户回答时要用。
     *
     * <p>为什么需要单独存一问：模型自己调 askHuman 时，问题在工具调用的参数里，
     * 历史本身就留着；但看门狗升级出来的提问（见 {@code AskUserLoopHandler}）不是模型说的，
     * 消息列表里没有它的位置。不记下来的话，用户回答时模型看不到自己被问过什么，
     * 只会看到一句没头没尾的回答。
     */
    public void rememberQuestion(String chatId, String question) {
        if (question != null && !question.isBlank()) {
            pendingQuestions.put(chatId, question);
        }
    }

    /**
     * 取走待答问题（取了就没了）。
     *
     * <p>「取走」而不是「读取」：它只对紧跟着的那一条用户消息有意义。
     * 留着的话，之后每一轮都会重复带上这段开场白。
     *
     * @return 没有待答问题时返回 null
     */
    public String takePendingQuestion(String chatId) {
        return pendingQuestions.remove(chatId);
    }

    public Set<String> conversationIds() {
        return Set.copyOf(conversations.keySet());
    }

    /**
     * 从最早的整轮开始丢，直到不超过窗口。
     *
     * <p>找不到下一个用户消息时停下（也就是只剩一轮了）：再丢就没有上下文了，
     * 留着这一轮虽然超了窗口，但至少模型还能看见用户问的是什么。
     * 一轮就超过整个窗口的情况只会出现在「用户贴了一大段材料」时，属于正常输入，不该报错。
     */
    private List<Message> prune(List<Message> messages) {
        List<Message> result = new ArrayList<>(messages);
        while (result.size() > window) {
            int nextTurn = nextUserMessageIndex(result);
            if (nextTurn <= 0) {
                break;
            }
            result.subList(0, nextTurn).clear();
        }
        return result;
    }

    /**
     * 从第 1 条开始找到下一个用户消息的下标；找不到返回 -1。
     *
     * <p>不看第 0 条：它是当前这条时间线所属那一轮的起点，丢到它身上就变成「丢掉整个历史」了。
     */
    private static int nextUserMessageIndex(List<Message> messages) {
        for (int i = 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                return i;
            }
        }
        return -1;
    }
}
