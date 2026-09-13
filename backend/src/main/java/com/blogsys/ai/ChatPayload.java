package com.blogsys.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 构造 OpenAI 兼容 Chat Completions 的请求体。
 *
 * <p>抽出来的理由只有一个:在这之前它长在 {@code OpenAiClient} 里,而那个类唯一能被
 * 触达的方式是一次真实 HTTP 调用 —— 于是「tools 为空时不该带 tools 字段」这种约定
 * 从来没有人断言过。这里它是个纯函数,可以直接喂数据、断言 JSON。
 */
final class ChatPayload {

    private ChatPayload() {
    }

    static String of(ObjectMapper mapper, String model, List<ChatMessage> messages,
                     List<ToolDefinition> tools) {
        try {
            var root = mapper.createObjectNode()
                    .put("model", model)
                    .put("stream", true)
                    .put("temperature", 0.7);
            root.set("messages", mapper.valueToTree(messages));
            if (tools != null && !tools.isEmpty()) {
                root.set("tools", mapper.valueToTree(tools));
                root.put("tool_choice", "auto");
            }
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AI 请求构造失败", e);
        }
    }
}
