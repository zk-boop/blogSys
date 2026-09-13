package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsys.common.ArticleStatus;
import com.blogsys.common.BizException;
import com.blogsys.common.PageResult;
import com.blogsys.dto.CommentRequest;
import com.blogsys.entity.Article;
import com.blogsys.entity.Comment;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.CommentMapper;
import com.blogsys.security.LoginUser;
import com.blogsys.security.SecurityUtil;
import com.blogsys.visibility.Visibility;
import com.blogsys.visibility.VisibleArticle;
import com.blogsys.vo.AdminCommentVO;
import com.blogsys.vo.CommentVO;
import com.blogsys.vo.UserBriefVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final ArticleMapper articleMapper;
    private final UserService userService;
    private final Visibility visibility;

    public List<CommentVO> listByArticle(Long articleId) {
        // 草稿:作者与管理员现在拿到的是真实评论列表,而不是迁移前那个「200 + 空数组」——
        // 那让草稿的评论区与「零评论」在客户端上无法区分。
        // 被封禁作者的文章:非管理员一律 404,与详情页口径一致(此前这里是 200 + 评论树)。
        VisibleArticle article = visibility.articles().includingOwnDrafts().require(articleId);
        return buildTree(toVOs(visibility.commentsOf(article)));
    }

    public PageResult<AdminCommentVO> adminPage(long page, long size, String keyword) {
        Page<Comment> result = commentMapper.selectPage(new Page<>(page, size),
                Wrappers.<Comment>lambdaQuery()
                        .like(StringUtils.hasText(keyword), Comment::getContent, keyword)
                        .orderByDesc(Comment::getCreatedAt));
        List<AdminCommentVO> records = toAdminVOs(result.getRecords());
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    private List<AdminCommentVO> toAdminVOs(List<Comment> comments) {
        if (comments.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = comments.stream().map(Comment::getUserId).distinct().toList();
        Map<Long, User> users = userService.findByIds(userIds);
        List<Long> articleIds = comments.stream().map(Comment::getArticleId).distinct().toList();
        Map<Long, String> articleTitles = articleMapper.selectBatchIds(articleIds).stream()
                .collect(Collectors.toMap(Article::getId, Article::getTitle));
        return comments.stream().map(comment -> {
            AdminCommentVO vo = new AdminCommentVO();
            vo.setId(comment.getId());
            vo.setArticleId(comment.getArticleId());
            vo.setArticleTitle(articleTitles.getOrDefault(comment.getArticleId(), "(文章已删除)"));
            vo.setContent(comment.getContent());
            vo.setCreatedAt(comment.getCreatedAt());
            User user = users.get(comment.getUserId());
            if (user != null) {
                vo.setUser(new UserBriefVO(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar()));
            }
            return vo;
        }).toList();
    }

    @Transactional
    public CommentVO create(Long articleId, CommentRequest request) {
        // 不可见 = 不存在,写操作也一样(见 ADR-0001)
        visibility.articles().require(articleId);
        Long parentId = request.getParentId();
        if (parentId != null) {
            Comment parent = commentMapper.selectById(parentId);
            if (parent == null || !parent.getArticleId().equals(articleId)) {
                throw new BizException(400, "被回复的评论不存在");
            }
        }
        Comment comment = new Comment();
        comment.setArticleId(articleId);
        comment.setUserId(SecurityUtil.currentUserId());
        comment.setParentId(parentId);
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
        List<Long> toDelete = collectSubtree(comment);
        commentMapper.deleteBatchIds(toDelete);
        articleMapper.incrCommentCount(comment.getArticleId(), -toDelete.size());
    }

    private List<Long> collectSubtree(Comment root) {
        List<Comment> all = commentMapper.selectList(
                Wrappers.<Comment>lambdaQuery().eq(Comment::getArticleId, root.getArticleId()));
        List<Long> toDelete = new ArrayList<>();
        toDelete.add(root.getId());
        for (int i = 0; i < toDelete.size(); i++) {
            Long parentId = toDelete.get(i);
            for (Comment c : all) {
                if (parentId.equals(c.getParentId()) && !toDelete.contains(c.getId())) {
                    toDelete.add(c.getId());
                }
            }
        }
        return toDelete;
    }

    private List<CommentVO> buildTree(List<CommentVO> flat) {
        Map<Long, CommentVO> all = new LinkedHashMap<>();
        for (CommentVO vo : flat) {
            all.put(vo.getId(), vo);
        }
        for (CommentVO vo : flat) {
            if (vo.getParentId() == null) {
                vo.setReplies(new ArrayList<>());
            }
        }
        Map<Long, CommentVO> roots = new LinkedHashMap<>();
        for (CommentVO vo : flat) {
            CommentVO root = findRoot(vo, all);
            if (root == vo) {
                roots.put(vo.getId(), vo);
            }
        }
        for (CommentVO vo : flat) {
            if (vo.getParentId() != null) {
                CommentVO root = findRoot(vo, all);
                if (root != vo && root.getReplies() != null) {
                    root.getReplies().add(vo);
                }
            }
        }
        return new ArrayList<>(roots.values());
    }

    private CommentVO findRoot(CommentVO vo, Map<Long, CommentVO> all) {
        CommentVO cur = vo;
        Set<Long> seen = new HashSet<>();
        while (cur.getParentId() != null) {
            CommentVO parent = all.get(cur.getParentId());
            if (parent == null || !seen.add(parent.getId())) {
                break;
            }
            cur = parent;
        }
        return cur;
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
