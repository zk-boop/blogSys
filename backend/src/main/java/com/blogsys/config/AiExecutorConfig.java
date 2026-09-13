package com.blogsys.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.Executor;

/**
 * AI 对话异步执行线程池,SSE 流式输出需在独立线程中运行。
 *
 * <p><b>提交任务时把 SecurityContext 一起带过去。</b>没有这一步,池线程上
 * {@code SecurityContextHolder} 就是空的 —— AI 工具路径读到的 viewer 一律退化成匿名,
 * 于是「提问者自己的草稿看不见」以及「收藏/点赞状态静默答成 false」这两类错误持续发生,
 * 而且从不报错(见 {@code docs/architecture.md} §5.1/§5.2)。
 *
 * <p>捕获发生在 {@code execute()} 那一刻,也就是 servlet 请求线程上,拿到的是
 * {@code JwtAuthFilter} 刚放进去的那份。随后 {@code SecurityContextHolderFilter}
 * 清空的是 ThreadLocal 绑定,不影响这个已被捕获的对象。
 *
 * <p>这里不用 {@code DelegatingSecurityContextAsyncTaskExecutor} 包装整个池:那会让 bean
 * 的运行时类型变成包装器,{@code ThreadPoolTaskExecutor} 不再是 bean,从而失去 Spring 的
 * destroy 回调 —— 池线程不会在关闭时回收。只补 TaskDecorator 这一段即可。
 */
@Configuration
public class AiExecutorConfig {

    @Bean("chatExecutor")
    public Executor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("ai-chat-");
        executor.setTaskDecorator(task ->
                new DelegatingSecurityContextRunnable(task, SecurityContextHolder.getContext()));
        executor.initialize();
        return executor;
    }
}
