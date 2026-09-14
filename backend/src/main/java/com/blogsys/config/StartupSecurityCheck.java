package com.blogsys.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 把 {@link StartupSecurityPolicy} 接到启动流程上:非 local profile 且密钥不合格,
 * 应用直接起不来。
 *
 * <p>为什么挂在 bean 初始化上而不是 {@code CommandLineRunner}:内嵌 Web 服务器是在
 * refresh 阶段绑定端口的,而 runner 在那之后才跑 —— 用 runner 做检查,端口已经敞开过了,
 * 端口开的这一瞬间就足够让一个用默认密钥的实例被人签走 token。bean 初始化早于端口绑定,
 * 所以"起不来"是干脆的。
 *
 * <p>为什么把判定放在 policy 里:本仓库没有 {@code @SpringBootTest},判定留在这个类里
 * 就只能靠真启动一次来验证;现在这个类只剩"读 Environment + 调 policy + 打日志"。
 */
@Slf4j
@Component
public class StartupSecurityCheck implements InitializingBean {

    private final Environment environment;
    private final String jwtSecret;

    public StartupSecurityCheck(Environment environment,
                                @Value("${blogsys.jwt.secret}") String jwtSecret) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
    }

    @Override
    public void afterPropertiesSet() {
        // 用 Spring 解析好的 active profiles(命令行 --spring.profiles.active、环境变量
        // SPRING_PROFILES_ACTIVE、application.yml 三种来源它都看得到),不做字符串判断。
        String[] activeProfiles = environment.getActiveProfiles();

        // 不合格在这里抛 IllegalStateException,容器会把消息原样带进启动失败的输出里。
        StartupSecurityPolicy.assertJwtSecretUsable(jwtSecret, activeProfiles);

        if (StartupSecurityPolicy.isSecretUsable(jwtSecret)) {
            log.info("JWT 密钥校验通过(profile: {})", String.join(",", activeProfiles));
        } else {
            // 走到这里只可能是 local(非 local 已经抛出去了)。本地放行,但每年都有人
            // 把 local profile 带上生产 —— 一行 WARN 是这个错误唯一会被看见的机会。
            log.warn("当前 profile 含 local,放行仓库默认 JWT 密钥。local 只用于本地开发,"
                    + "部署到服务器时请切换到别的 profile 并设置 JWT_SECRET;"
                    + StartupSecurityPolicy.JWT_SECRET_FIX_HINT);
        }
    }
}
