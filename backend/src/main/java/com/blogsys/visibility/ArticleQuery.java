package com.blogsys.visibility;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsys.common.ArticleStatus;
import com.blogsys.common.BizException;
import com.blogsys.common.UserStatus;
import com.blogsys.entity.Article;
import com.blogsys.mapper.ArticleMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 一次「对某个 viewer 的文章查询」。
 *
 * <p>实例由 {@link Visibility#articles()} 签发,谓词在签发时就已确定,因此不存在
 * 「忘了过滤」这种写法。{@link #where} 只能<b>收窄</b>它 —— 追加的条件会被 MyBatis-Plus
 * 用括号整体包住,和谓词并列,所以一个同级的 {@code or()} 改不动谓词的含义。
 *
 * <p>不可变:每次 {@link #where} / {@link #includingOwnDrafts} 返回新实例。这样每次终态调用
 * 都从干净的条件集重新组装,不会出现「上一次查询的 eq 泄漏到这一次」。
 */
public final class ArticleQuery {

    /** 不可见与不存在必须是同一个答案,否则等于确认了内容的存在。 */
    private static final String NOT_FOUND_MESSAGE = "文章不存在";

    private final Viewer viewer;
    private final ArticleMapper articleMapper;
    private final boolean ownDraftsIncluded;
    private final List<Consumer<LambdaQueryWrapper<Article>>> restrictions;
    private final List<Consumer<LambdaQueryWrapper<Article>>> orderings;

    ArticleQuery(Viewer viewer, ArticleMapper articleMapper) {
        this(viewer, articleMapper, false, List.of(), List.of());
    }

    private ArticleQuery(Viewer viewer, ArticleMapper articleMapper, boolean ownDraftsIncluded,
                         List<Consumer<LambdaQueryWrapper<Article>>> restrictions,
                         List<Consumer<LambdaQueryWrapper<Article>>> orderings) {
        this.viewer = viewer;
        this.articleMapper = articleMapper;
        this.ownDraftsIncluded = ownDraftsIncluded;
        this.restrictions = restrictions;
        this.orderings = orderings;
    }

    /** 追加一个收窄条件。 */
    public ArticleQuery where(Consumer<LambdaQueryWrapper<Article>> restriction) {
        List<Consumer<LambdaQueryWrapper<Article>>> next = new ArrayList<>(restrictions);
        next.add(restriction);
        return new ArticleQuery(viewer, articleMapper, ownDraftsIncluded, List.copyOf(next), orderings);
    }

    /** 追加排序。与谓词无关,所以单独一个方法而不是塞进 {@link #where}。 */
    public ArticleQuery orderByDesc(SFunction<Article, ?> column) {
        List<Consumer<LambdaQueryWrapper<Article>>> next = new ArrayList<>(orderings);
        next.add(w -> w.orderByDesc(column));
        return new ArticleQuery(viewer, articleMapper, ownDraftsIncluded, restrictions, List.copyOf(next));
    }

    /**
     * 追加升序排序,与 {@link #orderByDesc} 对称。
     *
     * <p>方向是调用方的语义(列表要「最新的在前」,而「下一篇」要的是「最近的一个更晚者」),
     * 不是可见性规则 —— 所以模块给出两个方向,而不是让调用方绕过查询对象自己拼一个 wrapper
     * (那样候选集合就有了第二个主人,而「哪些文章可见」必须只有一个)。
     */
    public ArticleQuery orderByAsc(SFunction<Article, ?> column) {
        List<Consumer<LambdaQueryWrapper<Article>>> next = new ArrayList<>(orderings);
        next.add(w -> w.orderByAsc(column));
        return new ArticleQuery(viewer, articleMapper, ownDraftsIncluded, restrictions, List.copyOf(next));
    }

    /**
     * 同时纳入 viewer 自己的草稿。
     *
     * <p>这是「罕见形状」的那四个字:公开列表要的是默认值,只有详情页与个人中心需要它。
     * 忘记写的方向是安全的 —— 少看见,而不是多泄漏。
     */
    public ArticleQuery includingOwnDrafts() {
        return new ArticleQuery(viewer, articleMapper, true, restrictions, orderings);
    }

    /* ---------- 终态 ---------- */

    public List<Article> list(int limit) {
        LambdaQueryWrapper<Article> wrapper = build().last("LIMIT " + Math.max(0, limit));
        return articleMapper.selectList(wrapper);
    }

    /**
     * 分页。<b>total 与 records 由同一个 wrapper 产生</b>,所以「过滤后的记录配未过滤的总数」
     * 这种情况不可能再被写出来 —— 它曾经是收藏列表的真实缺陷。
     */
    public Page<Article> page(long page, long size) {
        return articleMapper.selectPage(new Page<>(page, size), build());
    }

    public long count() {
        return articleMapper.selectCount(build());
    }

    public Optional<Article> find(long articleId) {
        LambdaQueryWrapper<Article> wrapper = build();
        wrapper.eq(Article::getId, articleId);
        return Optional.ofNullable(articleMapper.selectOne(wrapper));
    }

    /**
     * 与 {@link #find} 同义,但不可见时抛出与「不存在」<b>完全相同</b>的 404。
     *
     * <p>消息由模块拥有,不由调用方各自拼 —— 两处稍有差别就是一个存在性预言机。
     */
    public VisibleArticle require(long articleId) {
        return find(articleId)
                .map(article -> new VisibleArticle(article, viewer))
                .orElseThrow(() -> new BizException(404, NOT_FOUND_MESSAGE));
    }

    /* ---------- 谓词装配 ---------- */

    private LambdaQueryWrapper<Article> build() {
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        applyPredicate(wrapper);
        // 条件一律经 and(Consumer):MyBatis-Plus 会把嵌套条件整体括起,
        // 所以调用方的一个同级 or() 改不动谓词的含义。
        restrictions.forEach(restriction -> wrapper.and(restriction));
        // 排序不能经 and():那会被包成 AND (ORDER BY ...),是非法 SQL。
        // 排序不参与谓词,直接作用在外层。
        orderings.forEach(ordering -> ordering.accept(wrapper));
        return wrapper;
    }

    /**
     * 谓词随 viewer 的种类改变<b>形状</b>,而不是改变某个值:
     * 管理员完全没有谓词(于是 SQL 文本与「只写了调用方条件」逐字相同,可以复用执行计划);
     * 其余人是一条「已发布 / 也包含我的草稿」+ 「作者未封禁」。
     *
     * <p><b>一处刻意的省略。</b>设计阶段还考虑过「匿名 viewer 绑定一个哨兵 id,
     * 让带草稿与不带草稿两种形状的 SQL 文本对所有人一致」,以便复用执行计划。没有采用:
     * <ul>
     *   <li>匿名与「不带草稿的登录者」本来就已经产生<b>逐字相同</b>的 SQL —— 计划复用已经拿到了</li>
     *   <li>剩下的差异只在带不带草稿之间,那是<b>语义</b>差异而不是 viewer 身份差异,
     *       哨兵并不能把它统一,只能把匿名的语义伪装成登录者的</li>
     *   <li>代价是一个必须靠注释存活的魔法值(-1),而它的收益只落在三个调用点上</li>
     * </ul>
     */
    private void applyPredicate(LambdaQueryWrapper<Article> wrapper) {
        if (viewer.isAdmin()) {
            return;
        }
        int published = ArticleStatus.PUBLISHED.getValue();
        if (ownDraftsIncluded) {
            Long viewerId = viewer.id().orElse(null);
            if (viewerId != null) {
                wrapper.and(w -> w.eq(Article::getStatus, published).or().eq(Article::getUserId, viewerId));
            } else {
                // 匿名没有草稿,退化成纯公开语料
                wrapper.eq(Article::getStatus, published);
            }
        } else {
            wrapper.eq(Article::getStatus, published);
        }
        // 用 {0} 占位符而非 inSql:值进参数表,不进 SQL 文本
        wrapper.apply(true, "user_id IN (SELECT id FROM users WHERE status = {0})",
                UserStatus.ACTIVE.getValue());
    }
}
