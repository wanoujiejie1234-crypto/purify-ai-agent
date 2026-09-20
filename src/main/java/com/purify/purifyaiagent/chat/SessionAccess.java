package com.purify.purifyaiagent.chat;

import com.purify.purifyaiagent.exception.ApiException;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * 会话归属的判定。三条对话链路（轻语对话、轻语看图、PurifyManus）共用这一份。
 *
 * <p><b>为什么必须抽出来</b>：这个判断以前完全不存在——拿到一个 chatId 就能读别人的历史，
 * 也能往别人的会话里发消息。补的时候如果有三个地方各写一遍，迟早会有一处漏掉或写歪，
 * 而漏掉的那一处不会报任何错，只是「某个接口还能看到别人的数据」。
 * 收在一个类里，这三条链路的口径就由编译器保证一致。
 *
 * <h3>两种判定，区别只在「会话还不存在」时怎么办</h3>
 *
 * <p>{@link #requireCanWrite}（给发消息用）和 {@link #requireOwned}（给读历史用）
 * 对「这个会话压根不存在」的处理<b>是相反的</b>，这不是疏漏：
 * <ul>
 *   <li>发消息：会话不存在是<b>正常</b>的——客户端生成了一个新的 chatId，
 *       这一轮就是它的第一句话。允许，随后 {@code touch} 会把它建出来；</li>
 *   <li>读历史：会话不存在意味着<b>没有任何东西可读</b>。返回空数组（旧行为）
 *       会让「伪造的 chatId」和「真实但没有记录的 chatId」不可区分，
 *       所以统一报 404。</li>
 * </ul>
 *
 * <h3>「别人的」和「不存在的」必须是同一个响应</h3>
 *
 * <p>两者都返回 404，用的是同一句话、同一个响应体。分开的话，这个接口就成了一个
 * 存在性探测器：攻击者能靠状态码的差异问出「这个 chatId 是不是真的存在」。
 * 这和 {@code ChatSessionRepository#rename} 已有的口径一致——那里也是
 * 「影响行数为 0 等价于不存在，不额外报错」。
 *
 * <p>注意这条口径有一个无法消除的残余：{@link #requireCanWrite} 必须允许「不存在」，
 * 所以发消息时「别人的会话」会 404 而「不存在的」会正常进行。要消掉它就得放弃
 * 客户端自己生成 chatId 这个能力，不值得。
 */
@Slf4j
public final class SessionAccess {

    /** 归属不符或不存在的统一说辞。两处引用同一个常量，免得有人只改了一边。 */
    private static final String NOT_FOUND_MESSAGE = "会话不存在或已被删除";

    private SessionAccess() {
    }

    /**
     * 发消息前的判定：会话要么是我的，要么还不存在（那就要新建它）。
     *
     * <p>不查这一步的后果比「看到别人的历史」更重：{@code touch} 用的是
     * {@code ON DUPLICATE KEY UPDATE} 且只更新 {@code updated_at}，
     * 所以别人的会话<b>不会</b>被劫持过去——但这一轮对话会写进<b>那个会话的记忆里</b>，
     * 而记忆是模型下一轮要看的东西。也就是说攻击者能往别人的对话里注入内容，
     * 让那个用户在后续提问时读到。智能体那条链路更直接：它能打断或插入别人正在跑的 run。
     *
     * @throws ApiException 会话存在但不属于这个用户
     */
    public static void requireCanWrite(ChatSessionRepository repository, String conversationId, String userId) {
        Optional<String> owner = repository.ownerOf(conversationId);
        if (owner.isEmpty()) {
            // 新会话，交给 touch 去建。这是绝大多数请求走的那条路
            return;
        }
        if (!owner.get().equals(userId)) {
            log.warn("[会话] 拒绝写入不属于自己的会话：conversationId={} 归属={} 请求方={}",
                    conversationId, owner.get(), userId);
            throw ApiException.notFound(NOT_FOUND_MESSAGE);
        }
    }

    /**
     * 读历史前的判定：会话必须存在<b>且</b>是我的。
     *
     * <p>「不存在」也返回 404，和「不是我的」保持同一个响应——理由见类注释。
     * 前端要为这个改动配合一下：新会话不要去拉历史（那里必然 404），
     * 而从 sessionStorage 恢复出来的旧 chatId 真 404 时，要给一条提示并开一个新会话。
     *
     * @throws ApiException 会话不存在，或存在但不属于这个用户
     */
    public static void requireOwned(ChatSessionRepository repository, String conversationId, String userId) {
        Optional<String> owner = repository.ownerOf(conversationId);
        if (owner.isEmpty()) {
            log.debug("[会话] 读取了一个不存在的会话的历史：conversationId={}", conversationId);
            throw ApiException.notFound(NOT_FOUND_MESSAGE);
        }
        if (!owner.get().equals(userId)) {
            log.warn("[会话] 拒绝读取不属于自己的会话：conversationId={} 归属={} 请求方={}",
                    conversationId, owner.get(), userId);
            throw ApiException.notFound(NOT_FOUND_MESSAGE);
        }
    }
}
