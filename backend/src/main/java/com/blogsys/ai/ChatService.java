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
     *
     * <p>{@code egress} 是<b>行为层</b>的出口:这里只说发生了什么,不说它长什么样。
     */
    public void chat(List<ChatMessage> incoming, ChatEgress egress, Viewer viewer) {
        if (!properties.isEnabled()) {
            egress.failed("AI 服务未配置:请在环境变量中设置 AI_API_KEY(OpenAI 兼容 API Key)");
            return;
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", SYSTEM_PROMPT, null, null));
        int start = Math.max(0, incoming.size() - MAX_HISTORY);
        messages.addAll(incoming.subList(start, incoming.size()));

        try {
            boolean hasToolCalls = runToolLoop(messages, egress, viewer);
            if (hasToolCalls) {
                writeToClient(() -> egress.assistantDelta(
                        "抱歉,处理你的请求时步骤过多,请缩小问题范围后重试。"));
            }
            writeToClient(egress::ended);
        } catch (ClientDisconnectedException e) {
            // 客户端断开。这是**正常的用户操作**(关掉标签页、点了停止),不是服务故障。
            //
            // 此前它落到下面的兜底分支,被记成一条 `log.error("AI chat failed")` 加完整堆栈,
            // 并且还会朝一个已经不在的客户端发一句「AI 服务异常」——
            // 于是「用户关了页面」在日志里长得和「AI 服务挂了」一模一样。
            log.debug("客户端已断开,对话中止: {}", e.getCause() == null ? "" : e.getCause().getMessage());
        } catch (BizException e) {
            egress.failed(e.getMessage());
        } catch (Exception e) {
            log.error("AI chat failed", e);
            egress.failed("AI 服务异常,请稍后重试");
        }
    }

    /**
     * 往客户端写。写不出去只有一个合理解释:**客户端断了**。
     *
     * <p>把它转成一个内部信号,是为了让「写不出去」在下面那三个 catch 里能和
     * 「AI 服务真的出错了」分开 —— 两者此前不可区分,因为都是 Exception。
     */
    private static void writeToClient(EgressWrite write) {
        try {
            write.write();
        } catch (Exception e) {
            throw new ClientDisconnectedException(e);
        }
    }

    @FunctionalInterface
    private interface EgressWrite {
        void write() throws Exception;
    }

    /** 流式工具循环:内容增量实时转发;返回是否有未消费的工具调用(轮数耗尽)。 */
    private boolean runToolLoop(List<ChatMessage> messages, ChatEgress egress, Viewer viewer) throws Exception {
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            List<ChatMessage.ToolCall>[] toolCalls = new List[1];
            openAiClient.chatStream(messages, toolRegistry.definitions(viewer), new ChatStreamListener() {
                @Override
                public void onContent(String delta) {
                    writeToClient(() -> egress.assistantDelta(delta));
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
                String result = executeTool(call, egress, viewer);
                messages.add(ChatMessage.toolResult(call.id(), result));
            }
        }
        return true;
    }

    private String executeTool(ChatMessage.ToolCall call, ChatEgress egress, Viewer viewer) throws Exception {
        String name = call.functionName();
        AgentTool tool = toolRegistry.get(name);
        // 「不存在」与「对你不存在」必须给出**逐字相同**的答复 —— 两处稍有差别就是一个
        // 存在性预言机,模型会从措辞里读出「这儿有个你不能用的能力」,然后反复重试。
        //
        // definitions(viewer) 已经把不可用的工具从清单里摘掉了,但模型可以凭空说出一个
        // 它没被给过的名字。所以强制在这里做第二次判定:那一处是 UX,这一处才是边界。
        if (tool == null || !tool.isAvailableTo(viewer)) {
            return errorEnvelope("未知工具: " + name);
        }
        Map<String, Object> args = parseArgs(call);
        writeToClient(() -> egress.toolStarted(name, args));
        String result;
        try {
            result = tool.execute(args);
        } catch (Exception e) {
            log.warn("tool {} failed: {}", name, e.getMessage());
            result = errorEnvelope(e.getMessage() == null ? "工具执行失败" : e.getMessage());
        }
        if (result.length() > TOOL_RESULT_LIMIT) {
            result = result.substring(0, TOOL_RESULT_LIMIT) + "…(已截断)";
        }
        String reported = result;
        writeToClient(() -> egress.toolFinished(name, args, reported));
        return reported;
    }

    /**
     * 工具的错误信封。
     *
     * <p>此前是字符串拼接:{@code "{\"error\":\"" + message + "\"}"} —— 驱动消息里
     * **一个引号**就能造出一段坏 JSON,而那段 JSON 会作为 tool 结果原样回到模型那里
     * (模型读到的是「未知工具」还是「语法错误」,取决于它自己刚才说了什么)。
     * 交给 ObjectMapper 转义才是唯一说得通的做法。
     */
    private String errorEnvelope(String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("error", message);
        return node.toString();
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

    /**
     * 「写不进客户端」的内部信号 —— 客户端断开、连接被中断,或 servlet 流已经关了。
     *
     * <p>它与「AI 服务出错」是两件事:前者是正常用户操作(关页面、点停止)导致的中止,
     * 后者才该记成服务故障。此前两者都是 Exception,于是只能一起记成后者。
     */
    private static final class ClientDisconnectedException extends RuntimeException {

        private ClientDisconnectedException(Throwable cause) {
            super(cause);
        }
    }
}
