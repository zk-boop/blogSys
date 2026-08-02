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

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
                        .orderByAsc(Comment::getCreatedAt));
        return buildTree(toVOs(comments));
    }

    @Transactional
    public CommentVO create(Long articleId, CommentRequest request) {
        Article article = articleMapper.selectById(articleId);
        if (article == null || article.getStatus() != ArticleStatus.PUBLISHED.getValue()) {
            throw new BizException(404, "文章不存在");
        }
        Long parentId = request.getParentId();
        Long effectiveParentId = null;
        if (parentId != null) {
            Comment parent = commentMapper.selectById(parentId);
            if (parent == null || !parent.getArticleId().equals(articleId)) {
                throw new BizException(400, "被回复的评论不存在");
            }
            effectiveParentId = parent.getParentId() == null ? parent.getId() : parent.getParentId();
        }
        Comment comment = new Comment();
        comment.setArticleId(articleId);
        comment.setUserId(SecurityUtil.currentUserId());
        comment.setParentId(effectiveParentId);
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
        int removed = 1;
        if (comment.getParentId() == null) {
            removed += commentMapper.selectCount(Wrappers.<Comment>lambdaQuery()
                    .eq(Comment::getParentId, comment.getId()));
            commentMapper.delete(Wrappers.<Comment>lambdaQuery().eq(Comment::getParentId, comment.getId()));
        }
        commentMapper.deleteById(id);
        articleMapper.incrCommentCount(comment.getArticleId(), -removed);
    }

    private List<CommentVO> buildTree(List<CommentVO> flat) {
        Map<Long, CommentVO> roots = new LinkedHashMap<>();
        for (CommentVO vo : flat) {
            if (vo.getParentId() == null) {
                vo.setReplies(new ArrayList<>());
                roots.put(vo.getId(), vo);
            }
        }
        for (CommentVO vo : flat) {
            if (vo.getParentId() != null) {
                CommentVO root = roots.get(vo.getParentId());
                if (root != null) {
                    root.getReplies().add(vo);
                }
            }
        }
        return new ArrayList<>(roots.values());
    }

    private List<CommentVO> toVOs(List<Comment> comments) {
        if (comments.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = comments.stream().map(Comment::getUserId).distinct().toList();
        Map<Long, User> users = userService.findByIds(userIds);
        Map<Long, CommentVO> voById = new LinkedHashMap<>();
        for (Comment comment : comments) {
            CommentVO vo = new CommentVO();
            vo.setId(comment.getId());
            vo.setArticleId(comment.getArticleId());
            vo.setParentId(comment.getParentId());
            vo.setContent(comment.getContent());
            vo.setCreatedAt(comment.getCreatedAt());
            User user = users.get(comment.getUserId());
            if (user != null) {
                vo.setUser(new UserBriefVO(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar()));
            }
            voById.put(vo.getId(), vo);
        }
        for (Comment comment : comments) {
            if (comment.getParentId() != null) {
                CommentVO reply = voById.get(comment.getId());
                CommentVO parent = voById.get(comment.getParentId());
                if (parent != null) {
                    reply.setReplyTo(parent.getUser());
                }
            }
        }
        return new ArrayList<>(voById.values());
    }
}
