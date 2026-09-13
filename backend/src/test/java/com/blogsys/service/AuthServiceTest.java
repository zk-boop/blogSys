package com.blogsys.service;

import com.blogsys.common.BizException;
import com.blogsys.dto.LoginRequest;
import com.blogsys.dto.RegisterRequest;
import com.blogsys.entity.User;
import com.blogsys.mapper.UserMapper;
import com.blogsys.security.JwtUtil;
import com.blogsys.vo.AuthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        jwtUtil = new JwtUtil("test-secret-key-with-more-than-32-bytes-for-hmac", 1);
        authService = new AuthService(userMapper, passwordEncoder, jwtUtil);
    }

    @Test
    void register_shouldSucceed_whenUsernameNotTaken() {
        when(userMapper.selectCount(any())).thenReturn(0L);

        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setPassword("secret1");
        request.setNickname("爱丽丝");

        AuthResponse response = authService.register(request);

        assertNotNull(response.getToken());
        assertEquals("alice", response.getUser().getUsername());
        assertEquals("USER", response.getUser().getRole());
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void register_shouldFail_whenUsernameTaken() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setPassword("secret1");
        request.setNickname("爱丽丝");

        assertThrows(BizException.class, () -> authService.register(request));
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void login_shouldSucceed_withCorrectPassword() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword(passwordEncoder.encode("secret1"));
        user.setRole("USER");
        // status 必须显式设:UserStatus.of 对 null 是 fail closed 到「已封禁」。
        // 生产库里 users.status 是 NOT NULL DEFAULT 0,所以 null 不可达;
        // 但一个「内存里造出来的用户」本就该有状态,不设是夹具建模不完整。
        user.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(user);

        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("secret1");

        AuthResponse response = authService.login(request);

        assertNotNull(response.getToken());
        assertEquals(1L, response.getUser().getId());
    }

    @Test
    void login_shouldFail_withWrongPassword() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword(passwordEncoder.encode("secret1"));
        when(userMapper.selectOne(any())).thenReturn(user);

        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("wrong-pass");

        assertThrows(BizException.class, () -> authService.login(request));
    }

    @Test
    void login_shouldFail_whenUserBanned() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword(passwordEncoder.encode("secret1"));
        user.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(user);

        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("secret1");

        BizException ex = assertThrows(BizException.class, () -> authService.login(request));
        assertEquals(403, ex.getCode());
    }
}
