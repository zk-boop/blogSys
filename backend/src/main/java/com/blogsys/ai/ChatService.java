package com.blogsys.ai;

import com.blogsys.ai.tool.AgentTool;
import com.blogsys.ai.tool.ToolRegistry;
import com.blogsys.common.BizException;
import com.blogsys.visibility.Viewer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final int MAX_TOOL_ROUNDS = 4;
    private static final int MAX_HISTORY = 20;
    private static final int TOOL_RESULT_LIMIT = 4000;
    private static final String SYSTEM_PROMPT = """
            你是 blogSys 多人博客平台的内置 AI 助手,负责回答与平台内容相关的问题。
            你可以调用工具获取数据,工具返回 JSON 后请用自然语言总结回答,不要复述原始 JSON。
            回答使用 Markdown 格式,保持简洁。涉及文章时给出标题和链接(格式: /article/ID)。
            仅当需要查询站内数据时才调用工具;闲聊、提问直接回答。不要执行任何写操作。""";

    private final OpenAiClient openAiClient;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    /**
     * 跑一场对话。
     *
     * <p>{@code viewer} 是<b>提问者本人</b>,由 controller 在请求线程上解析后传进来。
     * 这场对话整个跑在 {@code chatExecutor} 的池线程上 —— 那里没有请求上下文,
     * 所以「谁在问」必须是被接受的依赖,而不是到环境里去猜。它同时决定两件事:
     * 哪些工具出现在发给模型的清单里,以及某次调用是否真的被执行。
     */
    public void chat(List<ChatMessage> incoming, SseWriter writer, Viewer viewer) {
        if (!properties.isEnabled()) {
            sendError(writer, "AI 服务未配置:请在环境变量中设置 AI_API_KEY(OpenAI 兼容 API Key)");
            return;
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", SYSTEM_PROMPT, null, null));
        int start = Math.max(0, incoming.size() - MAX_HISTORY);
        messages.addAll(incoming.subList(start, incoming.size()));

        try {
            boolean hasToolCalls = runToolLoop(messages, writer, viewer);
            if (hasToolCalls) {
                sendMessage(writer, "抱歉,处理你的请求时步骤过多,请缩小问题范围后重试。");
            }
            writer.event("done", "{}");
        } catch (BizException e) {
            sendError(writer, e.getMessage());
        } catch (Exception e) {
            log.error("AI chat failed", e);
            sendError(writer, "AI 服务异常,请稍后重试");
        }
    }

    /** 流式工具循环:内容增量实时转发;返回是否有未消费的工具调用(轮数耗尽)。 */
    private boolean runToolLoop(List<ChatMessage> messages, SseWriter writer, Viewer viewer) throws Exception {
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            List<ChatMessage.ToolCall>[] toolCalls = new List[1];
            openAiClient.chatStream(messages, toolRegistry.definitions(viewer), new ChatStreamListener() {
                @Override
                public void onContent(String delta) {
                    try {
                        sendMessage(writer, delta);
                    } catch (Exception e) {
                        throw new StreamAbortedException(e);
                    }
                }

                @Override
                public void onToolCalls(List<ChatMessage.ToolCall> calls) {
                    toolCalls[0] = calls;
                }

                @Override
                public void onFinish() {
                    // 无工具调用,本轮结束
                }
            });
            if (toolCalls[0] == null) {
                return false;
            }
            messages.add(ChatMessage.assistant(null, toolCalls[0]));
            for (ChatMessage.ToolCall call : toolCalls[0]) {
                String result = executeTool(call, writer, viewer);
                messages.add(ChatMessage.toolResult(call.id(), call.functionName(), result));
            }
        }
        return true;
    }

    private String executeTool(ChatMessage.ToolCall call, SseWriter writer, Viewer viewer) throws Exception {
        String name = call.functionName();
        AgentTool tool = toolRegistry.get(name);
        // 「不存在」与「对你不存在」必须给出**逐字相同**的答复 —— 两处稍有差别就是一个
        // 存在性预言机,模型会从措辞里读出「这儿有个你不能用的能力」,然后反复重试。
        //
        // definitions(viewer) 已经把不可用的工具从清单里摘掉了,但模型可以凭空说出一个
        // 它没被给过的名字。所以强制在这里做第二次判定:那一处是 UX,这一处才是边界。
        if (tool == null || !tool.isAvailableTo(viewer)) {
            return "{\"error\":\"未知工具: " + name + "\"}";
        }
        Map<String, Object> args = parseArgs(call);
        sendToolEvent(writer, name, args, null);
        String result;
        try {
            result = tool.execute(args);
        } catch (Exception e) {
            log.warn("tool {} failed: {}", name, e.getMessage());
            result = "{\"error\":\"" + (e.getMessage() == null ? "工具执行失败" : e.getMessage()) + "\"}";
        }
        if (result.length() > TOOL_RESULT_LIMIT) {
            result = result.substring(0, TOOL_RESULT_LIMIT) + "…(已截断)";
        }
        sendToolEvent(writer, name, args, result);
        return result;
    }

    private Map<String, Object> parseArgs(ChatMessage.ToolCall call) {
        String arguments = call.function() == null ? "" : call.function().arguments();
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void sendToolEvent(SseWriter writer, String name, Map<String, Object> args, String result) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", name);
        node.set("args", objectMapper.valueToTree(args));
        if (result != null) {
            node.put("result", result);
        }
        writer.event("tool", node.toString());
    }

    private void sendMessage(SseWriter writer, String content) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("content", content);
        writer.event("message", node.toString());
    }

    private void sendError(SseWriter writer, String message) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("message", message);
            writer.event("error", node.toString());
        } catch (Exception ignored) {
            // 客户端已断开
        }
    }

    /** 客户端断开时中断流式读取的内部信号。 */
    private static final class StreamAbortedException extends RuntimeException {

        private StreamAbortedException(Throwable cause) {
            super(cause);
        }
    }
}
