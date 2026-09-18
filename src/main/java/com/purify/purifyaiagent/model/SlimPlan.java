package com.purify.purifyaiagent.model;

import java.util.List;

/**
 * 个性化瘦身计划，由模型直接输出结构化对象。
 *
 * <p>这里用 record + {@code ChatClient...entity(SlimPlan.class)}，
 * Spring AI 会自动把字段结构转成 JSON Schema 附加到提示词里，
 * 再把模型返回的 JSON 反序列化成对象，省掉手写解析。
 */
public record SlimPlan(
        String summary,
        List<String> dietAdvice,
        List<String> exerciseAdvice,
        List<String> warnings
) {
}
