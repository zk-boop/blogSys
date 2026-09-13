package com.blogsys.ai;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class SseWriterHttp implements ChatEgress {

    /** 心跳帧的文本。与 {@code docs/api.md} 那张表里写的是同一个东西。 */
    private static final String HEARTBEAT = "ping";

    private final HttpServletResponse response;
    private final SseProtocol protocol;

    /**
     * 上一次写有没有成功。**这是「客户端还在不在」的全部依据** —— 一个已经断开的连接
     * 不会以别的方式通知服务端(servlet 的 error/complete 回调都不会因为对端关掉而触发),
     * 只有真的写一次才知道。所以心跳不只是保活,它是唯一按期发生的探测。
     *
     * <p>一个方向:一旦写失败就永远认为断了。没有「又连上了」这回事 —— 这是一次 POST,
     * 客户端没有重新连回来的通道。
     */
    private volatile boolean connected = true;

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
        bestEffort(protocol.failed(message));
    }

    @Override
    public void ended() throws IOException {
        write(protocol.done());
    }

    @Override
    public void keepAlive() {
        bestEffort(protocol.comment(HEARTBEAT));
    }

    @Override
    public boolean stillConnected() {
        return connected;
    }

    /**
     * 写不出去就算了 —— 这两条通道是**尽力而为**的:客户端可能早已断开,那时无话可说,
     * 也不该再为「没通知到」抛一次。心跳更是如此:它的产出是 {@link #stillConnected()}
     * 这个事实,而不是一个异常 —— 抛出去只会打死调度它的那个定时任务。
     */
    private void bestEffort(String text) {
        try {
            write(text);
        } catch (Exception ignored) {
            // 已经记在 connected 里了
        }
    }

    private void write(String text) throws IOException {
        try {
            // getOutputStream() 也要在 try 里:响应已经提交或关闭时,抛出来的就是它自己
            // (Tomcat 上是 IllegalStateException),而它对「客户端还在不在」是同一个答案。
            OutputStream out = response.getOutputStream();
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException | RuntimeException e) {
            if (connected) {
                // 只在**第一次**失败时留一行:这行日志回答的就是「对话怎么停的」
                // ——在此之前,「用户点了停止」在日志里什么也留不下。
                log.debug("往客户端写失败,判定为已断开: {}", e.getMessage());
            }
            connected = false;
            throw e;
        }
    }
}
