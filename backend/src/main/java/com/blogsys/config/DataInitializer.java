package com.blogsys.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blogsys.entity.User;
import com.blogsys.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    @Override
    public void run(String... args) {
        // 管理员已存在就一行都不做 —— 尤其不能因为"这次启动没给 BLOGSYS_ADMIN_PASSWORD"
        // 就回去覆盖已有管理员的口令,那是把线上账号直接弄丢。
        if (userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getUsername, "admin")) > 0) {
            return;
        }

        boolean localProfile = StartupSecurityPolicy.isLocalProfile(environment.getActiveProfiles());
        // 走 Environment 而不是 System.getenv:部署者可能用环境变量、--BLOGSYS_ADMIN_PASSWORD=xxx
        // 或配置文件三种方式之一来给值,Environment 三种都看得到。
        String envPassword = environment.getProperty(AdminPasswordPolicy.ENV_ADMIN_PASSWORD);
        AdminPasswordPolicy.ResolvedAdminPassword resolved = AdminPasswordPolicy.resolve(
                envPassword, localProfile, AdminPasswordPolicy::generate);

        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode(resolved.password()));
        admin.setNickname("管理员");
        admin.setAvatar("");
        admin.setRole("ADMIN");
        userMapper.insert(admin);

        switch (resolved.source()) {
            case ENV -> log.info("初始化管理员账号: admin(口令取自环境变量 {};不打印内容)。请登录后尽快修改。",
                    AdminPasswordPolicy.ENV_ADMIN_PASSWORD);
            case LOCAL_DEFAULT -> log.info("初始化管理员账号: admin / {}(local profile 的本地默认口令,仅供本地开发;"
                            + "其它 profile 会随机生成)", AdminPasswordPolicy.LOCAL_DEFAULT_ADMIN_PASSWORD);
            case GENERATED -> log.warn("初始化管理员账号: admin / {} —— 这是本次随机生成的初始口令,请立即保存;"
                    + "登录后请尽快修改。", resolved.password());
        }
    }
}
