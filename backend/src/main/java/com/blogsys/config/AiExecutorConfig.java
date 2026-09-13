package com.blogsys.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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

    /**
     * 同时进行的对话数。
     *
     * <p>{@code corePoolSize} 就是它 —— {@code ThreadPoolExecutor} 只在**队列满之后**才
     * 扩容到 {@code maxPoolSize},所以「core 2 / max 4 / queue 20」的实际含义是
     * **稳态并发 2**,另外 20 个静静地排队,而 {@code maxPoolSize=4} 几乎永远用不到:
     * 一个写着 4、跑着 2 的配置,读的人会照它做容量判断。
     *
     * <p>现在 4 就是 4。一次对话几乎全程在等模型(IO),4 个线程很便宜;
     * 超出的部分排队,排满 16 个就明确拒绝(见 {@code ChatController} 的收尾路径)。
     */
    private static final int CONCURRENT_CHATS = 4;

    /** 排队上限。4 + 16 = 同时在飞 20 场对话,与这个池此前的总容量一致。 */
    private static final int WAITING_CHATS = 16;

    @Bean("chatExecutor")
    public Executor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CONCURRENT_CHATS);
        executor.setMaxPoolSize(CONCURRENT_CHATS);
        executor.setQueueCapacity(WAITING_CHATS);
        executor.setThreadNamePrefix("ai-chat-");
        executor.setTaskDecorator(task ->
                new DelegatingSecurityContextRunnable(task, SecurityContextHolder.getContext()));
        executor.initialize();
        return executor;
    }

    /**
     * 心跳的调度器:一场对话一个定时任务,每 10 秒往客户端写一个 SSE 注释帧。
     *
     * <p>单线程够用 —— 心跳是一行文本,而它的价值在于**按期发生**,不在于并发。
     * 守护线程,所以它绝不会拖住关闭。
     */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService chatHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-chat-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}
