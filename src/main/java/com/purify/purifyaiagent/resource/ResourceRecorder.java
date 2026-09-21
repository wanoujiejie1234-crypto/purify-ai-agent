package com.purify.purifyaiagent.resource;

import com.purify.purifyaiagent.agent.tool.ToolContexts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDateTime;

/**
 * 把「工具产出了一份东西」记进资料库。
 *
 * <p>三个工具（生成 PDF、下载资源、写文件）在**成功之后**调它一次。
 * 埋点放在工具里而不是事后扫回答，是因为只有在这里才知道「这是什么文件、有多大、
 * 存在哪、从哪来的」——那些信息到了回答正文里就只剩一个 URL 了
 * （见 {@code user-resource-schema-mysql.sql} 的注释）。
 *
 * <p><b>它永远不会抛异常，也永远不会打断工具。</b>归档是附加动作，用户要的是那份文件；
 * 为了记不上一条账把他已经拿到的东西说成失败，是把主次搞反了
 * （同 {@code ChatRecordRepository#save} 的取向）。所以这里连「没有用户身份」也只是
 * 记一条 debug 就返回。
 *
 * <p><b>没有用户身份就不记。</b>{@code userId} 为空的唯一可能是装配漏了
 * （正常链路上聊天接口都要求登录），那种情况下记下来的行没有归属，
 * 谁都不该看到它。宁可丢一条，也不要造一条无主的记录。
 */
@Slf4j
public class ResourceRecorder {

    private final UserResourceRepository repository;

    public ResourceRecorder(UserResourceRepository repository) {
        this.repository = repository;
    }

    /**
     * 记一条产出。
     *
     * @param kind       怎么产出的
     * @param title      展示名，一般是文件名
     * @param url        可以直接打开的地址；本地工作目录里那些没有对外的地址，传 null
     * @param storageKey 服务端定位文件用的键（OSS objectKey 或本地文件名）
     * @param sizeBytes  字节数，拿不到传 null（不要传 0——那会被显示成「0 字节」，像坏掉了）
     */
    public void record(ToolContext toolContext, ResourceKind kind, String title,
                       String url, String storageKey, Long sizeBytes, String mimeType, String sourceUrl) {
        String userId = ToolContexts.userIdOf(toolContext);
        if (userId == null) {
            log.debug("[Resource] 工具上下文里没有用户标识，本次产出不入资料库：{}", title);
            return;
        }

        UserResource resource = UserResource.create(
                userId,
                // chatId 可能是空的（不是所有调用点都在会话里），那是允许的
                ToolContexts.chatIdOf(toolContext),
                kind, title, url, storageKey, sizeBytes, mimeType, sourceUrl,
                LocalDateTime.now());

        repository.insert(resource)
                .ifPresent(saved -> log.info("[Resource] 已归档：user={} kind={} title={} id={}",
                        userId, kind, title, saved.id()));
    }
}
