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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ChatService} 的测试面。
 *
 * <p><b>断言的是行为,不是 JSON。</b>seam 升到行为层之前,这里的 adapter 得把自己刚
 * 序列化出去的 JSON 再解析回来才能问「回答是什么」—— 于是测试同时验证了 ChatService
 * 与一份线格式的副本,而那份副本在别处还有三份。
 */
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

    private Recorder recorder() {
        return new Recorder();
    }

    /**
     * 行为层的测试 adapter:只记下「发生了什么」。
     *
     * <p>它不知道事件名、不知道载荷形状、也不组帧 —— 那些归 {@link SseProtocol},
     * 由 {@code SseProtocolTest} 负责。这里记录的三样东西就是 ChatService 真正说的话。
     */
    static class Recorder implements ChatEgress {

        final List<String> events = new ArrayList<>();
        final StringBuilder answer = new StringBuilder();
        String failure;

        @Override
        public void assistantDelta(String delta) {
            events.add("delta");
            answer.append(delta);
        }

        @Override
        public void toolStarted(String name, Map<String, Object> args) {
            events.add("toolStarted:" + name);
        }

        @Override
        public void toolFinished(String name, Map<String, Object> args, String result) {
            events.add("toolFinished:" + name);
        }

        @Override
        public void failed(String message) {
            events.add("failed");
            failure = message;
        }

        @Override
        public void ended() {
            events.add("ended");
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
    void chat_shouldReportFailure_whenApiKeyMissing() {
        properties.setApiKey("");
        Recorder recorder = recorder();

        chatService.chat(List.of(ChatMessage.user("你好")), recorder, VIEWER);

        assertTrue(recorder.failure.contains("AI 服务未配置"), "实际: " + recorder.failure);
        assertTrue(recorder.events.contains("failed"));
        verify(openAiClient, never()).chatStream(any(), anyList(), any());
    }

    @Test
    void chat_shouldStreamAnswerDirectly_whenNoToolCall() {
        streamAnswer("你好!我是博客助手。");

        Recorder recorder = recorder();
        chatService.chat(List.of(ChatMessage.user("你是谁")), recorder, VIEWER);

        assertEquals("你好!我是博客助手。", recorder.answer.toString());
        assertTrue(recorder.events.contains("ended"), "正常走完必须发 ended");
        assertNull(recorder.failure);
        verify(tool, never()).execute(any());
    }

    @Test
    void chat_shouldRunToolLoopAndStreamFinalAnswer() {
        streamToolCalls(List.of(toolCall("call-1", "getHotArticles", "{}")));
        streamAnswer("热门文章有 5 篇。");
        when(tool.execute(any())).thenReturn("{\"count\":5}");

        Recorder recorder = recorder();
        chatService.chat(List.of(ChatMessage.user("有哪些热门文章")), recorder, VIEWER);

        assertEquals(List.of("toolStarted:getHotArticles", "toolFinished:getHotArticles", "delta", "ended"),
                recorder.events, "工具调用的开始与结束各说一次,然后才是回答");
        assertEquals("热门文章有 5 篇。", recorder.answer.toString());
        verify(tool).execute(any());
        verify(openAiClient, times(2)).chatStream(any(), anyList(), any());
    }

    @Test
    void chat_shouldContinue_whenToolThrows() {
        streamToolCalls(List.of(toolCall("call-1", "getHotArticles", "{}")));
        streamAnswer("抱歉,暂时查不到。");
        when(tool.execute(any())).thenThrow(new BizException("模拟失败"));

        Recorder recorder = recorder();
        chatService.chat(List.of(ChatMessage.user("查热门")), recorder, VIEWER);

        // 工具失败要如实告诉模型,由模型决定怎么对用户说 —— 所以是 toolFinished 而不是 failed
        assertEquals(List.of("toolStarted:getHotArticles", "toolFinished:getHotArticles", "delta", "ended"),
                recorder.events);
        assertTrue(recorder.answer.toString().contains("抱歉,暂时查不到。"));
        assertNull(recorder.failure);
        verify(openAiClient, times(2)).chatStream(any(), anyList(), any());
    }

    @Test
    void chat_shouldStopAfterMaxRounds() {
        for (int i = 0; i < 4; i++) {
            streamToolCalls(List.of(toolCall("call-" + i, "getHotArticles", "{}")));
        }
        when(tool.execute(any())).thenReturn("{}");

        Recorder recorder = recorder();
        chatService.chat(List.of(ChatMessage.user("一直调用工具")), recorder, VIEWER);

        assertTrue(recorder.answer.toString().contains("步骤过多"), "实际: " + recorder.answer);
        assertTrue(recorder.events.contains("ended"));
        verify(openAiClient, times(4)).chatStream(any(), anyList(), any());
        verify(tool, times(4)).execute(any());
    }

    @Test
    void chat_shouldHandleUnknownTool() {
        streamToolCalls(List.of(toolCall("call-1", "noSuchTool", "{}")));
        streamAnswer("已收到。");

        Recorder recorder = recorder();
        chatService.chat(List.of(ChatMessage.user("调工具")), recorder, VIEWER);

        assertEquals(List.of("delta", "ended"), recorder.events,
                "未知工具不该产生任何工具事件 —— 模型只看到一条 toolResult");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(openAiClient, times(2)).chatStream(captor.capture(), anyList(), any());
        assertTrue(captor.getAllValues().get(1).toString().contains("未知工具"));
        verify(tool, never()).execute(any());
    }

    // ---- 「谁在问」穿过 seam:工具清单与执行判定都由提问者决定 ----

    @Test
    @DisplayName("不可用的工具不出现在发给模型的清单里 —— 让它连尝试的机会都没有")
    void chat_shouldNotOfferUnavailableTool() {
        when(tool.isAvailableTo(any())).thenReturn(false);
        streamAnswer("好的。");

        chatService.chat(List.of(ChatMessage.user("全站有多少文章")), recorder(), VIEWER);

        assertTrue(offeredTools().isEmpty(), "对提问者不可用的工具不该出现在 tools 列表里");
    }

    @Test
    @DisplayName("同一个工具,换管理员来问就出现在清单里 —— 过滤的是人,不是工具")
    void chat_shouldOfferTheTool_whenViewerIsAdmin() {
        streamAnswer("好的。");

        chatService.chat(List.of(ChatMessage.user("全站有多少文章")), recorder(), Viewer.of(1L, true));

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

        Recorder recorder = recorder();
        chatService.chat(List.of(ChatMessage.user("调工具")), recorder, VIEWER);

        assertEquals(List.of("delta", "ended"), recorder.events, "不可用的工具不该被执行");
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

    @Test
    @DisplayName("工具的错误信封是合法 JSON —— 消息里一个引号不再能把它弄坏")
    void chat_shouldEscapeToolErrorMessages() throws Exception {
        // 此前信封是字符串拼接:`{"error":"` + message + `"}`。
        // 这一句带引号的消息会拼出一段坏 JSON,而它会作为 tool 结果原样回到模型那里 ——
        // 模型读到的是「未知工具」还是「语法错误」,取决于它自己刚才说了什么。
        streamToolCalls(List.of(toolCall("call-1", "getHotArticles", "{}")));
        streamAnswer("好");
        when(tool.execute(any())).thenThrow(new BizException("他说:\"这不可能\""));

        chatService.chat(List.of(ChatMessage.user("x")), recorder(), VIEWER);

        assertEquals("他说:\"这不可能\"", toolErrorEnvelope().path("error").asText(),
                "消息要原样带回来,而不是把 JSON 弄坏");
    }

    /** 从第二次 chatStream 收到的消息里找出工具错误信封,并把它当 JSON 解析。 */
    private com.fasterxml.jackson.databind.JsonNode toolErrorEnvelope() throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(openAiClient, times(2)).chatStream(captor.capture(), anyList(), any());
        for (ChatMessage message : captor.getAllValues().get(1)) {
            String content = message.content();
            if (content != null && content.contains("error")) {
                return objectMapper.readTree(content);
            }
        }
        throw new AssertionError("没有找到工具错误信封");
    }
}
