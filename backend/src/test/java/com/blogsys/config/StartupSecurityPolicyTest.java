package com.blogsys.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 密钥的启动判定。
 *
 * <p>本仓库没有 {@code @SpringBootTest},所以这里断言的是判定本身 —— 这也是把规则
 * 从容器里抽出来的全部理由:真启动一次才能验证的规则,等于没人验证。
 */
class StartupSecurityPolicyTest {

    /** 46 字符的强密钥(长度超过 32 的硬门槛)。 */
    private static final String STRONG_SECRET = "0123456789abcdef0123456789abcdef0123456789";

    private static final String[] PROD = {"prod"};
    private static final String[] LOCAL = {"local"};
    private static final String[] NO_PROFILE = {};

    @Test
    @DisplayName("非 local + 仓库默认密钥 → 拒绝启动,且报错里给出能照抄的补救命令")
    void nonLocal_withRepositoryDefaultSecret_shouldRefuseToStart() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                StartupSecurityPolicy.assertJwtSecretUsable(
                        StartupSecurityPolicy.DEFAULT_JWT_SECRET, PROD));

        assertTrue(ex.getMessage().contains("JWT_SECRET"), "报错必须点名 JWT_SECRET:" + ex.getMessage());
        assertTrue(ex.getMessage().contains("openssl rand -base64 48"),
                "报错必须给出能直接照抄的命令:" + ex.getMessage());
        // 第一条就是可照抄的补救指令,补充说明只能在它后面
        assertTrue(ex.getMessage().startsWith(StartupSecurityPolicy.JWT_SECRET_MISSING_MESSAGE),
                "报错开头应是完整的补救指令:" + ex.getMessage());
    }

    @Test
    @DisplayName("非 local + 短密钥 → 拒绝启动")
    void nonLocal_withTooShortSecret_shouldRefuseToStart() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                StartupSecurityPolicy.assertJwtSecretUsable("short-secret", PROD));

        assertTrue(ex.getMessage().contains("JWT_SECRET"), ex.getMessage());
        assertTrue(ex.getMessage().contains("openssl rand -base64 48"), ex.getMessage());
        assertTrue(ex.getMessage().contains("12"), "报错里要带上实际长度:" + ex.getMessage());
    }

    @Test
    @DisplayName("非 local + 空密钥(export JWT_SECRET= 这种)→ 拒绝启动,不能当成\"已配置\"")
    void nonLocal_withBlankSecret_shouldRefuseToStart() {
        assertThrows(IllegalStateException.class, () ->
                StartupSecurityPolicy.assertJwtSecretUsable("", PROD));
        assertThrows(IllegalStateException.class, () ->
                StartupSecurityPolicy.assertJwtSecretUsable("   ", PROD));
        assertThrows(IllegalStateException.class, () ->
                StartupSecurityPolicy.assertJwtSecretUsable(null, PROD));
    }

    @Test
    @DisplayName("一个 profile 都没设 → 按非 local 处理(保守方向,免得漏拦)")
    void withoutAnyProfile_shouldBeTreatedAsNonLocal() {
        assertThrows(IllegalStateException.class, () ->
                StartupSecurityPolicy.assertJwtSecretUsable(
                        StartupSecurityPolicy.DEFAULT_JWT_SECRET, NO_PROFILE));
    }

    @Test
    @DisplayName("非 local + 强密钥 → 通过")
    void nonLocal_withStrongSecret_shouldPass() {
        assertDoesNotThrow(() ->
                StartupSecurityPolicy.assertJwtSecretUsable(STRONG_SECRET, PROD));
        assertDoesNotThrow(() ->
                StartupSecurityPolicy.assertJwtSecretUsable(STRONG_SECRET, new String[]{"prod", "mysql"}));
    }

    @Test
    @DisplayName("长度刚好 32 通过、31 拒绝 —— 门槛是闭区间,别把边界写偏")
    void lengthBoundary_isInclusive() {
        String exactly32 = "0123456789abcdef0123456789abcdef";
        assertDoesNotThrow(() ->
                StartupSecurityPolicy.assertJwtSecretUsable(exactly32, PROD));
        assertThrows(IllegalStateException.class, () ->
                StartupSecurityPolicy.assertJwtSecretUsable(exactly32.substring(1), PROD));
    }

    @Test
    @DisplayName("local + 仓库默认密钥 → 通过(本地开发与现有流程不受影响)")
    void local_withRepositoryDefaultSecret_shouldPass() {
        assertDoesNotThrow(() ->
                StartupSecurityPolicy.assertJwtSecretUsable(
                        StartupSecurityPolicy.DEFAULT_JWT_SECRET, LOCAL));
    }

    @Test
    @DisplayName("local + 短密钥 → 通过;local 混在多个 profile 里也算 local")
    void local_shouldBeExemptRegardlessOfSecret() {
        assertDoesNotThrow(() -> StartupSecurityPolicy.assertJwtSecretUsable("dev", LOCAL));
        assertDoesNotThrow(() -> StartupSecurityPolicy.assertJwtSecretUsable(null, LOCAL));
        assertDoesNotThrow(() -> StartupSecurityPolicy.assertJwtSecretUsable(
                StartupSecurityPolicy.DEFAULT_JWT_SECRET, new String[]{"mysql", "local"}));
        assertTrue(StartupSecurityPolicy.isLocalProfile(new String[]{"mysql", "local"}));
        assertFalse(StartupSecurityPolicy.isLocalProfile(new String[]{"prod"}));
        assertFalse(StartupSecurityPolicy.isLocalProfile(null));
    }

    @Test
    @DisplayName("与 profile 无关的密钥质量判断:local 放行但要能被识别出\"是不可用密钥\"")
    void isSecretUsable_shouldMirrorTheThresholds() {
        assertFalse(StartupSecurityPolicy.isSecretUsable(StartupSecurityPolicy.DEFAULT_JWT_SECRET));
        assertFalse(StartupSecurityPolicy.isSecretUsable(""));
        assertFalse(StartupSecurityPolicy.isSecretUsable(null));
        assertFalse(StartupSecurityPolicy.isSecretUsable("0123456789abcdef0123456789abcdef".substring(1)));
        assertTrue(StartupSecurityPolicy.isSecretUsable(STRONG_SECRET));
        assertTrue(StartupSecurityPolicy.isSecretUsable("0123456789abcdef0123456789abcdef"));
    }
}
