package com.blogsys.service;

import com.blogsys.common.BizException;
import com.blogsys.entity.Article;
import com.blogsys.entity.ArticleTag;
import com.blogsys.entity.Tag;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.ArticleTagMapper;
import com.blogsys.mapper.TagMapper;
import com.blogsys.vo.ArticleListItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendServiceTest {

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private ArticleTagMapper articleTagMapper;

    @Mock
    private TagMapper tagMapper;

    @Mock
    private UserService userService;

    private RecommendService recommendService;

    @BeforeEach
    void setUp() {
        recommendService = new RecommendService(articleMapper, articleTagMapper, tagMapper, userService);
    }

    private Article published(long id, long userId, String title, String summary) {
        Article article = new Article();
        article.setId(id);
        article.setUserId(userId);
        article.setTitle(title);
        article.setSummary(summary);
        article.setStatus(1);
        article.setViewCount(0);
        return article;
    }

    private Tag tag(long id, String name) {
        Tag tag = new Tag();
        tag.setId(id);
        tag.setName(name);
        return tag;
    }

    @Test
    void keywords_shouldExtractChineseBigramsAndEnglishWords() {
        Set<String> keywords = RecommendService.keywords("Java 微服务实战", "使用 Spring Boot 构建", "");

        assertTrue(keywords.contains("java"));
        assertTrue(keywords.contains("微服"));
        assertTrue(keywords.contains("服务"));
        assertTrue(keywords.contains("实战"));
        assertTrue(keywords.contains("spring"));
        assertTrue(keywords.contains("boot"));
        assertTrue(keywords.contains("使用"));
        assertTrue(keywords.contains("构建"));
    }

    @Test
    void keywords_shouldSkipStopWordsAndShortTokens() {
        Set<String> keywords = RecommendService.keywords("", "的一和一 A ab", "");

        assertFalse(keywords.contains("的"));
        assertFalse(keywords.contains("一"));
        assertFalse(keywords.contains("ab"));
    }

    @Test
    void keywords_shouldIncludeContentButTruncateIt() {
        Set<String> keywords = RecommendService.keywords("", "", "并发编程核心要点 并发编程核心要点");
        Set<String> contentKeywords = RecommendService.keywords("", "", "并发编程核心要点");

        assertEquals(contentKeywords, keywords);
        assertTrue(keywords.contains("并发"));
    }

    @Test
    void recommend_shouldRankSharedTagsFirst() {
        Article target = published(1L, 10L, "Java 入门指南", "JVM 基础");
        Article sameTag = published(2L, 11L, "Java 深入解析", "类加载机制");
        Article keywordOnly = published(4L, 13L, "JVM 调优笔记", "内存模型");
        Article different = published(3L, 12L, "摄影技巧分享", "相机参数");
        when(articleMapper.selectById(1L)).thenReturn(target);
        when(articleMapper.selectList(any())).thenReturn(List.of(sameTag, keywordOnly, different));
        when(userService.findByIds(any()))
                .thenReturn(Map.of(11L, active(11L), 13L, active(13L), 12L, active(12L)));
        when(tagMapper.selectBatchIds(List.of(1L, 2L)))
                .thenReturn(List.of(tag(1L, "java"), tag(2L, "摄影")));
        when(articleTagMapper.selectList(any())).thenReturn(List.of(
                rel(1L, 1L), rel(2L, 1L), rel(3L, 2L)));

        List<ArticleListItemVO> result = recommendService.recommend(1L, 5);

        assertEquals(2, result.size());
        assertEquals(2L, result.get(0).getId());
        assertEquals(4L, result.get(1).getId());
        assertEquals(List.of("java"), result.get(0).getTags());
        assertTrue(result.get(0).getRecommendScore() > result.get(1).getRecommendScore());
    }

    @Test
    void recommend_shouldExcludeBannedAuthor() {
        Article target = published(1L, 10L, "Java 入门", "JVM 基础");
        Article banned = published(2L, 99L, "Java 进阶", "并发编程");
        when(articleMapper.selectById(1L)).thenReturn(target);
        when(articleMapper.selectList(any())).thenReturn(List.of(banned));
        when(userService.findByIds(any())).thenReturn(Map.of(99L, bannedUser(99L)));

        List<ArticleListItemVO> result = recommendService.recommend(1L, 5);

        assertTrue(result.isEmpty());
    }

    @Test
    void recommend_shouldExcludeUnrelatedArticles() {
        Article target = published(1L, 10L, "Java 入门", "JVM 基础");
        Article unrelated = published(2L, 11L, "菜谱大全", "红烧肉做法");
        when(articleMapper.selectById(1L)).thenReturn(target);
        when(articleMapper.selectList(any())).thenReturn(List.of(unrelated));
        when(userService.findByIds(any())).thenReturn(Map.of(11L, active(11L)));

        List<ArticleListItemVO> result = recommendService.recommend(1L, 5);

        assertTrue(result.isEmpty());
    }

    @Test
    void recommend_shouldThrow_whenArticleNotFoundOrDraft() {
        when(articleMapper.selectById(99L)).thenReturn(null);
        assertThrows(BizException.class, () -> recommendService.recommend(99L, 5));

        Article draft = published(5L, 10L, "草稿", "");
        draft.setStatus(0);
        when(articleMapper.selectById(5L)).thenReturn(draft);
        assertThrows(BizException.class, () -> recommendService.recommend(5L, 5));
    }

    private User active(long id) {
        User user = new User();
        user.setId(id);
        user.setStatus(0);
        return user;
    }

    private User bannedUser(long id) {
        User user = new User();
        user.setId(id);
        user.setStatus(1);
        return user;
    }

    private ArticleTag rel(long articleId, long tagId) {
        ArticleTag articleTag = new ArticleTag();
        articleTag.setArticleId(articleId);
        articleTag.setTagId(tagId);
        return articleTag;
    }
}
