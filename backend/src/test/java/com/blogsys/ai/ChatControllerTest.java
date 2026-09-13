package com.blogsys.ai;

import com.blogsys.visibility.Viewer;
import com.blogsys.visibility.ViewerSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * AI 对话的**收尾**路径。
 *
 * <p>正常路径在 `ChatService` 那边测;这里测的是那条最容易漏的:线程池拒绝任务时,
 * 谁负责把响应结束掉。拒绝时那个 lambda 从不执行,于是 `asyncContext.complete()`
 * 也从不执行,而 `setTimeout(0L)` 让请求本身没有期限 —— 客户端什么也收不到,
 * 只会一直等。**一次「服务器忙」在客户端上表现得和「AI 卡住了」一模一样。**
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatService chatService;
    @Mock
    private ViewerSource viewerSource;

    private final SseProtocol sseProtocol = new SseProtocol(new ObjectMapper());

    private ChatController controller(Executor executor) {
        return new ChatController(chatService, executor, viewerSource, sseProtocol);
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

    @Test
    @DisplayName("线程池拒绝时客户端仍收到一个错误事件,而不是一直等")
    void chat_shouldReportBusy_whenTheExecutorRejects() throws Exception {
        when(viewerSource.current()).thenReturn(Viewer.anonymous());
        MockHttpServletResponse response = new MockHttpServletResponse();
        Executor rejecting = task -> {
            throw new RejectedExecutionException("队列已满");
        };

        controller(rejecting).chat(ask("你好"), request(), response);

        String body = response.getContentAsString();
        assertTrue(body.contains("event:error"), "要发出一个错误帧,实际: " + body);
        assertTrue(body.contains("稍后重试"), "要说人话,实际: " + body);
    }

    @Test
    @DisplayName("拒绝时不发 done —— 那会让客户端以为回答完整")
    void chat_shouldNotSendDone_whenTheExecutorRejects() throws Exception {
        when(viewerSource.current()).thenReturn(Viewer.anonymous());
        MockHttpServletResponse response = new MockHttpServletResponse();
        Executor rejecting = task -> {
            throw new RejectedExecutionException("队列已满");
        };

        controller(rejecting).chat(ask("你好"), request(), response);

        assertTrue(!response.getContentAsString().contains("event:done"),
                "error 与 done 互斥,实际: " + response.getContentAsString());
    }
}
