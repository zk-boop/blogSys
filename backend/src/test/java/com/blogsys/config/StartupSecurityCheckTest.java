package com.blogsys.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 装配层:证明被启动流程调用的那个 bean 确实**读的是 Environment 里的 active profiles**,
 * 而不是某个写死的判断。判定本身在 {@link StartupSecurityPolicyTest} 里测。
 *
 * <p>Environment 用 Mockito 顶掉 —— 这样不用拉起 Spring 上下文,也就不会碰数据库。
 */
@ExtendWith(MockitoExtension.class)
class StartupSecurityCheckTest {

    private static final String STRONG_SECRET = "0123456789abcdef0123456789abcdef0123456789";

    @Mock
    private Environment environment;

    @Test
    @DisplayName("非 local profile + 默认密钥 → 抛异常(应用起不来)")
    void nonLocalWithDefaultSecret_shouldThrow() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        StartupSecurityCheck check = new StartupSecurityCheck(
                environment, StartupSecurityPolicy.DEFAULT_JWT_SECRET);

        IllegalStateException ex = assertThrows(IllegalStateException.class, check::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("JWT_SECRET"), ex.getMessage());
        verify(environment).getActiveProfiles();
    }

    @Test
    @DisplayName("非 local profile + 短密钥 → 抛异常")
    void nonLocalWithShortSecret_shouldThrow() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        StartupSecurityCheck check = new StartupSecurityCheck(environment, "too-short");

        assertThrows(IllegalStateException.class, check::afterPropertiesSet);
    }

    @Test
    @DisplayName("非 local profile + 强密钥 → 放行")
    void nonLocalWithStrongSecret_shouldPass() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        StartupSecurityCheck check = new StartupSecurityCheck(environment, STRONG_SECRET);

        assertDoesNotThrow(check::afterPropertiesSet);
    }

    @Test
    @DisplayName("local profile + 默认密钥 → 放行(本地开发不被拦)")
    void localWithDefaultSecret_shouldPass() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"local"});

        StartupSecurityCheck check = new StartupSecurityCheck(
                environment, StartupSecurityPolicy.DEFAULT_JWT_SECRET);

        assertDoesNotThrow(check::afterPropertiesSet);
    }

    @Test
    @DisplayName("没有任何 active profile → 一律按非 local 拦下")
    void withoutProfiles_shouldThrow() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{});

        StartupSecurityCheck check = new StartupSecurityCheck(
                environment, StartupSecurityPolicy.DEFAULT_JWT_SECRET);

        assertThrows(IllegalStateException.class, check::afterPropertiesSet);
    }
}
