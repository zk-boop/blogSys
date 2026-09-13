package com.blogsys.ai;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 把事件写到 HTTP 响应上的 adapter —— 基于 {@code HttpServletResponse},逐事件 flush,
 * 由 {@code AsyncContext.complete()} 终结响应。
 *
 * <p>它只做「把已经算好的文本变成字节」这一件事:事件名、载荷形状与组帧都在
 * {@link SseProtocol} 里。此前组帧长在这里,于是测试要验证「发出去的是什么」时,
 * 只能把自己序列化过的 JSON 再解析回来。
 */
public class SseWriterHttp implements ChatEgress {

    private final HttpServletResponse response;
    private final SseProtocol protocol;

    public SseWriterHttp(HttpServletResponse response, SseProtocol protocol) {
        this.response = response;
        this.protocol = protocol;
    }

    @Override
    public void assistantDelta(String delta) throws IOException {
        write(protocol.delta(delta));
    }

    @Override
    public void toolStarted(String name, Map<String, Object> args) throws IOException {
        write(protocol.toolStarted(name, args));
    }

    @Override
    public void toolFinished(String name, Map<String, Object> args, String result) throws IOException {
        write(protocol.toolFinished(name, args, result));
    }

    @Override
    public void failed(String message) {
        try {
            write(protocol.failed(message));
        } catch (IOException ignored) {
            // 客户端已断开 —— 这时候错误通知没有收件人,不该再抛
        }
    }

    @Override
    public void ended() throws IOException {
        write(protocol.done());
    }

    private void write(String text) throws IOException {
        OutputStream out = response.getOutputStream();
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
