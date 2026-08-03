package com.blogsys.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** OpenAI 兼容格式的对话消息。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        String role,
        String content,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls) {

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, null, toolCalls);
    }

    public static ChatMessage toolResult(String toolCallId, String name, String content) {
        return new ChatMessage("tool", content, toolCallId, null);
    }

    public record ToolCall(String id, String type, ToolFunction function) {

        public ToolCall {
            if (type == null || type.isBlank()) {
                type = "function";
            }
        }

        public String functionName() {
            return function != null ? function.name() : null;
        }
    }

    public record ToolFunction(String name, String arguments) {
    }
}
