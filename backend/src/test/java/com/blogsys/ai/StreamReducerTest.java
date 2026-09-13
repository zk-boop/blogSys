package com.blogsys.ai;

import com.blogsys.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游解帧的测试面。
 *
 * <p><b>在候选 06 之前,整个测试套件里一帧都没被解析过。</b>唯一解帧的代码是
 * {@code OpenAiClient} 的私有内部类,只能靠一次真实 HTTP 调用触达,于是 D8(错误帧被静默
 * 丢弃)与 D9(零参数工具调用可能被丢掉)长在那片没有测试面的地方。
 *
 * <p>这些用例是毫秒级的:喂固定帧,断言事件,不碰网络。
 */
class StreamReducerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> events = new ArrayList<>();
    private final List<ChatMessage.ToolCall> toolCalls = new ArrayList<>();
    private StreamReducer reducer;

    @BeforeEach
    void setUp() {
        events.clear();
        toolCalls.clear();
        reducer = new StreamReducer(new ChatStreamListener() {
            @Override
            public void onContent(String delta) {
                events.add("content:" + delta);
            }

            @Override
            public void onToolCalls(List<ChatMessage.ToolCall> calls) {
                events.add("toolCalls");
                toolCalls.addAll(calls);
            }

            @Override
            public void onFinish() {
                events.add("finish");
            }
        });
    }

    private void feed(String json) {
        try {
            reducer.accept(mapper.readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 正常路径 ----------

    @Test
    @DisplayName("内容增量按到达顺序转发")
    void accept_shouldForwardContentDeltasInOrder() {
        feed("{\"choices\":[{\"delta\":{\"content\":\"你\"}}]}");
        feed("{\"choices\":[{\"delta\":{\"content\":\"好\"}}]}");
        feed("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        reducer.finish();

        assertEquals(List.of("content:你", "content:好", "finish"), events);
    }

    @Test
    @DisplayName("结束原因到达后 isFinished 为真")
    void accept_shouldMarkFinished_onFinishReason() {
        assertFalse(reducer.isFinished());
        feed("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        assertTrue(reducer.isFinished());
    }

    @Test
    @DisplayName("[DONE] 哨兵单独到达也算正常结束")
    void finish_shouldAcceptDoneSentinelAlone() {
        reducer.markDone();
        reducer.finish();

        assertEquals(List.of("finish"), events);
    }

    // ---------- D9:零参数工具调用 ----------

    @Test
    @DisplayName("D9:参数为空串的工具调用仍然是工具调用")
    void finish_shouldEmitToolCall_whenArgumentsAreEmpty() {
        // getSiteStats 与 getHotArticles 的参数就是空的(空 property map)。
        // 旧实现在 complete() 里用「参数分片是否为空」推断有没有工具调用,于是这种调用
        // 被重新归类成「没有工具调用」,助手消息里也不含它 —— 模型永远不知道它被跳过了。
        feed("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
                + "\"function\":{\"name\":\"getSiteStats\",\"arguments\":\"\"}}]}}]}");
        feed("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        reducer.finish();

        assertEquals(1, toolCalls.size(), "零参数的工具调用必须被交出去");
        assertEquals("getSiteStats", toolCalls.get(0).functionName());
        assertEquals("", toolCalls.get(0).function().arguments());
        assertFalse(events.contains("finish"), "有工具调用时不该同时报结束");
    }

    @Test
    @DisplayName("D9:arguments 键整个缺失时同样算工具调用")
    void finish_shouldEmitToolCall_whenArgumentsKeyIsAbsent() {
        feed("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
                + "\"function\":{\"name\":\"getHotArticles\"}}]}}]}");
        feed("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        reducer.finish();

        assertEquals(1, toolCalls.size());
        assertEquals("getHotArticles", toolCalls.get(0).functionName());
        assertEquals("", toolCalls.get(0).function().arguments());
    }

    @Test
    @DisplayName("参数分片跨帧按 index 拼接")
    void accept_shouldConcatenateArgumentFragments() {
        feed("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                + "\"function\":{\"name\":\"getArticleDetail\",\"arguments\":\"{\\\"id\\\":\"}}]}}]}");
        feed("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"17}\"}}]}}]}");
        feed("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        reducer.finish();

        assertEquals(1, toolCalls.size());
        assertEquals("getArticleDetail", toolCalls.get(0).functionName());
        assertEquals("{\"id\":17}", toolCalls.get(0).function().arguments());
    }

    @Test
    @DisplayName("一次返回多个工具调用:按 index 分开聚合,顺序稳定")
    void accept_shouldKeepCallsApartByIndex() {
        feed("{\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"c0\",\"function\":{\"name\":\"searchArticles\",\"arguments\":\"{\\\"keyword\\\":\\\"a\\\"}\"}},"
                + "{\"index\":1,\"id\":\"c1\",\"function\":{\"name\":\"getHotArticles\",\"arguments\":\"\"}}]}}]}");
        feed("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        reducer.finish();

        assertEquals(List.of("searchArticles", "getHotArticles"),
                toolCalls.stream().map(ChatMessage.ToolCall::functionName).toList());
        assertEquals("c0", toolCalls.get(0).id());
        assertEquals("c1", toolCalls.get(1).id());
    }

    @Test
    @DisplayName("上游没给 id 时补一个按 index 的占位,不影响参数")
    void finish_shouldSynthesizeMissingCallId() {
        feed("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":2,"
                + "\"function\":{\"name\":\"getSiteStats\",\"arguments\":\"\"}}]}}]}");
        feed("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        reducer.finish();

        assertEquals("call-2", toolCalls.get(0).id());
        assertEquals("getSiteStats", toolCalls.get(0).functionName());
    }

    // ---------- D8:错误帧 ----------

    @Test
    @DisplayName("D8:错误帧抛错,不再被静默丢弃")
    void accept_shouldThrow_onErrorFrame() {
        // OpenAI 形状的错误帧(限流、内容过滤)。以前 accept() 对没有 choices 的帧直接
        // return,流随后读到 EOF、finished 保持 false,用户收到一个 done 和一片空白,
        // 没有任何错误事件。
        BizException e = assertThrows(BizException.class, () ->
                feed("{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_error\"}}"));

        assertEquals(502, e.getCode());
        assertTrue(e.getMessage().contains("Rate limit reached"),
                "上游给的信息要带出来,实际: " + e.getMessage());
    }

    @Test
    @DisplayName("D8:错误帧在流中间到达同样抛错,不会把半截回答当完整回答")
    void accept_shouldThrow_whenErrorArrivesMidStream() {
        feed("{\"choices\":[{\"delta\":{\"content\":\"前半\"}}]}");

        assertThrows(BizException.class, () -> feed("{\"error\":{\"message\":\"内容被过滤\"}}"));

        assertEquals(List.of("content:前半"), events, "不能有 finish —— 那会让用户以为回答完整");
    }

    // ---------- 截断 ----------

    @Test
    @DisplayName("被截断的流抛错,而不是伪装成正常结束")
    void finish_shouldThrow_whenStreamWasTruncated() {
        feed("{\"choices\":[{\"delta\":{\"content\":\"半截\"}}]}");

        BizException e = assertThrows(BizException.class, () -> reducer.finish());

        assertEquals(502, e.getCode());
        assertFalse(events.contains("finish"),
                "截断走成 onFinish 就是「空白回答 + done」那类静默失败");
    }

    // ---------- 无关帧 ----------

    @Test
    @DisplayName("既非错误也非增量的帧被忽略(只带 usage 的收尾帧、空的选择数组)")
    void accept_shouldIgnoreUnrecognizedFrames() {
        feed("{\"usage\":{\"total_tokens\":10}}");
        feed("{\"choices\":[]}");

        assertEquals(List.of(), events);
    }
}
