package com.blogsys.visibility;

import com.blogsys.security.LoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 从 Spring Security 上下文推导 viewer 的适配器。
 *
 * <p>这是模块里唯一知道 Spring Security 存在的地方。刻意不走 {@code SecurityUtil}:
 * 那个类是 final + 全静态,{@code currentUserId()} 在匿名时抛 401 ——
 * 它既无法被任何适配器满足,也没有「匿名」这个正常取值。
 *
 * <p>被封禁用户在 {@code JwtAuthFilter:39} 处就被清空了上下文,所以这里推导出的 viewer
 * 永远不会是一个被封禁的人。可见性规则因此只需问「作者是否被封禁」,不必再问「viewer 是否被封禁」。
 */
@Component
public class SecurityContextViewerSource implements ViewerSource {

    private static final String ROLE_ADMIN = "ADMIN";

    @Override
    public Viewer current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser loginUser)
                || loginUser.getId() == null) {
            return Viewer.anonymous();
        }
        return Viewer.of(loginUser.getId(), ROLE_ADMIN.equals(loginUser.getRole()));
    }
}
