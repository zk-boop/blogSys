package com.blogsys.visibility;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    @Override
    public Visibility as(Viewer viewer) {
        return new DefaultVisibility(articleMapper, commentMapper, ViewerSource.fixed(viewer));
    }
}
