package com.blogsys.visibility;

import java.util.Optional;

/**
 * 正在读这份内容的人。
 *
 * <p><b>「未登录」是一个正常取值,不是缺失状态。</b>公开博客的绝大多数请求都是匿名的,
 * 所以匿名必须是一条平淡的分支 —— 而不是像现在这样,靠
 * {@code try { ... } catch (BizException e) { return false; }} 去兜住一个表示「未登录」的异常。
 *
 * <p>这是一个纯值:不可变、可自由传递、不持有任何环境状态。需要「当前 viewer」的地方由
 * {@code Visibility} 从请求里取一次,而不是让这个类型自己去读线程局部变量。
 */
public sealed interface Viewer {

    Viewer ANONYMOUS = new Anonymous();

    /** 已登录用户的 id;匿名时为 {@link Optional#empty()}。 */
    Optional<Long> id();

    /** 是否管理员。管理员对可见性规则全域豁免。 */
    boolean isAdmin();

    default boolean isAnonymous() {
        return id().isEmpty();
    }

    /** viewer 本人是否就是某个主体(文章作者、评论者)。 */
    default boolean owns(Long principalId) {
        return principalId != null && id().filter(principalId::equals).isPresent();
    }

    static Viewer anonymous() {
        return ANONYMOUS;
    }

    /**
     * 一个已登录的 viewer。
     *
     * @throws IllegalArgumentException {@code userId} 为 null —— 那应当用 {@link #anonymous()}
     */
    static Viewer of(Long userId, boolean admin) {
        return new Member(userId, admin);
    }

    record Anonymous() implements Viewer {

        @Override
        public Optional<Long> id() {
            return Optional.empty();
        }

        @Override
        public boolean isAdmin() {
            return false;
        }

        @Override
        public String toString() {
            return "Viewer.anonymous";
        }
    }

    record Member(Long userId, boolean admin) implements Viewer {

        /** 不变量在此强制:record 的规范构造器是 public,光靠 {@link #of} 拦不住。 */
        public Member {
            if (userId == null) {
                throw new IllegalArgumentException("已登录的 viewer 必须有 userId,匿名请用 Viewer.anonymous()");
            }
        }

        @Override
        public Optional<Long> id() {
            return Optional.of(userId);
        }

        @Override
        public boolean isAdmin() {
            return admin;
        }

        @Override
        public String toString() {
            return "Viewer(" + userId + (admin ? ",admin" : "") + ")";
        }
    }
}
