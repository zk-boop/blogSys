package com.blogsys.config;

import com.blogsys.entity.User;
import com.blogsys.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 首次启动创建管理员这条路径:口令从哪来、是否落库、已存在时是否完全不动作。
 *
 * <p>编码器与 Environment 都用 Mockito 顶掉 —— 本仓库没有 {@code @SpringBootTest},
 * 这条路径也就不会真的连数据库。编码器用 {@code "ENC:" + 明文} 代替 BCrypt,
 * 是为了让"到底用了哪个口令"能被直接断言,而不用去反推哈希。
 */
@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    private static final String ENV_PASSWORD = "ops-supplied-J8kQ2m";

    @Mock
    private UserMapper userMapper;

    @Mock
    private Environment environment;

    @Mock
    private PasswordEncoder passwordEncoder;

    private DataInitializer dataInitializer;

    @BeforeEach
    void setUp() {
        dataInitializer = new DataInitializer(userMapper, passwordEncoder, environment);
    }

    /** 让明文口令能穿过编码器被断言到。 */
    private void encodeWithPrefix() {
        when(passwordEncoder.encode(anyString())).thenAnswer(call -> "ENC:" + call.getArgument(0));
    }

    /** 取出这次 insert 进去的管理员。 */
    private User captureInsertedAdmin() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static String plainPassword(User admin) {
        return admin.getPassword().substring("ENC:".length());
    }

    @Test
    @DisplayName("管理员已存在 → 一行都不做(不覆盖线上口令,也不读 profile/环境变量)")
    void run_shouldDoNothing_whenAdminAlreadyExists() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        dataInitializer.run();

        verify(userMapper, never()).insert(any(User.class));
        verifyNoInteractions(environment);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("local + 未给环境变量 → 仍是 admin123(本地流程不变),并同以前一样落库")
    void run_shouldUseLocalDefaultPassword_inLocalProfile() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"local"});
        when(environment.getProperty(AdminPasswordPolicy.ENV_ADMIN_PASSWORD)).thenReturn(null);
        encodeWithPrefix();

        dataInitializer.run();

        User admin = captureInsertedAdmin();
        assertEquals("admin", admin.getUsername());
        assertEquals("ADMIN", admin.getRole());
        assertEquals("admin123", plainPassword(admin));
        assertNotEquals("admin123", admin.getPassword(), "口令必须过编码器,不能明文落库");
    }

    @Test
    @DisplayName("非 local + 给了环境变量 → 用环境变量里的口令")
    void run_shouldUseEnvPassword_inNonLocalProfile() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(environment.getProperty(AdminPasswordPolicy.ENV_ADMIN_PASSWORD)).thenReturn(ENV_PASSWORD);
        encodeWithPrefix();

        dataInitializer.run();

        assertEquals(ENV_PASSWORD, plainPassword(captureInsertedAdmin()));
    }

    @Test
    @DisplayName("local + 给了环境变量 → 环境变量赢过 admin123")
    void run_shouldPreferEnvPassword_overLocalDefault() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"local"});
        when(environment.getProperty(AdminPasswordPolicy.ENV_ADMIN_PASSWORD)).thenReturn(ENV_PASSWORD);
        encodeWithPrefix();

        dataInitializer.run();

        assertEquals(ENV_PASSWORD, plainPassword(captureInsertedAdmin()));
    }

    @Test
    @DisplayName("非 local + 没给环境变量 → 随机生成 16 位,绝不是 admin123")
    void run_shouldGenerateRandomPassword_inNonLocalProfile() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(environment.getProperty(AdminPasswordPolicy.ENV_ADMIN_PASSWORD)).thenReturn(null);
        encodeWithPrefix();

        dataInitializer.run();

        String password = plainPassword(captureInsertedAdmin());
        assertNotEquals("admin123", password);
        assertEquals(AdminPasswordPolicy.GENERATED_PASSWORD_LENGTH, password.length());
        for (char c : password.toCharArray()) {
            assertTrue(AdminPasswordPolicy.PASSWORD_ALPHABET.indexOf(c) >= 0, "字符集外的字符: " + c);
        }
    }

    @Test
    @DisplayName("非 local + 环境变量是空白 → 仍然随机生成")
    void run_shouldGenerateRandomPassword_whenEnvPasswordIsBlank() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(environment.getProperty(AdminPasswordPolicy.ENV_ADMIN_PASSWORD)).thenReturn("  ");
        encodeWithPrefix();

        dataInitializer.run();

        assertNotEquals("admin123", plainPassword(captureInsertedAdmin()));
    }

    @Test
    @DisplayName("非 local 下两次初始化拿到的口令不同")
    void run_shouldGenerateDifferentPasswords_acrossRuns() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(environment.getProperty(AdminPasswordPolicy.ENV_ADMIN_PASSWORD)).thenReturn(null);
        encodeWithPrefix();

        dataInitializer.run();
        dataInitializer.run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, times(2)).insert(captor.capture());
        List<User> inserted = captor.getAllValues();
        assertNotEquals(plainPassword(inserted.get(0)), plainPassword(inserted.get(1)));
    }
}
