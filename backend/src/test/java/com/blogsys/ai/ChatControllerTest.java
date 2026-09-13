package com.blogsys.ai;

import com.blogsys.visibility.Viewer;
import com.blogsys.visibility.ViewerSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 对话的**生命周期**路径:谁开始,谁收尾。
 *
 * <p>正常路径在 `ChatService` 那边测;这里测的是控制器独有的那两件事 ——
 * 线程池拒绝任务时谁负责把响应结束掉,以及心跳什么时候开始、什么时候停。
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatService chatService;
    @Mock
    private ViewerSource viewerSource;
    @Mock
    private ScheduledExecutorService heartbeatScheduler;
    @Mock
    private ScheduledFuture<?> heartbeatFuture;

    private final SseProtocol sseProtocol = new SseProtocol(new ObjectMapper());

    private ChatController controller(Executor executor) {
        return new ChatController(chatService, executor, viewerSource, sseProtocol, heartbeatScheduler);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAsyncSupported(true);
        return request;
    }

    private ChatController.ChatRequest ask(String content) {
        return new ChatController.ChatRequest(
                List.of(new ChatController.ChatRequest.ChatItem("user", content)));
    }

    private Executor rejecting() {
        return task -> {
            throw new RejectedExecutionException("队列已满");
        };
    }

    @Test
    @DisplayName("线程池拒绝时客户端仍收到一个错误事件,而不是一直等")
    void chat_shouldReportBusy_whenTheExecutorRejects() throws Exception {
        when(viewerSource.current()).thenReturn(Viewer.anonymous());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller(rejecting()).chat(ask("你好"), request(), response);

        String body = response.getContentAsString();
        assertTrue(body.contains("event:error"), "要发出一个错误帧,实际: " + body);
        assertTrue(body.contains("稍后重试"), "要说人话,实际: " + body);
    }

    @Test
    @DisplayName("拒绝时不发 done —— 那会让客户端以为回答完整")
    void chat_shouldNotSendDone_whenTheExecutorRejects() throws Exception {
        when(viewerSource.current()).thenReturn(Viewer.anonymous());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller(rejecting()).chat(ask("你好"), request(), response);

        assertTrue(!response.getContentAsString().contains("event:done"),
                "error 与 done 互斥,实际: " + response.getContentAsString());
    }

    @Test
    @DisplayName("对话一开始就发心跳,结束后立刻停 —— 响应头因此不必等到第一个 token")
    void chat_shouldScheduleHeartbeats_andCancelThem() throws Exception {
        when(viewerSource.current()).thenReturn(Viewer.anonymous());
        MockHttpServletResponse response = new MockHttpServletResponse();
        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        // doReturn 而不是 when(...).thenReturn(...):后者会把捕获器与 ScheduledFuture<?>
        // 的通配符一起推断,两个 capture 对不上(编译期就会红)
        doReturn(heartbeatFuture).when(heartbeatScheduler)
                .scheduleAtFixedRate(heartbeat.capture(), anyLong(), anyLong(), any());
        // 对话进行中,心跳「到点」一次 —— 这是它在真实世界里每 10 秒做的事
        doAnswer(invocation -> {
            heartbeat.getValue().run();
            return null;
        }).when(chatService).chat(any(), any(), any());

        controller(Runnable::run).chat(ask("你好"), request(), response);

        assertTrue(response.getContentAsString().contains(": ping\n\n"),
                "心跳要真的写到响应上,实际: " + response.getContentAsString());
        // 首次延迟为 0 是有行为后果的:响应头在第一次写就 flush,
        // 而不是等模型吐出第一个 token(那可能是一分钟后)。
        verify(heartbeatScheduler).scheduleAtFixedRate(any(), eq(0L), eq(10L), eq(TimeUnit.SECONDS));
        verify(heartbeatFuture).cancel(false);
    }

    @Test
    @DisplayName("被拒绝的那条路径不留心跳 —— 它从没跑起来,也就没人去停它")
    void chat_shouldNotScheduleHeartbeats_whenTheExecutorRejects() throws Exception {
        when(viewerSource.current()).thenReturn(Viewer.anonymous());

        controller(rejecting()).chat(ask("你好"), request(), new MockHttpServletResponse());

        verify(heartbeatScheduler, org.mockito.Mockito.never())
                .scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
    }
}
