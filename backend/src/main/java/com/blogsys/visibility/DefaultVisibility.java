package com.blogsys.visibility;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blogsys.common.ArticleStatus;
import com.blogsys.common.UserStatus;
import com.blogsys.entity.Comment;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.CommentMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link Visibility} 的默认实现。
 *
 * <p>viewer 在<b>构造查询对象的那一刻</b>被解析并冻结进查询对象里。所以一个
 * {@link ArticleQuery} 是单 viewer、短命的:不要把它存进字段、静态变量或缓存。
 *
 * <p>刻意不在这里做任何写操作、计数或缓存 —— 这个模块只回答「看得见吗」。
 */
@Service
public class DefaultVisibility implements Visibility {

    private final ArticleMapper articleMapper;
    private final CommentMapper commentMapper;
    private final ViewerSource viewerSource;

    public DefaultVisibility(ArticleMapper articleMapper, CommentMapper commentMapper, ViewerSource viewerSource) {
        this.articleMapper = articleMapper;
        this.commentMapper = commentMapper;
        this.viewerSource = viewerSource;
    }

    @Override
    public ArticleQuery articles() {
        return new ArticleQuery(viewerSource.current(), articleMapper);
    }

    /**
     * 评论没有自己的状态字段:它可见 ⟺ 所属文章可见(由令牌保证)∧ 评论作者未被封禁。
     *
     * <p>用令牌里带的 viewer,而不是重新读一次环境 —— 读两次就可能拿到两个不同的 viewer,
     * 那样文章规则与评论规则又会在同一处分歧。
     */
    @Override
    public List<Comment> commentsOf(VisibleArticle article) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getArticleId, article.id())
                .orderByAsc(Comment::getCreatedAt);
        if (!article.viewer().isAdmin()) {
            wrapper.apply(true, "user_id IN (SELECT id FROM users WHERE status = {0})",
                    UserStatus.ACTIVE.getValue());
        }
        return commentMapper.selectList(wrapper);
    }

    /**
     * 第三张表的情形:用<b>相关 EXISTS</b> 而不是不相关 IN。
     *
     * <p>{@code a.id = article_id} 走的是 articles 的主键,所以外层每行只是一次主键查找,
     * 不需要任何新索引;而 {@code inSql} 那类不相关子查询要先把全部可见 id 物化出来。
     * 谓词形式对性能没有影响(实测两种形式 MySQL 会优化成同一个计划),
     * 这里选 EXISTS 是因为它在语义上就是「这一行引用的文章可见吗」,而且是逐行短路。
     */
    @Override
    public <R> LambdaQueryWrapper<R> restrictToVisibleArticles(LambdaQueryWrapper<R> wrapper) {
        Viewer viewer = viewerSource.current();
        if (viewer.isAdmin()) {
            return wrapper;
        }
        Long viewerId = viewer.id().orElse(null);
        if (viewerId == null) {
            wrapper.apply(true,
                    "EXISTS (SELECT 1 FROM articles a WHERE a.id = article_id"
                            + " AND a.status = {0}"
                            + " AND a.user_id IN (SELECT id FROM users WHERE status = {1}))",
                    ArticleStatus.PUBLISHED.getValue(), UserStatus.ACTIVE.getValue());
        } else {
            wrapper.apply(true,
                    "EXISTS (SELECT 1 FROM articles a WHERE a.id = article_id"
                            + " AND (a.status = {0} OR a.user_id = {1})"
                            + " AND a.user_id IN (SELECT id FROM users WHERE status = {2}))",
                    ArticleStatus.PUBLISHED.getValue(), viewerId, UserStatus.ACTIVE.getValue());
        }
        return wrapper;
    }

    @Override
    public Visibility as(Viewer viewer) {
        return new DefaultVisibility(articleMapper, commentMapper, ViewerSource.fixed(viewer));
    }
}
