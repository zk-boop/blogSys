package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsys.common.ArticleStatus;
import com.blogsys.common.BizException;
import com.blogsys.common.PageResult;
import com.blogsys.dto.ArticleRequest;
import com.blogsys.entity.Article;
import com.blogsys.entity.ArticleTag;
import com.blogsys.entity.Comment;
import com.blogsys.entity.Like;
import com.blogsys.entity.Tag;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.ArticleTagMapper;
import com.blogsys.mapper.CommentMapper;
import com.blogsys.mapper.LikeMapper;
import com.blogsys.mapper.TagMapper;
import com.blogsys.security.LoginUser;
import com.blogsys.security.SecurityUtil;
import com.blogsys.vo.ArticleDetailVO;
import com.blogsys.vo.ArticleListItemVO;
import com.blogsys.vo.UserBriefVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleMapper articleMapper;
    private final TagMapper tagMapper;
    private final ArticleTagMapper articleTagMapper;
    private final CommentMapper commentMapper;
    private final LikeMapper likeMapper;
    private final UserService userService;

    public PageResult<ArticleListItemVO> page(long page, long size, Long tagId, String keyword) {
        Page<Article> result;
        var wrapper = Wrappers.<Article>lambdaQuery()
                .eq(Article::getStatus, ArticleStatus.PUBLISHED.getValue())
                .orderByDesc(Article::getCreatedAt);
        if (tagId != null) {
            List<Long> articleIds = articleTagMapper.selectList(
                            Wrappers.<ArticleTag>lambdaQuery().eq(ArticleTag::getTagId, tagId))
                    .stream().map(ArticleTag::getArticleId).toList();
            if (articleIds.isEmpty()) {
                return new PageResult<>(0, page, size, new ArrayList<>());
            }
            wrapper.in(Article::getId, articleIds);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = escapeLike(keyword);
            wrapper.and(w -> w.like(Article::getTitle, kw).or().like(Article::getContent, kw));
        }
        result = articleMapper.selectPage(new Page<>(page, size), wrapper);
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(),
                attachUserAndTags(result.getRecords()));
    }

    public PageResult<ArticleListItemVO> pageByUser(long page, long size, Long userId, Integer status) {
        Page<Article> result = articleMapper.selectPage(new Page<>(page, size),
                Wrappers.<Article>lambdaQuery()
                        .eq(Article::getUserId, userId)
                        .eq(status != null, Article::getStatus, status)
                        .orderByDesc(Article::getCreatedAt));
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(),
                attachUserAndTags(result.getRecords()));
    }

    public List<ArticleListItemVO> hot(int size) {
        List<Article> articles = articleMapper.selectList(
                Wrappers.<Article>lambdaQuery()
                        .eq(Article::getStatus, ArticleStatus.PUBLISHED.getValue())
                        .gt(Article::getViewCount, 0)
                        .orderByDesc(Article::getViewCount)
                        .last("LIMIT " + Math.min(size, 20)));
        return attachUserAndTags(articles);
    }

    public ArticleDetailVO detail(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BizException(404, "文章不存在");
        }
        if (Objects.equals(article.getStatus(), ArticleStatus.DRAFT.getValue())) {
            if (!canViewDraft(article.getUserId())) {
                throw new BizException(404, "文章不存在");
            }
        } else {
            articleMapper.incrViewCount(id);
            article.setViewCount(article.getViewCount() + 1);
        }

        ArticleDetailVO vo = new ArticleDetailVO();
        copyBase(article, vo);
        vo.setContent(article.getContent());
        vo.setLiked(isLikedByCurrentUser(id));
        attachAuthorAndTags(vo, article);
        return vo;
    }

    private boolean canViewDraft(Long ownerId) {
        try {
            LoginUser loginUser = SecurityUtil.currentUser();
            return loginUser.getId().equals(ownerId) || "ADMIN".equals(loginUser.getRole());
        } catch (BizException e) {
            return false;
        }
    }

    public ArticleDetailVO editDetail(Long id) {
        Article article = requireOwnArticle(id);
        ArticleDetailVO vo = new ArticleDetailVO();
        copyBase(article, vo);
        vo.setContent(article.getContent());
        attachAuthorAndTags(vo, article);
        return vo;
    }

    @Transactional
    public Long create(ArticleRequest request) {
        Long userId = SecurityUtil.currentUserId();
        Article article = new Article();
        article.setUserId(userId);
        applyRequest(article, request);
        article.setStatus(resolveStatus(request));
        article.setViewCount(0);
        article.setLikeCount(0);
        article.setCommentCount(0);
        articleMapper.insert(article);
        syncTags(article.getId(), request.getTagNames());
        return article.getId();
    }

    @Transactional
    public void update(Long id, ArticleRequest request) {
        Article article = requireOwnArticle(id);
        applyRequest(article, request);
        article.setStatus(resolveStatus(request));
        articleMapper.updateById(article);
        syncTags(id, request.getTagNames());
    }

    @Transactional
    public void delete(Long id) {
        Article article = requireOwnArticle(id);
        List<Long> removedTagIds = tagIdsOfArticle(id);
        articleMapper.deleteById(id);
        articleTagMapper.delete(Wrappers.<ArticleTag>lambdaQuery().eq(ArticleTag::getArticleId, id));
        commentMapper.delete(Wrappers.<Comment>lambdaQuery().eq(Comment::getArticleId, id));
        likeMapper.delete(Wrappers.<Like>lambdaQuery().eq(Like::getArticleId, id));
        cleanupOrphanTags(removedTagIds);
    }

    public Map<Long, Article> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return articleMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Article::getId, Function.identity()));
    }

    private Article requireOwnArticle(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BizException(404, "文章不存在");
        }
        SecurityUtil.requireOwnerOrAdmin(article.getUserId());
        return article;
    }

    private int resolveStatus(ArticleRequest request) {
        return Boolean.TRUE.equals(request.getDraft())
                ? ArticleStatus.DRAFT.getValue()
                : ArticleStatus.PUBLISHED.getValue();
    }

    private void applyRequest(Article article, ArticleRequest request) {
        article.setTitle(request.getTitle());
        article.setContent(request.getContent());
        article.setSummary(StringUtils.hasText(request.getSummary()) ? request.getSummary() : "");
        article.setCover(StringUtils.hasText(request.getCover()) ? request.getCover() : "");
    }

    private String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private void syncTags(Long articleId, List<String> tagNames) {
        List<Long> removedTagIds = tagIdsOfArticle(articleId);
        articleTagMapper.delete(Wrappers.<ArticleTag>lambdaQuery().eq(ArticleTag::getArticleId, articleId));
        if (tagNames == null) {
            cleanupOrphanTags(removedTagIds);
            return;
        }
        Set<String> unique = tagNames.stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        for (String name : unique) {
            articleTagMapper.insert(buildRelation(articleId, findOrCreateTag(name)));
        }
        cleanupOrphanTags(removedTagIds);
    }

    private List<Long> tagIdsOfArticle(Long articleId) {
        return articleTagMapper.selectList(
                        Wrappers.<ArticleTag>lambdaQuery().eq(ArticleTag::getArticleId, articleId))
                .stream().map(ArticleTag::getTagId).distinct().toList();
    }

    private void cleanupOrphanTags(List<Long> tagIds) {
        for (Long tagId : tagIds) {
            long refs = articleTagMapper.selectCount(
                    Wrappers.<ArticleTag>lambdaQuery().eq(ArticleTag::getTagId, tagId));
            if (refs == 0) {
                tagMapper.deleteById(tagId);
            }
        }
    }

    private Tag findOrCreateTag(String name) {
        Tag tag = tagMapper.selectOne(Wrappers.<Tag>lambdaQuery().eq(Tag::getName, name));
        if (tag != null) {
            return tag;
        }
        Tag created = new Tag();
        created.setName(name);
        try {
            tagMapper.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            tag = tagMapper.selectOne(Wrappers.<Tag>lambdaQuery().eq(Tag::getName, name));
            if (tag == null) {
                throw e;
            }
            return tag;
        }
    }

    private ArticleTag buildRelation(Long articleId, Tag tag) {
        ArticleTag articleTag = new ArticleTag();
        articleTag.setArticleId(articleId);
        articleTag.setTagId(tag.getId());
        return articleTag;
    }

    private List<ArticleListItemVO> attachUserAndTags(List<Article> articles) {
        if (articles.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> userIds = articles.stream().map(Article::getUserId).distinct().toList();
        Map<Long, User> users = userService.findByIds(userIds);
        Map<Long, List<String>> tagsByArticle = findTagsByArticles(
                articles.stream().map(Article::getId).toList());
        return articles.stream().map(article -> {
            ArticleListItemVO vo = new ArticleListItemVO();
            copyBase(article, vo);
            User user = users.get(article.getUserId());
            if (user != null) {
                vo.setAuthor(new UserBriefVO(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar()));
            }
            vo.setTags(tagsByArticle.getOrDefault(article.getId(), List.of()));
            return vo;
        }).toList();
    }

    private void attachAuthorAndTags(ArticleListItemVO vo, Article article) {
        User user = userService.findByIds(List.of(article.getUserId())).get(article.getUserId());
        if (user != null) {
            vo.setAuthor(new UserBriefVO(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar()));
        }
        vo.setTags(findTagsByArticles(List.of(article.getId())).getOrDefault(article.getId(), List.of()));
    }

    private Map<Long, List<String>> findTagsByArticles(List<Long> articleIds) {
        List<ArticleTag> relations = articleTagMapper.selectList(
                Wrappers.<ArticleTag>lambdaQuery().in(ArticleTag::getArticleId, articleIds));
        if (relations.isEmpty()) {
            return Map.of();
        }
        List<Long> tagIds = relations.stream().map(ArticleTag::getTagId).distinct().toList();
        Map<Long, String> tagNames = tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, Tag::getName));
        return relations.stream().collect(Collectors.groupingBy(
                ArticleTag::getArticleId,
                Collectors.mapping(at -> tagNames.get(at.getTagId()), Collectors.toList())));
    }

    private boolean isLikedByCurrentUser(Long articleId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser loginUser)) {
            return false;
        }
        return likeMapper.selectCount(Wrappers.<Like>lambdaQuery()
                .eq(Like::getArticleId, articleId)
                .eq(Like::getUserId, loginUser.getId())) > 0;
    }

    private void copyBase(Article article, ArticleListItemVO vo) {
        vo.setId(article.getId());
        vo.setTitle(article.getTitle());
        vo.setSummary(article.getSummary());
        vo.setCover(article.getCover());
        vo.setCoverThumb(coverThumbOf(article.getCover()));
        vo.setStatus(article.getStatus());
        vo.setViewCount(article.getViewCount());
        vo.setLikeCount(article.getLikeCount());
        vo.setCommentCount(article.getCommentCount());
        vo.setCreatedAt(article.getCreatedAt());
    }

    private String coverThumbOf(String cover) {
        if (!StringUtils.hasText(cover) || !cover.startsWith("/uploads/") || cover.endsWith(".webp")) {
            return cover;
        }
        return cover.replaceAll("\\.\\w+$", "-thumb.jpg");
    }
}
