package com.blogsys.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「客户端还在不在」这件事的唯一证据来源。
 *
 * <p>一个已经断开的连接**不会以别的方式通知服务端**:servlet 的 error/complete 回调都不会
 * 因为对端关掉而触发,Tomcat 也不会在某个安静的时刻替我们发现。只有真的写一次才知道。
 * 所以心跳不只是保活 —— 它是唯一按期发生的探测,而 {@code connected} 就是它的产出。
 *
 * <p>本测试用一个写不了的响应当对照:如果「写失败」不翻这个标志,那么
 * {@code ChatService} 的轮间检查(用户点了停止之后后台还在跑)就永远是 true。
 */
class SseWriterHttpTest {

    private final SseProtocol protocol = new SseProtocol(new ObjectMapper());

    @Test
    @DisplayName("心跳写出去的是一个注释帧,而且没把连接标记成断开")
    void keepAlive_shouldWriteACommentFrame() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SseWriterHttp writer = new SseWriterHttp(response, protocol);

        writer.keepAlive();

        assertEquals(": ping\n\n", response.getContentAsString());
        assertTrue(writer.stillConnected());
    }

    @Test
    @DisplayName("写不出去 = 客户端不在 —— 这就是断连的信号")
    void keepAlive_shouldMarkDisconnected_whenTheWriteFails() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenThrow(new IOException("Connection reset by peer"));
        SseWriterHttp writer = new SseWriterHttp(response, protocol);

        assertDoesNotThrow(writer::keepAlive, "心跳是尽力而为的:它不该把调度它的任务打死");

        assertFalse(writer.stillConnected(), "写都写不出去,客户端已经不在了");
    }

    @Test
    @DisplayName("流已经关掉时抛的是运行时异常,那对「还在不在」是同一个答案")
    void write_shouldMarkDisconnected_onRuntimeExceptionsToo() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenThrow(new IllegalStateException("流已关闭"));
        SseWriterHttp writer = new SseWriterHttp(response, protocol);

        writer.keepAlive();

        assertFalse(writer.stillConnected());
    }

    @Test
    @DisplayName("回答增量写失败时也翻这个标志 —— 心跳只是让这件事按期发生")
    void delta_shouldMarkDisconnected_whenTheWriteFails() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenThrow(new IOException("broken pipe"));
        SseWriterHttp writer = new SseWriterHttp(response, protocol);

        assertThrows(IOException.class, () -> writer.assistantDelta("你好"),
                "增量写不出去仍然要抛:ChatService 靠它当场中止这一轮");

        assertFalse(writer.stillConnected());
    }

    @Test
    @DisplayName("没断的时候一切照常:事件按名字与载荷写出去")
    void events_shouldStillBeWritten() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SseWriterHttp writer = new SseWriterHttp(response, protocol);

        assertDoesNotThrow(() -> {
            writer.assistantDelta("你好");
            writer.toolStarted("searchArticles", Map.of("keyword", "java"));
            writer.ended();
        });

        // 写出去的是 UTF-8 字节(响应头那边的 charset 由 controller 设),
        // 所以这里也按 UTF-8 解回来 —— MockHttpServletResponse 的默认字符集是 ISO-8859-1
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(body.contains("event:message\ndata:{\"content\":\"你好\"}\n\n"), "实际: " + body);
        assertTrue(body.contains("event:tool\ndata:{\"name\":\"searchArticles\""), "实际: " + body);
        assertTrue(body.contains("event:done\ndata:{}\n\n"), "实际: " + body);
        assertTrue(writer.stillConnected());
    }
}
