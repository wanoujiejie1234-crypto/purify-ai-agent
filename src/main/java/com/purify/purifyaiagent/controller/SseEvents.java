package com.purify.purifyaiagent.controller;

import com.purify.purifyaiagent.agent.AgentEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 两条链路的流式响应共用同一套封装。
 *
 * <p>前端只写一套解析代码的前提，就是两边的帧结构完全一样——所以这件事必须只有一份实现。
 * 分开写在两个 controller 里，迟早会有一边改了而另一边没改，而契约漂移是很难在联调时看出来的：
 * 前端只会觉得「智能体那边偶尔解析不出来」。
 *
 * <p>事件名用 {@code type} 的名字（{@code TEXT}、{@code TOOL_CALL}……），{@code data} 是事件的 JSON。
 */
final class SseEvents {

    /** 会话 ID 的响应头名。SSE 的每条 data 都是事件正文，元信息只能走响应头。 */
    static final String CHAT_ID_HEADER = "X-Chat-Id";

    static ResponseEntity<Flux<ServerSentEvent<AgentEvent>>> response(String chatId, Flux<AgentEvent> events) {
        // 先固定到 ServerSentEvent<AgentEvent> 再往下传：在 lambda 里直接拼 Builder 的话，
        // 编译器定不下它的类型参数，报出来是一句很难看懂的「推断类型不符」
        Flux<ServerSentEvent<AgentEvent>> stream = events.map(SseEvents::toServerSentEvent);

        return ResponseEntity.ok()
                .header(CHAT_ID_HEADER, chatId)
                // 关掉中间环节的缓冲。SSE 被 nginx 之类的反向代理攒着一起发时，前端会
                // 「卡住半天，然后一瞬间全冒出来」，看起来和阻塞式没有任何区别。
                // 本地直连时这两个头没有作用，留着是为了部署到代理后面时不至于踩这个坑。
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    private static ServerSentEvent<AgentEvent> toServerSentEvent(AgentEvent event) {
        return ServerSentEvent.<AgentEvent>builder(event)
                .event(event.type().name())
                .build();
    }

    private SseEvents() {
    }
}
