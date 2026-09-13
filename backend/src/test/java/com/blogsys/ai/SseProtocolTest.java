package com.blogsys.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线格式的唯一归属。
 *
 * <p>事件名、载荷形状与 SSE 组帧此前散在四处({@code ChatService} 拼载荷、
 * {@code SseWriterHttp} 组帧、测试的 adapter 再解析回来、{@code frontend/src/api/ai.js}
 * 的 dispatch 再来一遍)。现在前两处归 {@link SseProtocol},这份测试就是它的规格。
 *
 * <p>最后一条钉住四个事件名本身 —— 它们是**跨语言契约**(浏览器那边的解析器按同样的
 * 名字读),所以改名字必须是一次显式的、会红掉的决定。
 */
class SseProtocolTest {

    private final SseProtocol protocol = new SseProtocol(new ObjectMapper());

    @Test
    @DisplayName("回答增量:event:message / data:{content}")
    void delta_shouldFrameContentDelta() {
        assertEquals("event:message\ndata:{\"content\":\"你好\"}\n\n", protocol.delta("你好"));
    }

    @Test
    @DisplayName("工具开始:载荷只有 name 与 args,没有 result 键")
    void toolStarted_shouldOmitResultKey() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id", 17);

        String frame = protocol.toolStarted("getArticleDetail", args);

        assertEquals("event:tool\ndata:{\"name\":\"getArticleDetail\",\"args\":{\"id\":17}}\n\n", frame);
    }

    @Test
    @DisplayName("工具结束:同一个事件名,多一个 result 键 —— 前后各发一次是契约的一部分")
    void toolFinished_shouldCarryResult() {
        String frame = protocol.toolFinished("getSiteStats", Map.of(), "{\"userCount\":4}");

        assertTrue(frame.startsWith("event:tool\ndata:"), "实际: " + frame);
        assertTrue(frame.contains("\"name\":\"getSiteStats\""), "实际: " + frame);
        assertTrue(frame.contains("\"result\":\"{\\\"userCount\\\":4}\""),
                "结果本身是 JSON 文本,所以它在 data 里是被转义的字符串。实际: " + frame);
    }

    @Test
    @DisplayName("失败:event:error / data:{message}")
    void failed_shouldFrameMessage() {
        assertEquals("event:error\ndata:{\"message\":\"AI 服务未配置\"}\n\n", protocol.failed("AI 服务未配置"));
    }

    @Test
    @DisplayName("收尾:event:done / data:{}")
    void done_shouldFrameEmptyObject() {
        assertEquals("event:done\ndata:{}\n\n", protocol.done());
    }

    @Test
    @DisplayName("心跳是注释帧 —— 以冒号开头,浏览器端的解析器按规范忽略它")
    void comment_shouldBeAnSseComment() {
        // 它不是事件:harness 这边靠它保活与探活(写不出去才知道客户端走了),
        // 浏览器那边靠它区分「AI 正在想」与「连接已经死了」。
        assertEquals(": ping\n\n", protocol.comment("ping"));
    }

    @Test
    @DisplayName("每个事件的帧都以空行结束 —— SSE 靠空行分帧,少一个就会粘住下一条")
    void everyFrame_shouldEndWithABlankLine() {
        for (String frame : new String[]{protocol.delta("x"), protocol.toolStarted("t", Map.of()),
                protocol.toolFinished("t", Map.of(), "{}"), protocol.failed("m"), protocol.done(),
                protocol.comment("ping")}) {
            assertTrue(frame.endsWith("\n\n"), "实际: " + frame);
        }
    }

    @Test
    @DisplayName("四个事件名就是契约本身 —— 改动必须是一次会红掉的决定")
    void eventNames_areTheContract() {
        assertEquals("message", SseProtocol.MESSAGE);
        assertEquals("tool", SseProtocol.TOOL);
        assertEquals("error", SseProtocol.ERROR);
        assertEquals("done", SseProtocol.DONE);
    }
}
