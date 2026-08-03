package com.blogsys.ai;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Validated
public class ChatController {

    private final ChatService chatService;
    private final Executor chatExecutor;

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
        chatExecutor.execute(() -> {
            try {
                chatService.chat(messages, new SseWriterHttp(servletResponse));
            } finally {
                asyncContext.complete();
            }
        });
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
