package com.blogsys.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SSE 线格式的唯一归属:事件名、载荷形状、组帧。
 *
 * <p>它是纯的 —— 不认识 servlet、不写字节,因此「行为进、SSE 文本出」这件事可以直接
 * 断言:{@code SseProtocolTest} 不必 mock 一个 HttpServletResponse。
 *
 * <p>此前这三样散在四个地方:{@code ChatService} 拼载荷、{@code SseWriterHttp} 组帧、
 * 测试的 CapturingWriter 再解析回来、{@code frontend/src/api/ai.js} 的 dispatch 再来一遍。
 * 现在前两个归这里,第四个与它对齐由 {@code docs/api.md} 那一张表负责 ——
 * 那张表是跨语言的共享契约,而契约得有个单一出处才叫契约。
 */
@Component
public class SseProtocol {

    /** 助手回答的增量。载荷 {@code {content}}。 */
    public static final String MESSAGE = "message";
    /** 工具调用。载荷 {@code {name, args, result?}} —— 前后各发一次,无 {@code result} 表示开始。 */
    public static final String TOOL = "tool";
    /** 失败。载荷 {@code {message}}。 */
    public static final String ERROR = "error";
    /** 正常收尾。载荷 {@code {}}。 */
    public static final String DONE = "done";

    private final ObjectMapper objectMapper;

    public SseProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String delta(String delta) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("content", delta);
        return frame(MESSAGE, node.toString());
    }

    public String toolStarted(String name, Map<String, Object> args) {
        return tool(name, args, null);
    }

    public String toolFinished(String name, Map<String, Object> args, String result) {
        return tool(name, args, result);
    }

    public String failed(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("message", message);
        return frame(ERROR, node.toString());
    }

    public String done() {
        return frame(DONE, "{}");
    }

    /** SSE 组帧:一行 {@code event}、一行 {@code data}、一个空行。 */
    public String frame(String event, String dataJson) {
        return "event:" + event + "\ndata:" + dataJson + "\n\n";
    }

    private String tool(String name, Map<String, Object> args, String result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", name);
        node.set("args", objectMapper.valueToTree(args));
        if (result != null) {
            node.put("result", result);
        }
        return frame(TOOL, node.toString());
    }
}
