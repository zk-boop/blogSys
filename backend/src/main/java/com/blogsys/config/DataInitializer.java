package com.blogsys.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blogsys.entity.User;
import com.blogsys.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private static final String DEFAULT_AVATAR = "https://api.dicebear.com/9.x/avataaars/svg?seed=admin";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getUsername, "admin")) > 0) {
            return;
        }
        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode("admin123"));
        admin.setNickname("管理员");
        admin.setAvatar(DEFAULT_AVATAR);
        admin.setRole("ADMIN");
        userMapper.insert(admin);
        log.info("初始化管理员账号: admin / admin123");
    }
}
