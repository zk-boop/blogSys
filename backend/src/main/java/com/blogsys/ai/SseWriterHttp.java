package com.blogsys.ai;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 基于 HttpServletResponse 的 SSE 写入器:逐事件 flush,由 AsyncContext.complete() 终结响应。 */
public class SseWriterHttp implements SseWriter {

    private final HttpServletResponse response;

    public SseWriterHttp(HttpServletResponse response) {
        this.response = response;
    }

    @Override
    public void event(String name, String dataJson) throws IOException {
        byte[] payload = ("event:" + name + "\ndata:" + dataJson + "\n\n")
                .getBytes(StandardCharsets.UTF_8);
        OutputStream out = response.getOutputStream();
        out.write(payload);
        out.flush();
    }
}
