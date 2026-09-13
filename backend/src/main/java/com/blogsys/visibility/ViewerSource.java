package com.blogsys.visibility;

/**
 * 「当前 viewer」的来源 —— 可见性模块读环境的唯一入口。
 *
 * <p>这个端口之所以成立,是因为它有两个真实适配器:{@link SecurityContextViewerSource}
 * (生产:从 Spring Security 上下文推导)与 {@link #fixed}(测试,以及将来跑在没有
 * principal 的线程池上的 AI 工具路径)。
 *
 * <p>它<b>不是</b>对 {@code SecurityUtil} 的适配 —— 那个类是 final + 全静态,
 * {@code currentUserId()} 在匿名时抛 401,既无法被满足,也没有「匿名」这个取值。
 */
@FunctionalInterface
public interface ViewerSource {

    /**
     * 当前请求的 viewer。
     *
     * <p><b>永不抛异常。</b>没有登录、线程上没有上下文、跑在非请求线程上,
     * 一律返回 {@link Viewer#anonymous()} —— 未登录是正常取值,不是错误。
     */
    Viewer current();

    /** 固定 viewer 的来源,供测试与后台任务使用。 */
    static ViewerSource fixed(Viewer viewer) {
        return () -> viewer;
    }
}
