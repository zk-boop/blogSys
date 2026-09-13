package com.blogsys.ai;

import com.blogsys.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把上游的增量帧归约成对话行为:帧进,事件出。
 *
 * <p>它不认识网络、不认识 HTTP —— 唯一入口 {@link #accept(JsonNode)} 由传输层喂。
 * 因此可以在测试里毫秒级地喂固定帧、断言事件,而不必碰一次真实 HTTP 调用。
 *
 * <p>此前这段逻辑是 {@code OpenAiClient} 的私有内部类,只能靠真实网络触达,于是
 * <b>整个测试套件里一帧都没被解析过</b> —— D8(错误帧被静默丢弃)与 D9(零参数工具调用
 * 可能被丢掉)就长在那片没有测试面的地方。
 *
 * <p>三件事在这里被钉死,都是原来错过的:
 * <ol>
 *   <li><b>错误帧有通道。</b>OpenAI 形状的 {@code {"error":…}}(限流、内容过滤)以前
 *       直接 return 掉,流随后读到 EOF,用户收到一个 {@code done} 和一片空白。</li>
 *   <li><b>「有没有工具调用」只看调用条目本身</b>,不再从参数分片推断。零参数工具
 *       ({@code getSiteStats} / {@code getHotArticles})的参数是空的,原来会被
 *       重新归类成「没有工具调用」,助手消息里也不含这个调用 —— 模型永远不知道
 *       它被跳过了。</li>
 *   <li><b>被截断的流不再伪装成正常结束。</b>既没有结束原因、也没有结束哨兵时抛错,
 *       而不是走成 {@code onFinish()}。</li>
 * </ol>
 */
public class StreamReducer {

    private final ChatStreamListener listener;
    private final Map<Integer, ToolCallBuilder> calls = new LinkedHashMap<>();
    private boolean ended;
    private boolean done;

    public StreamReducer(ChatStreamListener listener) {
        this.listener = listener;
    }

    /** 上游是否已经给出结束信号(结束原因或结束哨兵)。 */
    public boolean isFinished() {
        return ended || done;
    }

    /** 喂一帧。 */
    public void accept(JsonNode frame) {
        JsonNode error = frame.path("error");
        if (!error.isMissingNode()) {
            throw new BizException(502, "AI 服务返回错误: " + describe(error));
        }
        JsonNode choice = frame.path("choices").path(0);
        if (choice.isMissingNode()) {
            // 既不是错误帧、也不是可识别的增量帧(例如空的选择数组、只带 usage 的收尾帧)。
            // 忽略是对的 —— 但上面那条错误分支必须先在。
            return;
        }
        JsonNode delta = choice.path("delta");
        JsonNode content = delta.path("content");
        if (content.isTextual()) {
            listener.onContent(content.asText());
        }
        delta.path("tool_calls").forEach(this::acceptToolCall);
        if (!choice.path("finish_reason").asText("").isEmpty()) {
            ended = true;
        }
    }

    /** 上游发来了结束哨兵(SSE 的 {@code data: [DONE]})。线格式由传输层识别,这里只记下。 */
    public void markDone() {
        this.done = true;
    }

    /**
     * 上游流走到了 EOF。
     *
     * @throws BizException 流既没给出结束原因、也没给出结束哨兵 —— 那是一次被截断的流。
     *     以前这种情况会走成 {@code onFinish()}:用户拿到一个 {@code done} 和一片空白,
     *     没有任何错误事件。
     */
    public void finish() {
        if (!isFinished()) {
            throw new BizException(502, "AI 流意外结束:未收到结束标记");
        }
        if (calls.isEmpty()) {
            listener.onFinish();
        } else {
            listener.onToolCalls(calls.values().stream().map(ToolCallBuilder::toCall).toList());
        }
    }

    private void acceptToolCall(JsonNode call) {
        // 这条条目出现 = 这个工具调用存在。与它有没有参数无关 —— D9 就是在这里错的。
        ToolCallBuilder builder = calls.computeIfAbsent(call.path("index").asInt(0), ToolCallBuilder::new);
        String id = call.path("id").asText(null);
        if (id != null && !id.isBlank()) {
            builder.id = id;
        }
        String name = call.path("function").path("name").asText(null);
        if (name != null && !name.isBlank()) {
            builder.name = name;
        }
        // 参数跨帧分片到达,按 index 依次拼接。空串拼接是无害的,所以不必特判。
        String args = call.path("function").path("arguments").asText(null);
        if (args != null) {
            builder.args.append(args);
        }
    }

    private static String describe(JsonNode error) {
        String message = error.path("message").asText(null);
        if (message != null && !message.isBlank()) {
            return message;
        }
        return error.isTextual() ? error.asText() : error.toString();
    }

    /** 一次工具调用的累积状态:它分散在若干帧里。 */
    private static final class ToolCallBuilder {

        private final int index;
        private String id;
        private String name;
        private final StringBuilder args = new StringBuilder();

        private ToolCallBuilder(int index) {
            this.index = index;
        }

        private ChatMessage.ToolCall toCall() {
            return new ChatMessage.ToolCall(
                    id == null || id.isBlank() ? "call-" + index : id,
                    "function",
                    new ChatMessage.ToolFunction(name == null ? "" : name, args.toString()));
        }
    }
}
