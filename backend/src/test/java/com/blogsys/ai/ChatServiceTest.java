package com.blogsys.ai;

import com.blogsys.ai.tool.AgentTool;
import com.blogsys.ai.tool.ToolRegistry;
import com.blogsys.common.BizException;
import com.blogsys.visibility.Viewer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    /** 一个普通登录用户。工具清单与执行判定都按它计算。 */
    private static final Viewer VIEWER = Viewer.of(1L, false);

    @Mock
    private OpenAiClient openAiClient;

    @Mock
    private AgentTool tool;

    private ChatService chatService;
    private ObjectMapper objectMapper;
    private AiProperties properties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new AiProperties();
        properties.setApiKey("test-key");
        when(tool.name()).thenReturn("getHotArticles");
        // mock 的 boolean 默认是 false,而 isAvailableTo 是执行前的门槛 ——
        // 不显式打开的话,下面每个用例都会在「工具不可用」处被拦下。
        lenient().when(tool.isAvailableTo(any())).thenReturn(true);
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        chatService = new ChatService(openAiClient, registry, objectMapper, properties);
        behaviorIndex = 0;
        behaviors.clear();
        lenient().doAnswer(inv -> {
            java.util.function.Consumer<ChatStreamListener> behavior = behaviors.get(behaviorIndex);
            behaviorIndex++;
            behavior.accept(inv.getArgument(2));
            return null;
        }).when(openAiClient).chatStream(any(), anyList(), any());
    }

    private final List<java.util.function.Consumer<ChatStreamListener>> behaviors = new ArrayList<>();
    private int behaviorIndex;

    private SseWriter writer() {
        return new CapturingWriter();
    }

    private String joinedEvents(SseWriter writer) {
        return String.join(" ", ((CapturingWriter) writer).events.stream()
                .map(ev -> ev.name + ":" + ev.data).toList());
    }

    /** 拼接所有 message 事件的分块内容,用于断言最终完整回答。 */
    private String fullContent(SseWriter writer) {
        StringBuilder sb = new StringBuilder();
        for (CapturingWriter.Event ev : ((CapturingWriter) writer).events) {
            if ("message".equals(ev.name)) {
                try {
                    sb.append(objectMapper.readTree(ev.data).path("content").asText(""));
                } catch (Exception ignored) {
                    // 忽略无法解析的事件
                }
            }
        }
        return sb.toString();
    }

    static class CapturingWriter implements SseWriter {

        final List<Event> events = new ArrayList<>();

        @Override
        public void event(String name, String dataJson) {
            events.add(new Event(name, dataJson));
        }

        record Event(String name, String data) {
        }
    }

    private ChatMessage.ToolCall toolCall(String id, String name, String args) {
        return new ChatMessage.ToolCall(id, "function", new ChatMessage.ToolFunction(name, args));
    }

    /** mock 流式响应:回调 content 增量,最后 onFinish。 */
    private void streamAnswer(String content) {
        behaviors.add(listener -> {
            listener.onContent(content);
            listener.onFinish();
        });
    }

    /** mock 流式响应:本轮返回工具调用。 */
    private void streamToolCalls(List<ChatMessage.ToolCall> calls) {
        behaviors.add(listener -> listener.onToolCalls(calls));
    }

    @Test
    void chat_shouldSendErrorEvent_whenApiKeyMissing() {
        properties.setApiKey("");
        SseWriter writer = writer();

        chatService.chat(List.of(ChatMessage.user("你好")), writer, VIEWER);

        String joined = joinedEvents(writer);
        assertTrue(joined.contains("AI 服务未配置"));
        verify(openAiClient, never()).chatStream(any(), anyList(), any());
    }

    @Test
    void chat_shouldStreamAnswerDirectly_whenNoToolCall() {
        streamAnswer("你好!我是博客助手。");

        SseWriter writer = writer();
        chatService.chat(List.of(ChatMessage.user("你是谁")), writer, VIEWER);

        String joined = joinedEvents(writer);
        assertTrue(fullContent(writer).contains("你好!我是博客助手。"));
        assertTrue(joined.contains("done"));
        verify(tool, never()).execute(any());
    }

    @Test
    void chat_shouldRunToolLoopAndStreamFinalAnswer() {
        streamToolCalls(List.of(toolCall("call-1", "getHotArticles", "{}")));
        streamAnswer("热门文章有 5 篇。");
        when(tool.execute(any())).thenReturn("{\"count\":5}");

        SseWriter writer = writer();
        chatService.chat(List.of(ChatMessage.user("有哪些热门文章")), writer, VIEWER);

        String joined = joinedEvents(writer);
        assertTrue(joined.contains("tool:"));
        assertTrue(fullContent(writer).contains("热门文章有 5 篇。"));
        assertTrue(joined.contains("done"));
        verify(tool).execute(any());
        verify(openAiClient, times(2)).chatStream(any(), anyList(), any());
    }

    @Test
    void chat_shouldContinue_whenToolThrows() {
        streamToolCalls(List.of(toolCall("call-1", "getHotArticles", "{}")));
        streamAnswer("抱歉,暂时查不到。");
        when(tool.execute(any())).thenThrow(new BizException("模拟失败"));

        SseWriter writer = writer();
        chatService.chat(List.of(ChatMessage.user("查热门")), writer, VIEWER);

        String joined = joinedEvents(writer);
        assertTrue(joined.contains("模拟失败"));
        assertTrue(fullContent(writer).contains("抱歉,暂时查不到。"));
        verify(openAiClient, times(2)).chatStream(any(), anyList(), any());
    }

    @Test
    void chat_shouldStopAfterMaxRounds() {
        for (int i = 0; i < 4; i++) {
            streamToolCalls(List.of(toolCall("call-" + i, "getHotArticles", "{}")));
        }
        when(tool.execute(any())).thenReturn("{}");

        SseWriter writer = writer();
        chatService.chat(List.of(ChatMessage.user("一直调用工具")), writer, VIEWER);

        String joined = joinedEvents(writer);
        assertTrue(fullContent(writer).contains("步骤过多"));
        assertTrue(joined.contains("done"));
        verify(openAiClient, times(4)).chatStream(any(), anyList(), any());
        verify(tool, times(4)).execute(any());
    }

    @Test
    void chat_shouldHandleUnknownTool() {
        streamToolCalls(List.of(toolCall("call-1", "noSuchTool", "{}")));
        streamAnswer("已收到。");

        SseWriter writer = writer();
        chatService.chat(List.of(ChatMessage.user("调工具")), writer, VIEWER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(openAiClient, times(2)).chatStream(captor.capture(), anyList(), any());
        String secondCall = captor.getAllValues().get(1).toString();
        assertTrue(secondCall.contains("未知工具"));
        verify(tool, never()).execute(any());
    }

    // ---- 「谁在问」穿过 seam:工具清单与执行判定都由提问者决定 ----

    @Test
    @DisplayName("不可用的工具不出现在发给模型的清单里 —— 让它连尝试的机会都没有")
    void chat_shouldNotOfferUnavailableTool() {
        when(tool.isAvailableTo(any())).thenReturn(false);
        streamAnswer("好的。");

        chatService.chat(List.of(ChatMessage.user("全站有多少文章")), writer(), VIEWER);

        assertTrue(offeredTools().isEmpty(),
                "对提问者不可用的工具不该出现在 tools 列表里");
    }

    @Test
    @DisplayName("同一个工具,换管理员来问就出现在清单里 —— 过滤的是人,不是工具")
    void chat_shouldOfferTheTool_whenViewerIsAdmin() {
        streamAnswer("好的。");

        chatService.chat(List.of(ChatMessage.user("全站有多少文章")), writer(), Viewer.of(1L, true));

        assertEquals(List.of("getHotArticles"),
                offeredTools().stream().map(d -> d.function().name()).toList());
    }

    @Test
    @DisplayName("模型凭空说出一个它没被给过的工具名:答复与「未知工具」逐字相同")
    void chat_shouldRefuseUnavailableTool_withTheSameAnswerAsAnUnknownTool() {
        // 清单过滤只是 UX —— 模型可以猜出一个名字。强制在执行前做第二次判定,
        // 且两种情况的措辞必须一致:稍有差别就是「这儿有个你不能用的东西」的预言机。
        when(tool.isAvailableTo(any())).thenReturn(false);
        streamToolCalls(List.of(toolCall("call-1", "getHotArticles", "{}")));
        streamAnswer("已收到。");

        SseWriter writer = writer();
        chatService.chat(List.of(ChatMessage.user("调工具")), writer, VIEWER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(openAiClient, times(2)).chatStream(captor.capture(), anyList(), any());
        assertTrue(captor.getAllValues().get(1).toString().contains("未知工具"));
        verify(tool, never()).execute(any());
    }

    /** 第一次 chatStream 调用实际发出去的 tools 列表。 */
    private List<ToolDefinition> offeredTools() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolDefinition>> captor = ArgumentCaptor.forClass(List.class);
        verify(openAiClient).chatStream(any(), captor.capture(), any());
        return captor.getValue();
    }
}
