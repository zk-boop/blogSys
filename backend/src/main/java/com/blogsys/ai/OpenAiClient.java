package com.blogsys.ai;

import com.blogsys.common.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** OpenAI 兼容 Chat Completions 流式客户端:内容增量实时回调,工具调用跨帧聚合。 */
@Component
@RequiredArgsConstructor
public class OpenAiClient {

    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    /** 流式对话(可带工具)。流结束后回调 onToolCalls 或 onFinish 之一。 */
    public void chatStream(List<ChatMessage> messages, List<ToolDefinition> tools,
                           ChatStreamListener listener) {
        String payload = buildPayload(messages, tools);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = httpClient().send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                throw new BizException(502, "AI 服务错误: HTTP " + response.statusCode());
            }
            StreamAggregator aggregator = new StreamAggregator(listener);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (aggregator.isFinished()) {
                        break;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) {
                        continue;
                    }
                    aggregator.accept(parseJson(data));
                }
                aggregator.complete();
            }
        } catch (IOException e) {
            throw new BizException(502, "AI 流式连接中断");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(502, "AI 请求被中断");
        }
    }

    /** 解析增量帧:content 直接转发;tool_calls 按 index 聚合;finish_reason 后结束。 */
    private final class StreamAggregator {

        private final ChatStreamListener listener;
        private final Map<Integer, String> callIds = new HashMap<>();
        private final Map<Integer, String> callNames = new HashMap<>();
        private final Map<Integer, StringBuilder> callArgs = new LinkedHashMap<>();
        private boolean finished;

        private StreamAggregator(ChatStreamListener listener) {
            this.listener = listener;
        }

        private boolean isFinished() {
            return finished;
        }

        private void accept(JsonNode frame) {
            JsonNode choice = frame.path("choices").path(0);
            if (choice.isMissingNode()) {
                return;
            }
            JsonNode delta = choice.path("delta");
            JsonNode content = delta.path("content");
            if (content.isTextual()) {
                listener.onContent(content.asText());
            }
            delta.path("tool_calls").forEach(call -> {
                int index = call.path("index").asInt(0);
                String id = call.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    callIds.put(index, id);
                }
                String name = call.path("function").path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    callNames.put(index, name);
                }
                String args = call.path("function").path("arguments").asText(null);
                if (args != null && !args.isBlank()) {
                    callArgs.computeIfAbsent(index, k -> new StringBuilder()).append(args);
                }
            });
            String finishReason = choice.path("finish_reason").asText("");
            if (!finishReason.isEmpty()) {
                finished = true;
            }
        }

        private void complete() {
            if (finished && !callArgs.isEmpty()) {
                List<ChatMessage.ToolCall> calls = new ArrayList<>();
                for (Map.Entry<Integer, StringBuilder> entry : callArgs.entrySet()) {
                    int index = entry.getKey();
                    calls.add(new ChatMessage.ToolCall(
                            callIds.getOrDefault(index, "call-" + index),
                            "function",
                            new ChatMessage.ToolFunction(
                                    callNames.getOrDefault(index, ""),
                                    entry.getValue().toString())));
                }
                listener.onToolCalls(calls);
            } else {
                listener.onFinish();
            }
        }
    }

    private String buildPayload(List<ChatMessage> messages, List<ToolDefinition> tools) {
        try {
            var root = objectMapper.createObjectNode()
                    .put("model", properties.getModel())
                    .put("stream", true)
                    .put("temperature", 0.7);
            root.set("messages", objectMapper.valueToTree(messages));
            if (tools != null && !tools.isEmpty()) {
                root.set("tools", objectMapper.valueToTree(tools));
                root.put("tool_choice", "auto");
            }
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AI 请求构造失败", e);
        }
    }

    private JsonNode parseJson(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new BizException(502, "AI 服务响应解析失败");
        }
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
