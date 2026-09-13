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
import java.util.List;

/**
 * OpenAI 兼容 Chat Completions 的<b>传输层</b>:组请求、发出去、把 {@code data:} 行读出来。
 *
 * <p>它不再解释帧的含义 —— 那件事归 {@link StreamReducer},于是它可以被离线测试。
 * 这里只保有两件真正属于传输的事:<b>线格式的切分</b>({@code data:} 前缀、{@code [DONE]}
 * 哨兵)与<b>HTTP 错误</b>。
 */
@Component
@RequiredArgsConstructor
public class OpenAiClient {

    private static final String DONE_SENTINEL = "[DONE]";

    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 全应用共用一个 HTTP 客户端。它线程安全、自带连接池。
     * 此前是每轮 {@code chatStream} 新建一个 —— 一次对话最多 4 轮,4 个客户端,无 keep-alive。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 流式对话(可带工具)。流结束后回调 onToolCalls 或 onFinish 之一。 */
    public void chatStream(List<ChatMessage> messages, List<ToolDefinition> tools,
                           ChatStreamListener listener) {
        String payload = ChatPayload.of(objectMapper, properties.getModel(), messages, tools);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                throw new BizException(502, "AI 服务错误: HTTP " + response.statusCode());
            }
            StreamReducer reducer = new StreamReducer(listener);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while (!reducer.isFinished() && (line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if (DONE_SENTINEL.equals(data)) {
                        reducer.markDone();
                        break;
                    }
                    reducer.accept(parseJson(data));
                }
                reducer.finish();
            }
        } catch (IOException e) {
            throw new BizException(502, "AI 流式连接中断");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(502, "AI 请求被中断");
        }
    }

    /** 一行 {@code data:} 的内容是 JSON。这是线格式的一部分,所以留在传输层。 */
    private JsonNode parseJson(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new BizException(502, "AI 服务响应解析失败");
        }
    }
}
