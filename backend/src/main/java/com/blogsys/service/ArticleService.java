package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsys.common.ArticleStatus;
import com.blogsys.common.BizException;
import com.blogsys.common.LikePattern;
import com.blogsys.common.PageResult;
import com.blogsys.dto.ArticleRequest;
import com.blogsys.entity.Article;
import com.blogsys.entity.ArticleTag;
import com.blogsys.entity.Comment;
import com.blogsys.entity.Favorite;
import com.blogsys.entity.Like;
import com.blogsys.entity.Tag;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.ArticleTagMapper;
import com.blogsys.mapper.CommentMapper;
import com.blogsys.mapper.FavoriteMapper;
import com.blogsys.mapper.LikeMapper;
import com.blogsys.mapper.TagMapper;
import com.blogsys.security.LoginUser;
import com.blogsys.security.SecurityUtil;
import com.blogsys.visibility.ArticleQuery;
import com.blogsys.visibility.Visibility;
import com.blogsys.visibility.ViewerSource;
import com.blogsys.vo.ArticleDetailVO;
import com.blogsys.vo.ArticleListItemVO;
import com.blogsys.vo.FavoriteVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final FavoriteMapper favoriteMapper;
    /** 列表项的组装(作者、标签、缩略图)归它 —— 详情页也走这里,于是形状只有一份。 */
    private final ArticleListItems listItems;
    private final Visibility visibility;
    /**
     * 「谁在看」的入口。读侧的身份读取一律问它 —— 与 {@link Visibility} 同一个来源,
     * 于是「这篇文章可见吗」与「我收藏过吗」不可能来自两个不同的人。
     */
    private final ViewerSource viewerSource;

    public PageResult<ArticleListItemVO> page(long page, long size, Long tagId, String keyword) {
        ArticleQuery query = visibility.articles().orderByDesc(Article::getCreatedAt);
        if (tagId != null) {
            List<Long> articleIds = articleTagMapper.selectList(
                            Wrappers.<ArticleTag>lambdaQuery().eq(ArticleTag::getTagId, tagId))
                    .stream().map(ArticleTag::getArticleId).toList();
            if (articleIds.isEmpty()) {
                return new PageResult<>(0, page, size, new ArrayList<>());
            }
            query = query.where(w -> w.in(Article::getId, articleIds));
        }
        if (StringUtils.hasText(keyword)) {
            String kw = LikePattern.of(keyword);
            // 外层 where 已经把这一组整体括起,所以这里的 or() 只在本组内生效
            query = query.where(w -> w.like(Article::getTitle, kw).or().like(Article::getContent, kw));
        }
        Page<Article> result = query.page(page, size);
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(),
                listItems.of(result.getRecords()));
    }

    /**
     * 某个作者的文章列表。
     *
     * <p><b>语义由 viewer 决定,不由调用方各传一个 status。</b>迁移前这个方法收一个
     * {@code status} 参数,而两个调用方各传各的:个人中心传 {@code null}(草稿也返回)、
     * 他人主页传 {@code PUBLISHED} 并且另外靠一次「丢弃返回值的 publicProfile 调用」
     * 来做封禁过滤 —— 同一个方法、两套规则,而这个方法本身一条都不管。
     *
     * <p>现在:看自己(含自己的草稿)、看别人(只有已发布且作者未被封禁)。
     */
    public PageResult<ArticleListItemVO> pageByUser(long page, long size, Long userId) {
        ArticleQuery query = visibility.articles()
                .includingOwnDrafts()
                .where(w -> w.eq(Article::getUserId, userId))
                .orderByDesc(Article::getCreatedAt);
        Page<Article> result = query.page(page, size);
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(),
                listItems.of(result.getRecords()));
    }

    public List<ArticleListItemVO> hot(int size) {
        List<Article> articles = visibility.articles()
                .where(w -> w.gt(Article::getViewCount, 0))
                .orderByDesc(Article::getViewCount)
                .list(Math.min(size, 20));
        return listItems.of(articles);
    }

    public PageResult<ArticleListItemVO> adminPage(long page, long size, String keyword, Integer status, Long userId) {
        Page<Article> result = articleMapper.selectPage(new Page<>(page, size),
                Wrappers.<Article>lambdaQuery()
                        .eq(status != null, Article::getStatus, status)
                        .eq(userId != null, Article::getUserId, userId)
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(Article::getTitle, LikePattern.of(keyword))
                                .or().like(Article::getContent, LikePattern.of(keyword)))
                        .orderByDesc(Article::getCreatedAt));
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(),
                listItems.of(result.getRecords()));
    }

    /**
     * 一篇文章的详情。
     *
     * <p><b>这是一次纯读,不产生任何副作用。</b>「浏览量 +1」曾经长在这个方法里,于是
     * AI 工具路径({@code ArticleDetailTool})每查一次详情就给那篇文章加热一次 ——
     * 而浏览量正是 {@code hot()} 的排序依据。记浏览现在由调用方显式表达:
     * HTTP 层调 {@link #recordView(Long)},AI 工具路径不调。
     */
    public ArticleDetailVO detail(Long id) {
        Article article = visibility.articles().includingOwnDrafts().require(id).article();

        ArticleDetailVO vo = new ArticleDetailVO();
        listItems.fill(article, vo);
        vo.setContent(article.getContent());
        vo.setLiked(isLikedByCurrentUser(id));
        vo.setFavorited(isFavoritedByCurrentUser(id));
        return vo;
    }

    /**
     * 记一次浏览 —— <b>全库唯一</b>一处让 {@code view_count} 增加的入口。
     *
     * <p>与 {@link #detail(Long)} 同一口径地判可见性:不可见即不存在,抛同一个 404。
     * 只有已发布的文章计数,草稿(含作者本人和管理员看到的)不计 —— 与迁移前一致。
     *
     * <p>它被单独拆出来的理由是可证伪的一点:<b>「只读」应当由构造保证,而不是靠调用方
     * 记得传一个 readonly 标志。</b>现在读操作里没有写,所以没有任何标志需要传错。
     */
    public void recordView(Long id) {
        Article article = visibility.articles().includingOwnDrafts().require(id).article();
        if (ArticleStatus.of(article.getStatus()) == ArticleStatus.PUBLISHED) {
            articleMapper.incrViewCount(id);
        }
    }

    @Transactional
    public FavoriteVO toggleFavorite(Long articleId) {
        visibility.articles().require(articleId);
        Long userId = SecurityUtil.currentUserId();
        Favorite existing = favoriteMapper.selectOne(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getArticleId, articleId));
        if (existing != null) {
            favoriteMapper.deleteById(existing.getId());
            return new FavoriteVO(false);
        }
        Favorite favorite = new Favorite();
        favorite.setUserId(userId);
        favorite.setArticleId(articleId);
        favoriteMapper.insert(favorite);
        return new FavoriteVO(true);
    }

    public PageResult<ArticleListItemVO> favoritesPage(long page, long size, Long userId) {
        // D6 的三个缺陷在这里一起消失:
        //   1. 过滤在 SQL 里,不再「先 subList 再过滤」—— 页内条数不再少于 size
        //   2. total 与 records 出自同一个 wrapper,不再是一个过滤、一个不过滤
        //   3. 草稿不再泄漏(迁移前这里完全没有 status 条件,别人的草稿也会被返回)
        Page<Favorite> favorites = favoriteMapper.selectPage(new Page<>(page, size),
                visibility.restrictToVisibleArticles(Wrappers.<Favorite>lambdaQuery()
                        .eq(Favorite::getUserId, userId)
                        .orderByDesc(Favorite::getCreatedAt)));
        Map<Long, Article> articles = findByIds(favorites.getRecords().stream()
                .map(Favorite::getArticleId)
                .toList());
        List<Article> ordered = favorites.getRecords().stream()
                .map(fav -> articles.get(fav.getArticleId()))
                .filter(Objects::nonNull)
                .toList();
        return new PageResult<>(favorites.getTotal(), page, size, listItems.of(ordered));
    }

    /**
     * 当前 viewer 是否收藏了这篇。
     *
     * <p><b>viewer 是被接受的依赖,不是环境状态。</b>这里此前读 {@code SecurityUtil.currentUserId()},
     * 而它在匿名时**抛 401** —— 于是这个方法得用
     * {@code try { … } catch (BizException e) { return false; } } 把一个表示「未登录」的异常
     * 当成控制流;而同一个文件里隔一百多行的 {@link #isLikedByCurrentUser} 又换了种写法,
     * 直接判 {@code SecurityContextHolder} 里的 auth 是不是 null。同一个问题、两种写法,
     * 而匿名分支两边都没有测试。
     *
     * <p>现在两处都问 {@link ViewerSource}:「未登录」是它的一个**正常取值**
     * ({@code Viewer.anonymous()}),不是异常。匿名分支因此成了一条普通路径 ——
     * 用 {@code ViewerSource.fixed(Viewer.anonymous())} 就能直接测,不必去改线程局部变量。
     */
    private boolean isFavoritedByCurrentUser(Long articleId) {
        Optional<Long> userId = viewerSource.current().id();
        if (userId.isEmpty()) {
            return false;
        }
        return favoriteMapper.selectCount(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId.get())
                .eq(Favorite::getArticleId, articleId)) > 0;
    }

    public ArticleDetailVO editDetail(Long id) {
        Article article = requireOwnArticle(id);
        ArticleDetailVO vo = new ArticleDetailVO();
        listItems.fill(article, vo);
        vo.setContent(article.getContent());
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
        favoriteMapper.delete(Wrappers.<Favorite>lambdaQuery().eq(Favorite::getArticleId, id));
        cleanupOrphanTags(removedTagIds);
    }

    /**
     * 按 id 批量取文章 —— <b>不做任何可见性过滤</b>,所以刻意不是 public。
     *
     * <p>迁移前它是 public 的,而唯一的调用者已经在 {@link #favoritesPage} 里
     * 由 {@code restrictToVisibleArticles} 保证了前置条件。收紧可见性,是为了让它
     * 不能再被当作一条绕过模块的读路径。
     */
    private Map<Long, Article> findByIds(Collection<Long> ids) {
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
        boolean draft = Boolean.TRUE.equals(request.getDraft());
        if (!draft && (!StringUtils.hasText(request.getTitle()) || !StringUtils.hasText(request.getContent()))) {
            throw new BizException("发布时标题和内容不能为空");
        }
        return draft ? ArticleStatus.DRAFT.getValue() : ArticleStatus.PUBLISHED.getValue();
    }

    private void applyRequest(Article article, ArticleRequest request) {
        article.setTitle(StringUtils.hasText(request.getTitle()) ? request.getTitle().trim() : "");
        article.setContent(request.getContent() == null ? "" : request.getContent());
        article.setSummary(StringUtils.hasText(request.getSummary()) ? request.getSummary() : "");
        article.setCover(StringUtils.hasText(request.getCover()) ? request.getCover() : "");
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

    /** 与 {@link #isFavoritedByCurrentUser} 同一个来源、同一个写法 —— 它们回答的是同一个问题。 */
    private boolean isLikedByCurrentUser(Long articleId) {
        Optional<Long> userId = viewerSource.current().id();
        if (userId.isEmpty()) {
            return false;
        }
        return likeMapper.selectCount(Wrappers.<Like>lambdaQuery()
                .eq(Like::getArticleId, articleId)
                .eq(Like::getUserId, userId.get())) > 0;
    }
}
