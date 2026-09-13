package com.blogsys.visibility;

import com.blogsys.entity.Article;
import com.blogsys.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerSourceTest {

    private final SecurityContextViewerSource source = new SecurityContextViewerSource();

    /**
     * 前后都清。
     *
     * <p>只在 {@code @AfterEach} 清是不够的:{@code CommentServiceTest} 与 {@code LikeServiceTest}
     * 会在 {@code @BeforeEach} 里往 {@code SecurityContextHolder} 塞一个登录用户却从不清理,
     * 于是全量跑时本类的第一个用例会继承上一个测试类留下的身份(实测拿到的是 {@code Viewer(1)})。
     * 单跑本类不会暴露,全量跑才暴露 —— 这正是「测试依赖了比自身活得更久的状态」。
     */
    @BeforeEach
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(Long id, String role) {
        LoginUser loginUser = new LoginUser(id, "someone", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @Test
    @DisplayName("没有上下文时是匿名 —— 而不是抛异常")
    void current_shouldBeAnonymous_whenNoAuthentication() {
        assertEquals(Viewer.anonymous(), source.current());
    }

    @Test
    void current_shouldDeriveMemberFromPrincipal() {
        authenticate(7L, "USER");

        Viewer viewer = source.current();

        assertFalse(viewer.isAnonymous());
        assertEquals(7L, viewer.id().orElseThrow());
        assertFalse(viewer.isAdmin());
    }

    @Test
    void current_shouldDeriveAdminFromRole() {
        authenticate(3L, "ADMIN");

        Viewer viewer = source.current();

        assertEquals(3L, viewer.id().orElseThrow());
        assertTrue(viewer.isAdmin());
    }

    @Test
    @DisplayName("principal 不是 LoginUser 时收敛到匿名,而不是猜")
    void current_shouldBeAnonymous_whenPrincipalIsForeign() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymous-string-principal", null, java.util.List.of()));

        assertEquals(Viewer.anonymous(), source.current());
    }

    @Test
    @DisplayName("principal 缺 id 时收敛到匿名 —— Viewer.Member 不允许 null userId")
    void current_shouldBeAnonymous_whenIdIsMissing() {
        LoginUser noId = new LoginUser(null, "broken", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(noId, null, noId.getAuthorities()));

        assertEquals(Viewer.anonymous(), source.current());
    }

    @Test
    void fixed_shouldAlwaysReturnTheGivenViewer() {
        ViewerSource fixed = ViewerSource.fixed(Viewer.of(7L, true));

        assertEquals(Viewer.of(7L, true), fixed.current());
        assertEquals(Viewer.of(7L, true), fixed.current());
    }

    @Test
    @DisplayName("权限令牌只能由模块签发:没有任何 public / protected 构造器")
    void visibleArticle_cannotBeForgedFromOutside() {
        Constructor<?>[] constructors = VisibleArticle.class.getDeclaredConstructors();

        assertTrue(constructors.length > 0, "前提:确实声明了构造器");
        for (Constructor<?> ctor : constructors) {
            assertFalse(Modifier.isPublic(ctor.getModifiers()),
                    "构造器若为 public,模块外就能凭空造出「已验证可见」的文章: " + ctor);
            assertFalse(Modifier.isProtected(ctor.getModifiers()),
                    "protected 会给子类留口子,而这个令牌不该有任何签发旁路: " + ctor);
        }
    }
}
