package com.purify.purifyaiagent.resource;

import java.time.LocalDateTime;

/**
 * 资料库里的一条产出记录。
 *
 * <p>{@code url} 和 {@code storageKey} 是两件事，别混：
 * <ul>
 *   <li>{@code url} 是**给用户点的地址**。{@link ResourceKind#WRITTEN} 那种本地文件没有，
 *       所以它可以是 {@code null}；</li>
 *   <li>{@code storageKey} 是**服务端定位文件用的**。对 PDF 是 OSS 的 objectKey，
 *       对另外两种是本地文件名（不含目录——目录由 kind 决定）。</li>
 * </ul>
 * 用 storageKey 而不是从 url 里反推文件名：url 会被编码、会变成别的域名、
 * PDF 的 url 甚至是拼出来的，从里面解析是脆的。
 */
public record UserResource(
        Long id,
        String userId,
        String conversationId,
        ResourceKind kind,
        String title,
        String url,
        String storageKey,
        Long sizeBytes,
        String mimeType,
        String sourceUrl,
        LocalDateTime createdAt) {

    /**
     * 新建一条（还没入库，所以没有 id）。
     *
     * <p>{@code createdAt} 也由调用方给：{@code ResourceRecorder} 用写库那一刻的时间，
     * 测试里则要能指定一个固定值。
     */
    public static UserResource create(String userId, String conversationId, ResourceKind kind,
                                      String title, String url, String storageKey,
                                      Long sizeBytes, String mimeType, String sourceUrl,
                                      LocalDateTime createdAt) {
        return new UserResource(null, userId, conversationId, kind, title, url,
                storageKey, sizeBytes, mimeType, sourceUrl, createdAt);
    }

    /**
     * 能不能直接给用户一个可点的地址。
     *
     * <p>{@code url} 存在就能——三种产出里只有 {@link ResourceKind#WRITTEN} 会是 null。
     * 前端据此决定是「一个普通链接」还是「走后端下载接口」，
     * 所以这个判断放在这里定一次，不要在界面和接口里各判各的。
     */
    public boolean hasDirectUrl() {
        return url != null && !url.isBlank();
    }
}
