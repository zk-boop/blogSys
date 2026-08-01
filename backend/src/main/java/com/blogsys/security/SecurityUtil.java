package com.blogsys.security;

import com.blogsys.common.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtil {

    private SecurityUtil() {
    }

    public static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser.getId();
        }
        throw new BizException(401, "未登录");
    }

    public static LoginUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        throw new BizException(401, "未登录");
    }

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof LoginUser loginUser
                && "ADMIN".equals(loginUser.getRole());
    }

    public static void requireOwnerOrAdmin(Long ownerId) {
        Long userId = currentUserId();
        if (!ownerId.equals(userId) && !isAdmin()) {
            throw new BizException(403, "只有作者或管理员可以执行该操作");
        }
    }
}
