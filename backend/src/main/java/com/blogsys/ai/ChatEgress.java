package com.blogsys.ai;

import java.io.IOException;
import java.util.Map;

/**
 * 对话的出口 —— **行为层**的 seam。
 *
 * <p>此前它是 {@code SseWriter.event(name, dataJson)}:只抽象了帧外壳,没抽象载荷。
 * 于是四个事件名、四种载荷形状以及它们的顺序,必须被每个调用者、adapter、测试、
 * 以及浏览器解析器各自知道一遍 —— 这份知识当时被实现了四次
 * ({@code ChatService}、{@code SseWriterHttp}、测试的 CapturingWriter、
 * {@code frontend/src/api/ai.js} 的 dispatch)。seam 画错了层,知识就守不住。
 *
 * <p>这里只说<b>发生了什么</b>,不说它长什么样。事件名、载荷形状与 SSE 组帧归
 * {@link SseProtocol};把它写到响应上的是 {@code SseWriterHttp} 那一个 adapter。
 *
 * <p>测试因此可以断言行为,而不必把自己刚序列化出来的 JSON 再解析回去。
 */
public interface ChatEgress {

    /** 助手的回答又长了一点。 */
    void assistantDelta(String delta) throws IOException;

    /** 模型决定调用一个工具 —— 参数是它给出的。 */
    void toolStarted(String name, Map<String, Object> args) throws IOException;

    /** 工具调用结束,{@code result} 是要交回给模型的紧凑 JSON。 */
    void toolFinished(String name, Map<String, Object> args, String result) throws IOException;

    /**
     * 这次对话失败了,调用方不再期望后续事件。
     *
     * <p>不声明受检异常:这个通道是<b>尽力而为</b>的 —— 客户端可能早已断开,
     * 那时无话可说,也不该再为「没通知到」抛一次。
     */
    void failed(String message);

    /** 回答完整结束。只有正常走完才发 —— 它是客户端唯一可依赖的收尾凭证。 */
    void ended() throws IOException;

    /**
     * 一切正常,只是暂时没有说话 —— 让连接按期有字节,顺便<b>探一次客户端还在不在</b>。
     *
     * <p>它是「存活」这件事唯一的证据来源。SSE 的响应头要到第一次写才 flush,而两次
     * 写之间可以安静很久(一次模型轮次最长 120 秒);这段时间里「AI 正在想」与
     * 「连接已经死了」在两端都不可区分,而浏览器上的「停止」也因此传不到服务端 ——
     * 连接确实断了,但服务端没有理由去发现。<b>写不出去,才是断连的证据。</b>
     *
     * <p>尽力而为,不声明受检异常:客户端可能早已断开,那时无话可说。失败会被记进
     * {@link #stillConnected()},由对话内核在轮与轮之间读它。
     */
    void keepAlive();

    /**
     * 客户端还在不在。判断依据只有一个:上一次写有没有成功。
     *
     * <p>它由 adapter 持有,由 {@link #keepAlive()} 按期刷新 —— 这是「取消」在服务端的
     * 传播路径:用户点停止 ⇒ 连接断 ⇒ 心跳写失败 ⇒ 这里变成 false ⇒ 对话停止。
     */
    boolean stillConnected();
}
