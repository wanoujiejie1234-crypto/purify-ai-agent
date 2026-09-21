package com.purify.purifyaiagent.model;

import com.purify.purifyaiagent.i18n.Messages;
import com.purify.purifyaiagent.resource.UserResource;

import java.time.LocalDateTime;

/**
 * 资料库里一条记录的对外结构。
 *
 * <p>为什么不直接返回 {@link UserResource}：那个 record 带着 {@code storageKey}——
 * 它是服务端定位文件用的内部键（OSS 的 objectKey 或本地文件名）。
 * 它没有密钥那么敏感，但也没有任何理由发给浏览器：一旦发出去，
 * 前端的每一条日志、每一次错误上报里都会带上它。
 * 中间隔一层显式的投影，漏字段的表现是「前端少了个东西」而不是「内部路径被写进了日志」。
 *
 * <p>{@code kindLabel} 由后端给（{@link com.purify.purifyaiagent.resource.ResourceKind#getLabelKey()}），
 * 前端不再维护一份枚举名到文案的映射——那种映射迟早会和后端的枚举对不上，
 * 而漏一项的表现是界面上直接露出 {@code WRITTEN} 这种内部名。
 *
 * <p>它也是<b>翻译好的</b>：{@link #from} 收一份 {@code Messages}，键在那里被翻成
 * 当前请求语言下的话。代价是列表里那个标签跟着语言走的时机是「下一次拉列表」，
 * 而不是切换语言的那一刻——这和从后端来的其它散文是同一个取舍。
 */
public record ResourceView(
        Long id,
        String kind,
        String kindLabel,
        String title,
        String url,
        Long sizeBytes,
        String mimeType,
        String sourceUrl,
        String conversationId,
        LocalDateTime createdAt) {

    /**
     * @param resource 领域对象
     * @param messages 当前请求语言的文案出口。由 controller 从 {@code MessageResolver} 取，
     *                 因为只有那一层同时知道「现在是什么语言」
     */
    public static ResourceView from(UserResource resource, Messages messages) {
        return new ResourceView(
                resource.id(),
                resource.kind() == null ? null : resource.kind().name(),
                resource.kind() == null ? null : messages.get(resource.kind().getLabelKey()),
                resource.title(),
                resource.url(),
                resource.sizeBytes(),
                resource.mimeType(),
                resource.sourceUrl(),
                resource.conversationId(),
                resource.createdAt());
    }
}
