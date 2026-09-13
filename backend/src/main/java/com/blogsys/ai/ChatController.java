package com.blogsys.ai;

import com.blogsys.visibility.Viewer;
import com.blogsys.visibility.ViewerSource;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Validated
@Slf4j
public class ChatController {

    private final ChatService chatService;
    private final Executor chatExecutor;
    private final ViewerSource viewerSource;
    private final SseProtocol sseProtocol;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void chat(@Valid @RequestBody ChatRequest request,
                     HttpServletRequest servletRequest,
                     HttpServletResponse servletResponse) {
        servletResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        servletResponse.setCharacterEncoding("UTF-8");
        AsyncContext asyncContext = servletRequest.startAsync();
        asyncContext.setTimeout(0L);

        List<ChatMessage> messages = new ArrayList<>();
        for (ChatRequest.ChatItem item : request.messages()) {
            if ("assistant".equals(item.role())) {
                messages.add(new ChatMessage("assistant", item.content(), null, null));
            } else {
                messages.add(ChatMessage.user(item.content()));
            }
        }
        // 提问者必须在**请求线程上**解析:下面那个 lambda 跑在池线程上,那里没有请求上下文,
        // 而身份一旦丢失就只会静默退化成匿名 —— 不报错,只是答错。
        //
        // 这是「谁在问」穿过 seam 的那一步。它决定模型能看到哪些工具;域层(可见性模块)
        // 读到的 viewer 则由 AiExecutorConfig 的 TaskDecorator 从同一个请求线程带过去。
        Viewer viewer = viewerSource.current();
        try {
            chatExecutor.execute(() -> {
                try {
                    chatService.chat(messages, new SseWriterHttp(servletResponse, sseProtocol), viewer);
                } finally {
                    asyncContext.complete();
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列也满了。这是唯一一条**必须由提交方自己收尾**的路径:
            // 拒绝时那个 lambda 从不执行,于是 `asyncContext.complete()` 也从不执行,
            // 而 `setTimeout(0L)` 让请求本身没有期限 —— 客户端什么也收不到,只会一直等。
            // 一次「服务器忙」在客户端上表现得和「AI 卡住了」一模一样。
            log.warn("AI 对话被拒绝(线程池已满): {}", e.getMessage());
            try {
                new SseWriterHttp(servletResponse, sseProtocol).failed("AI 助手当前忙,请稍后重试");
            } finally {
                asyncContext.complete();
            }
        }
    }

    public record ChatRequest(
            @NotEmpty(message = "消息不能为空")
            @Size(max = 50, message = "历史消息过多")
            @Valid List<ChatItem> messages) {

        public record ChatItem(
                String role,
                @NotEmpty(message = "消息内容不能为空")
                @Size(max = 4000, message = "单条消息过长")
                String content) {
        }
    }
}
