package com.purify.purifyaiagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Re-Reading（Re2）Advisor：把用户的问题原样再读一遍，并要求模型「重新阅读后再回答」。
 *
 * <p>这是论文 <i>Re-Reading Improves Reasoning in Large Language Models</i> 里
 * 提出的零样本技巧，不需要改模型、不需要加样本，只通过改写输入就能提升推理准确率，
 * 很适合用来演示「只改请求、不碰响应」这一类 Advisor 的写法。
 *
 * <p>实现要点：找到 Prompt 里最后一条 {@link UserMessage}，在其后追加一段
 * Re-Reading 指令，再用改写后的 Prompt 生成新的 {@link ChatClientRequest} 继续往下传递。
 * 因为 {@code MessageChatMemoryAdvisor} 的 order 更小（更靠外层），
 * 它记录进 MySQL 的仍然是用户原始输入，不会把这段指令污染进对话历史。
 */
@Slf4j
public class ReReadingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final String RE_READ_SUFFIX = """

            请重新阅读上面的问题，先理清其中的关键信息，再给出更准确的回答。
            """;

    private final int order;

    public ReReadingAdvisor() {
        this(AdvisorOrders.RE_READING);
    }

    public ReReadingAdvisor(int order) {
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(rewrite(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(rewrite(request));
    }

    private ChatClientRequest rewrite(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        List<Message> instructions = prompt.getInstructions();

        // 倒序找到最后一条用户消息；系统消息、历史消息都不动
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i) instanceof UserMessage userMessage) {
                List<Message> rewritten = new ArrayList<>(instructions);
                // 用 mutate() 而不是 new UserMessage(text)：后者构造出来的消息
                // media 是空列表、metadata 是空 Map，会把多模态消息里的图片直接抹掉，
                // 表现为模型收到文字却看不到图，回答「看不清」——不报错，很难查。
                // mutate() 会带上原有的 text / media / metadata，这里只改文本。
                rewritten.set(i, userMessage.mutate()
                        .text(userMessage.getText() + RE_READ_SUFFIX)
                        .build());
                log.debug("[ReReadingAdvisor] 已为第 {} 条消息追加 Re-Reading 指令（保留 {} 个媒体附件）",
                        i, userMessage.getMedia().size());
                return request.mutate()
                        .prompt(new Prompt(rewritten, prompt.getOptions()))
                        .build();
            }
        }

        log.debug("[ReReadingAdvisor] Prompt 中没有用户消息，跳过改写");
        return request;
    }

    @Override
    public String getName() {
        return "ReReadingAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }
}
