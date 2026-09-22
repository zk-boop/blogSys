package com.blogsys.config;

import java.security.SecureRandom;
import java.util.Random;
import java.util.function.Supplier;

/**
 * 管理员初始口令的**来源判定**与**随机生成**。
 *
 * <p>抽成纯方法的原因:口令来源(环境变量 / 本地默认 / 随机生成)是三条互斥分支,
 * 而随机那一条天生不可复现 —— 如果把 {@code new SecureRandom()} 直接写在
 * {@link DataInitializer} 的初始化流程里,这三条分支就没法离线断言了。这里把随机性
 * 收敛成一个可注入的 {@link Supplier},来源判定因此可以完全确定性地测。
 *
 * <p>为什么非 local 要随机:固定口令 {@code admin123} 跟着源码一起公开,等于给每个
 * 部署实例装了一扇公共后门 —— 攻击者不需要猜,读一遍源码就知道。生成的一次性口令只在
 * 首次启动的日志里出现一次,部署者抄走即可。
 */
public final class AdminPasswordPolicy {

    /** 部署方注入初始口令用的环境变量名。 */
    public static final String ENV_ADMIN_PASSWORD = "BLOGSYS_ADMIN_PASSWORD";

    /** local profile 下的固定口令:本地开发要能不看日志直接登录。 */
    public static final String LOCAL_DEFAULT_ADMIN_PASSWORD = "admin123";

    /**
     * 随机口令长度 16。16 位随机字母数字 ≈ 94 bit,猜不出来;又不至于长到没法从日志里手抄。
     */
    public static final int GENERATED_PASSWORD_LENGTH = 16;

    /**
     * 随机口令字符集:去掉了 {@code 0/O/o}、{@code 1/l/I} 这些易混字符 ——
     * 这个口令是要人工从启动日志里抄下来再登录的,少一个字符就得重新部署一次,
     * 可抄写性比字符集大小值钱得多。
     */
    public static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 口令来源;调用方据此决定日志级别与措辞(随机口令必须提醒"立即保存")。 */
    public enum Source {
        /** 部署方通过 {@link #ENV_ADMIN_PASSWORD} 指定。 */
        ENV,
        /** local profile 的本地默认口令。 */
        LOCAL_DEFAULT,
        /** 本次启动随机生成。 */
        GENERATED
    }

    /** 判定结果:口令本身 + 它从哪来。 */
    public record ResolvedAdminPassword(String password, Source source) {
    }

    private AdminPasswordPolicy() {
    }

    /**
     * 决定首次初始化用哪个口令。
     *
     * <p>优先级:环境变量 &gt; local 默认 &gt; 随机生成。环境变量排第一,因为部署脚本
     * 里写死的口令才是部署者**可控**的那个 —— 随机口令虽然安全,但每次初始化都得去翻日志。
     *
     * @param envPassword  {@link #ENV_ADMIN_PASSWORD} 的值,未设置时为 null;空白视为未设置
     *                     (shell 里 {@code export X=} 会给出空串,这不该被当成一个口令)
     * @param localProfile 当前是否 local profile
     * @param generator    随机口令生成器,仅在需要随机时调用一次
     */
    public static ResolvedAdminPassword resolve(String envPassword,
                                                boolean localProfile,
                                                Supplier<String> generator) {
        if (envPassword != null && !envPassword.isBlank()) {
            // 不 trim:口令里的空白可能是部署者故意的,悄悄改掉会变成"配置写对了却登不上"。
            return new ResolvedAdminPassword(envPassword, Source.ENV);
        }
        if (localProfile) {
            return new ResolvedAdminPassword(LOCAL_DEFAULT_ADMIN_PASSWORD, Source.LOCAL_DEFAULT);
        }
        return new ResolvedAdminPassword(generator.get(), Source.GENERATED);
    }

    /** 生成一个 {@link #GENERATED_PASSWORD_LENGTH} 位的随机口令。 */
    public static String generate() {
        return generate(GENERATED_PASSWORD_LENGTH);
    }

    /** 生成指定长度的随机口令;长度必须为正。 */
    public static String generate(int length) {
        return generate(length, RANDOM);
    }

    /**
     * 可注入随机源的版本,包内可见:测试用固定种子复现字符集/长度断言,生产走 {@link SecureRandom}。
     */
    static String generate(int length, Random random) {
        if (length <= 0) {
            throw new IllegalArgumentException("口令长度必须为正数,当前: " + length);
        }
        StringBuilder password = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            password.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }
}
