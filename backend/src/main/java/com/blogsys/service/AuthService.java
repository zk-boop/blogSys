package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blogsys.common.BizException;
import com.blogsys.dto.LoginRequest;
import com.blogsys.dto.RegisterRequest;
import com.blogsys.entity.User;
import com.blogsys.mapper.UserMapper;
import com.blogsys.security.JwtUtil;
import com.blogsys.security.SecurityUtil;
import com.blogsys.vo.AuthResponse;
import com.blogsys.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthResponse register(RegisterRequest request) {
        Long exists = userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, request.getUsername()));
        if (exists > 0) {
            throw new BizException("用户名已存在");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setAvatar("");
        user.setRole("USER");
        userMapper.insert(user);
        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BizException("用户名或密码错误");
        }
        return buildAuthResponse(user);
    }

    public UserVO me() {
        Long userId = SecurityUtil.currentUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(401, "用户不存在");
        }
        return toVO(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtUtil.generate(user.getId(), user.getUsername(), user.getRole());
        return new AuthResponse(token, toVO(user));
    }

    public static UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setRole(user.getRole());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
