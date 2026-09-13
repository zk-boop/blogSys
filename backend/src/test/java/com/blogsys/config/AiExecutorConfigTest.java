package com.blogsys.config;

import com.blogsys.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 会话跑在 {@code chatExecutor} 的线程池上,而 {@code JwtAuthFilter} 只把 principal
 * 放进了 servlet 请求线程的 {@code SecurityContextHolder}。
 *
 * <p>没有这个传播,池线程上的身份一律是空的 —— 而这条路径上<b>没有任何一处会因此报错</b>,
 * 它只是答错:普通登录用户能从 AI 问到 ADMIN 专属的全站统计(D1),
 * 收藏/点赞状态被静默答成「没有」(§5.2)。
 *
 * <p>本测试用真实线程池证明它被修好了。断言的是行为,不是「某段配置文本存在」——
 * 后者在有人把 TaskDecorator 挪走之后仍然会通过。
 */
class AiExecutorConfigTest {

    @AfterEach
    void clearContext() {
        // SecurityContextHolder 是线程局部的,寿命比测试类长(见 docs/lessons.md)
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(Long id, String role) {
        LoginUser loginUser = new LoginUser(id, "someone", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    /** 在池线程上执行一次 {@code task},返回它的结果。 */
    private <T> T onPoolThread(Executor executor, Supplier<T> task) throws InterruptedException {
        AtomicReference<T> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                result.set(task.get());
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "池线程没有在 5 秒内跑完");
        return result.get();
    }

    @Test
    @DisplayName("提交者的身份跟到池线程上")
    void poolThread_shouldSeeTheSubmittingThreadsPrincipal() throws Exception {
        authenticate(7L, "USER");

        Object principal = onPoolThread(new AiExecutorConfig().chatExecutor(),
                () -> SecurityContextHolder.getContext().getAuthentication() == null
                        ? null
                        : SecurityContextHolder.getContext().getAuthentication().getPrincipal());

        assertInstanceOf(LoginUser.class, principal, "池线程上应该是提问者本人,而不是空的");
        assertEquals(7L, ((LoginUser) principal).getId());
    }

    @Test
    @DisplayName("管理员身份照传 —— 「谁是管理员」在池线程上必须与请求线程一致")
    void poolThread_shouldSeeAdminRole() throws Exception {
        authenticate(1L, "ADMIN");

        String role = onPoolThread(new AiExecutorConfig().chatExecutor(),
                () -> ((LoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                        .getRole());

        assertEquals("ADMIN", role);
    }

    @Test
    @DisplayName("没有登录时池线程上仍是匿名,不抛异常")
    void poolThread_shouldStayAnonymous_whenNothingWasAuthenticated() throws Exception {
        Object authentication = onPoolThread(new AiExecutorConfig().chatExecutor(),
                () -> SecurityContextHolder.getContext().getAuthentication());

        assertNull(authentication);
    }

    @Test
    @DisplayName("对照:裸线程池会丢掉身份 —— 证明上面几条断言确实有分辨力")
    void plainPool_shouldLoseTheIdentity() throws Exception {
        authenticate(7L, "USER");
        ExecutorService plain = Executors.newSingleThreadExecutor();
        try {
            assertNull(onPoolThread(plain, () -> SecurityContextHolder.getContext().getAuthentication()),
                    "如果这里不为 null,说明上面的测试并没有在验证传播这件事");
        } finally {
            plain.shutdownNow();
        }
    }

    @Test
    @DisplayName("跑完不留身份:池线程会被复用,上一个人的身份绝不能留给下一个人")
    void poolThread_shouldNotKeepTheIdentity_afterTheTask() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AiExecutorConfig().chatExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            // 先把 4 个 worker 全部占住 —— 它们在**提交那一刻**各自捕获了 7 号的身份,
            // 于是「跑过带身份任务的线程」是确定的 4 个,而不是靠反复提交去撞其中一个。
            authenticate(7L, "USER");
            CountDownLatch occupied = new CountDownLatch(4);
            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    occupied.countDown();
                    awaitQuietly(release);
                });
            }
            assertTrue(occupied.await(5, TimeUnit.SECONDS), "4 个 worker 没都跑起来");

            // 探针在上下文已清空时提交 ⇒ 不带身份;池已满,它们只会被上面那 4 个线程接手
            // (队列非空时 ThreadPoolExecutor 不会再开新线程),所以每一条断言都落在
            // 「刚刚跑过带身份任务的线程」上。
            SecurityContextHolder.clearContext();
            List<Object> seen = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch probed = new CountDownLatch(4);
            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    try {
                        seen.add(SecurityContextHolder.getContext().getAuthentication());
                    } finally {
                        probed.countDown();
                    }
                });
            }
            release.countDown();

            assertTrue(probed.await(5, TimeUnit.SECONDS), "探针任务没有跑完");
            assertEquals(4, seen.size());
            for (Object authentication : seen) {
                assertNull(authentication, "复用的池线程上还留着上一个人的身份");
            }
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("说 4 路并发就是 4 路 —— 不是「core 2,另外 20 个静静排队」")
    void chatPool_shouldRunFourConversationsAtOnce() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AiExecutorConfig().chatExecutor();
        CountDownLatch running = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queued = new AtomicBoolean(false);
        try {
            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    running.countDown();
                    awaitQuietly(release);
                });
            }
            assertTrue(running.await(5, TimeUnit.SECONDS),
                    "只有不到 4 场对话同时跑起来 —— 旧配置(core 2 / queue 20)正是这样:"
                            + "配置写着 4,实际稳态并发是 2");

            executor.execute(() -> queued.set(true));
            Thread.sleep(300);
            assertFalse(queued.get(), "第 5 场该在排队,而不是插进来");

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!queued.get() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(queued.get(), "放行之后排队的那一场该跑起来");
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    /** 等一个闩,被中断时如实把中断标记放回去 —— 测试里不需要更复杂的处理。 */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
