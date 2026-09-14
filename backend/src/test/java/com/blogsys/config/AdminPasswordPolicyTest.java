package com.blogsys.config;

import com.blogsys.config.AdminPasswordPolicy.ResolvedAdminPassword;
import com.blogsys.config.AdminPasswordPolicy.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理员初始口令的来源判定与随机生成。
 *
 * <p>随机源用 Mockito 顶掉:这样"该不该随机"与"随机出来的是不是好口令"可以分开断言 ——
 * 前者必须是确定的,后者才需要真的掷骰子。
 */
@ExtendWith(MockitoExtension.class)
class AdminPasswordPolicyTest {

    /** 易混字符,生成的口令里一个都不该出现(要人工从日志抄写)。 */
    private static final String AMBIGUOUS = "0Oo1lI";

    @Mock
    private Supplier<String> generator;

    @Test
    @DisplayName("给了 BLOGSYS_ADMIN_PASSWORD → 用它,且完全不碰随机生成")
    void resolve_shouldPreferEnvPassword() {
        ResolvedAdminPassword resolved =
                AdminPasswordPolicy.resolve("from-ops-8sJ2kQ", false, generator);

        assertEquals("from-ops-8sJ2kQ", resolved.password());
        assertEquals(Source.ENV, resolved.source());
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("环境变量优先于 local 默认口令")
    void resolve_shouldPreferEnvPassword_evenInLocalProfile() {
        ResolvedAdminPassword resolved =
                AdminPasswordPolicy.resolve("from-ops-8sJ2kQ", true, generator);

        assertEquals(Source.ENV, resolved.source());
        assertEquals("from-ops-8sJ2kQ", resolved.password());
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("环境变量是空白(shell 里的 export X=)→ 视为没给")
    void resolve_shouldTreatBlankEnvAsAbsent() {
        when(generator.get()).thenReturn("GENERATED-PASSWORD");

        assertEquals(Source.LOCAL_DEFAULT, AdminPasswordPolicy.resolve("   ", true, generator).source());
        assertEquals(Source.GENERATED, AdminPasswordPolicy.resolve("", false, generator).source());
    }

    @Test
    @DisplayName("local 且没给环境变量 → 仍然是 admin123(本地流程不变)")
    void resolve_shouldUseLocalDefault_inLocalProfile() {
        ResolvedAdminPassword resolved = AdminPasswordPolicy.resolve(null, true, generator);

        assertEquals(AdminPasswordPolicy.LOCAL_DEFAULT_ADMIN_PASSWORD, resolved.password());
        assertEquals("admin123", resolved.password());
        assertEquals(Source.LOCAL_DEFAULT, resolved.source());
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("非 local 且没给环境变量 → 随机生成,不再用 admin123")
    void resolve_shouldGenerate_inNonLocalProfile() {
        when(generator.get()).thenReturn("GENERATED-PASSWORD");

        ResolvedAdminPassword resolved = AdminPasswordPolicy.resolve(null, false, generator);

        assertEquals(Source.GENERATED, resolved.source());
        assertEquals("GENERATED-PASSWORD", resolved.password());
        assertNotEquals("admin123", resolved.password());
    }

    @Test
    @DisplayName("非 local 下两次解析拿到的口令不同(真的随机,不是固定串)")
    void resolve_shouldProduceDifferentPasswordsOnEachCall_inNonLocalProfile() {
        String first = AdminPasswordPolicy.resolve(null, false, AdminPasswordPolicy::generate).password();
        String second = AdminPasswordPolicy.resolve(null, false, AdminPasswordPolicy::generate).password();

        assertEquals(AdminPasswordPolicy.GENERATED_PASSWORD_LENGTH, first.length());
        assertEquals(AdminPasswordPolicy.GENERATED_PASSWORD_LENGTH, second.length());
        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("生成的口令:长度正确、只用安全字符集、不含易混字符")
    void generate_shouldMatchLengthAndAlphabet() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String password = AdminPasswordPolicy.generate();
            assertEquals(AdminPasswordPolicy.GENERATED_PASSWORD_LENGTH, password.length());
            for (char c : password.toCharArray()) {
                assertTrue(AdminPasswordPolicy.PASSWORD_ALPHABET.indexOf(c) >= 0, "字符集外的字符: " + c);
                assertFalse(AMBIGUOUS.indexOf(c) >= 0, "不该出现易混字符: " + c);
            }
            seen.add(password);
        }
        // 200 次里几乎不可能撞车;真撞车说明随机源坏了
        assertEquals(200, seen.size(), "生成结果出现了重复,随机源可疑");
    }

    @Test
    @DisplayName("指定长度生效,非法长度直接拒绝")
    void generate_shouldHonourRequestedLength() {
        assertEquals(24, AdminPasswordPolicy.generate(24).length());
        assertEquals(1, AdminPasswordPolicy.generate(1).length());
        assertThrows(IllegalArgumentException.class, () -> AdminPasswordPolicy.generate(0));
        assertThrows(IllegalArgumentException.class, () -> AdminPasswordPolicy.generate(-3));
    }

    @Test
    @DisplayName("随机源可注入:固定种子可复现,证明生成逻辑本身不依赖时间/全局状态")
    void generate_shouldBeReproducibleWithSeededRandom() {
        String first = AdminPasswordPolicy.generate(16, new Random(7));
        String second = AdminPasswordPolicy.generate(16, new Random(7));

        assertEquals(first, second);
        assertEquals(16, first.length());
        for (char c : first.toCharArray()) {
            assertTrue(AdminPasswordPolicy.PASSWORD_ALPHABET.indexOf(c) >= 0, "字符集外的字符: " + c);
        }
    }
}
