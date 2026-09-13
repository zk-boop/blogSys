package com.blogsys.config;

import com.blogsys.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        Executor executor = new AiExecutorConfig().chatExecutor();
        authenticate(7L, "USER");
        AtomicReference<Thread> first = new AtomicReference<>();
        onPoolThread(executor, () -> {
            first.set(Thread.currentThread());
            return null;
        });
        SecurityContextHolder.clearContext();

        // 池里只有两个 worker,且都在跑;后续任务必然被它们接手 —— 直到命中同一个线程为止。
        // 命中是断言的前提,所以要显式要求它发生,而不是碰运气通过。
        for (int attempt = 0; attempt < 20; attempt++) {
            AtomicReference<Thread> thread = new AtomicReference<>();
            Object authentication = onPoolThread(executor, () -> {
                thread.set(Thread.currentThread());
                return SecurityContextHolder.getContext().getAuthentication();
            });
            if (thread.get() == first.get()) {
                assertNull(authentication, "复用的池线程上还留着上一个人的身份");
                return;
            }
        }
        fail("20 次都没落到同一个池线程上 —— 这条断言失去了分辨力,请修好它而不是删掉它");
    }
}
