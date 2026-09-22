package com.blogsys.config;

import java.util.Arrays;

/**
 * 启动期的安全底线判定:JWT 密钥能不能用来启动。
 *
 * <p>这里刻意写成**纯函数**(不碰静态状态、不依赖 Spring 容器),因为"该不该拒绝启动"
 * 是本项目最容易出错也最该被测到的一条规则,而本仓库没有 {@code @SpringBootTest} ——
 * 判定逻辑留在容器里就只能靠人肉启动一次来验证。装配见 {@link StartupSecurityCheck}。
 *
 * <p>为什么分界线画在 profile 上:这份代码会被直接部署上线,部署者常常直接
 * {@code java -jar} 上线。仓库里的默认密钥一旦跟着上线,任何人都能自己签一个合法 token
 * 冒充管理员 —— 而且这种错误**不会以任何方式自曝**,站点看起来一切正常。本地开发
 * (profile=local)又需要开箱即用,所以规则只对非 local 生效。
 */
public final class StartupSecurityPolicy {

    /** 唯一被豁免的 profile:本地开发。 */
    public static final String LOCAL_PROFILE = "local";

    /** application.yml 里 {@code ${JWT_SECRET:...}} 的兜底值;改这里必须同步改 application.yml。 */
    public static final String DEFAULT_JWT_SECRET =
            "blogsys-dev-secret-key-please-change-in-production-0123456789";

    /**
     * 密钥长度下限。HS256 的安全强度就等于密钥长度,32 字符(256 bit)是 HMAC-SHA256 的下限;
     * 同时也挡掉 {@code JWT_SECRET=changeme} 这类"设了等于没设"的值。
     */
    public static final int MIN_JWT_SECRET_LENGTH = 32;

    /** 报错信息里给出**能直接照抄**的补救命令,而不是只说一句"请配置密钥"。 */
    public static final String JWT_SECRET_FIX_HINT =
            "请设置一个至少 32 字符的随机密钥,例如:openssl rand -base64 48";

    /** 密钥缺失/仍是仓库默认值时的报错全文。 */
    public static final String JWT_SECRET_MISSING_MESSAGE =
            "JWT_SECRET 未配置或仍是仓库默认值。" + JWT_SECRET_FIX_HINT;

    private StartupSecurityPolicy() {
    }

    /**
     * profile 里是否含 local。
     *
     * <p>用 Spring 已经解析好的 active profiles 数组判断,不做字符串拼接 ——
     * 拼接出来的判断在 {@code local,prod}、大小写、命令行覆盖这些情况下都会骗人。
     */
    public static boolean isLocalProfile(String[] activeProfiles) {
        return activeProfiles != null && Arrays.asList(activeProfiles).contains(LOCAL_PROFILE);
    }

    /**
     * 密钥质量判断,**与 profile 无关**:非空、不是仓库默认值、长度够。
     *
     * <p>拆出来是为了让 local 下也能"放行但提醒",不必复制一份判断。
     */
    public static boolean isSecretUsable(String secret) {
        if (secret == null) {
            return false;
        }
        String actual = secret.trim();
        return !actual.isEmpty()
                && !DEFAULT_JWT_SECRET.equals(actual)
                && actual.length() >= MIN_JWT_SECRET_LENGTH;
    }

    /**
     * 密钥不合格就抛异常,让应用起不来。
     *
     * <p>为什么是"拒绝启动"而不是"打条日志继续跑":一个用默认密钥启动的实例,
     * 对外表现和一个配置正确的实例**完全一样**,没人会发现。宁可让部署在启动阶段
     * 大声失败,也不要让它悄悄上线。
     *
     * <p>为什么 local 直接放行:本地开发、现有测试与本地流程都依赖这个默认值,
     * 在这里拦一刀会把开发体验和测试一起打坏,而它拦住的并不是真正的风险场景
     * (风险场景是生产,生产不该用 local profile)。
     */
    public static void assertJwtSecretUsable(String secret, String[] activeProfiles) {
        if (isLocalProfile(activeProfiles)) {
            return;
        }
        String actual = secret == null ? "" : secret.trim();
        if (actual.isEmpty() || DEFAULT_JWT_SECRET.equals(actual)) {
            throw new IllegalStateException(JWT_SECRET_MISSING_MESSAGE + profileHint(activeProfiles));
        }
        if (actual.length() < MIN_JWT_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT_SECRET 太短(当前 " + actual.length() + " 字符,至少要 "
                            + MIN_JWT_SECRET_LENGTH + " 字符)。" + JWT_SECRET_FIX_HINT
                            + profileHint(activeProfiles));
        }
    }

    /** 补一句"为什么现在会被拦",免得部署者以为是被误伤的。 */
    private static String profileHint(String[] activeProfiles) {
        String profiles = activeProfiles == null || activeProfiles.length == 0
                ? "(未设置)"
                : String.join(",", activeProfiles);
        return "\n当前 profile: " + profiles + ";不含 local 的启动必须显式提供 JWT_SECRET。";
    }
}
