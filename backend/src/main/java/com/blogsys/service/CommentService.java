package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blogsys.common.ArticleStatus;
import com.blogsys.common.BizException;
import com.blogsys.dto.CommentRequest;
import com.blogsys.entity.Article;
import com.blogsys.entity.Comment;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.CommentMapper;
import com.blogsys.security.SecurityUtil;
import com.blogsys.vo.CommentVO;
import com.blogsys.vo.UserBriefVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final ArticleMapper articleMapper;
    private final UserService userService;

    public List<CommentVO> listByArticle(Long articleId) {
        if (articleMapper.selectById(articleId) == null) {
            throw new BizException(404, "文章不存在");
        }
        List<Comment> comments = commentMapper.selectList(
                Wrappers.<Comment>lambdaQuery()
                        .eq(Comment::getArticleId, articleId)
                        .isNull(Comment::getParentId)
                        .orderByAsc(Comment::getCreatedAt));
        return toVOs(comments);
    }

    @Transactional
    public CommentVO create(Long articleId, CommentRequest request) {
        Article article = articleMapper.selectById(articleId);
        if (article == null || article.getStatus() != ArticleStatus.PUBLISHED.getValue()) {
            throw new BizException(404, "文章不存在");
        }
        Comment comment = new Comment();
        comment.setArticleId(articleId);
        comment.setUserId(SecurityUtil.currentUserId());
        comment.setParentId(null);
        comment.setContent(request.getContent());
        commentMapper.insert(comment);

        articleMapper.incrCommentCount(articleId, 1);
        return toVOs(List.of(comment)).get(0);
    }

    @Transactional
    public void delete(Long id) {
        Comment comment = commentMapper.selectById(id);
        if (comment == null) {
            throw new BizException(404, "评论不存在");
        }
        SecurityUtil.requireOwnerOrAdmin(comment.getUserId());
        commentMapper.deleteById(id);
        articleMapper.incrCommentCount(comment.getArticleId(), -1);
    }

    private List<CommentVO> toVOs(List<Comment> comments) {
        if (comments.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = comments.stream().map(Comment::getUserId).distinct().toList();
        Map<Long, User> users = userService.findByIds(userIds);
        return comments.stream().map(comment -> {
            CommentVO vo = new CommentVO();
            vo.setId(comment.getId());
            vo.setArticleId(comment.getArticleId());
            vo.setContent(comment.getContent());
            vo.setCreatedAt(comment.getCreatedAt());
            User user = users.get(comment.getUserId());
            if (user != null) {
                vo.setUser(new UserBriefVO(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar()));
            }
            return vo;
        }).toList();
    }
}
