package com.blogsys.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.blogsys.common.BizException;
import com.blogsys.entity.Article;
import com.blogsys.entity.ArticleTag;
import com.blogsys.entity.Comment;
import com.blogsys.entity.Tag;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.ArticleTagMapper;
import com.blogsys.mapper.CommentMapper;
import com.blogsys.mapper.TagMapper;
import com.blogsys.visibility.DefaultVisibility;
import com.blogsys.visibility.Viewer;
import com.blogsys.visibility.ViewerSource;
import com.blogsys.vo.ArticleListItemVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 推荐算法的测试。
 *
 * <p><b>一处重要的覆盖迁移,必须说明。</b>迁移前有一个用例 {@code recommend_shouldExcludeBannedAuthor}
 * 断言「内存里剔掉被封禁作者的候选」。那次过滤已经从 Java 搬进了 SQL 谓词,而这里的 mapper 是 mock ——
 * mock 不评估谓词,一律返回桩定的行。所以同一个用例现在既写不出来、也证明不了任何事,
 * 硬留着只会变成「绿着但没测到东西」。
 *
 * <p>那件事现在由两处负责:
 * <ol>
 *   <li>{@code ArticleQueryTest} —— 断言非管理员的候选查询确实带封禁谓词</li>
 *   <li>针对真实数据的一次性核对 —— 逐条比对期望行集</li>
 * </ol>
 * 本类保留的是算法的纯逻辑,以及 service 与模块之间的契约。
 */
@ExtendWith(MockitoExtension.class)
class RecommendServiceTest {

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private ArticleTagMapper articleTagMapper;

    @Mock
    private TagMapper tagMapper;

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private UserService userService;

    private RecommendService recommendService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(Article.class, User.class, ArticleTag.class, Tag.class, Comment.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        recommendService = new RecommendService(articleMapper, articleTagMapper, tagMapper, userService,
                new DefaultVisibility(articleMapper, commentMapper, ViewerSource.fixed(Viewer.anonymous())));
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

    /* ---------- 纯算法 ---------- */

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

    /* ---------- 排序 ---------- */

    @Test
    void recommend_shouldRankSharedTagsFirst() {
        Article target = published(1L, 10L, "Java 入门指南", "JVM 基础");
        Article sameTag = published(2L, 11L, "Java 深入解析", "类加载机制");
        Article keywordOnly = published(4L, 13L, "JVM 调优笔记", "内存模型");
        Article different = published(3L, 12L, "摄影技巧分享", "相机参数");
        when(articleMapper.selectOne(any())).thenReturn(target);
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
    void recommend_shouldExcludeUnrelatedArticles() {
        Article target = published(1L, 10L, "Java 入门", "JVM 基础");
        Article unrelated = published(2L, 11L, "菜谱大全", "红烧肉做法");
        when(articleMapper.selectOne(any())).thenReturn(target);
        when(articleMapper.selectList(any())).thenReturn(List.of(unrelated));
        // 注意:这里不再需要 userService 的桩。迁移前 bannedUserIdsOf 会为整批候选查一次用户,
        // 好算出「谁被封禁」;那个查询现在没有存在的理由了 —— 封禁过滤在 SQL 里。
        // Mockito 的严格模式在我删掉过滤之后立刻报了这个桩多余,算是这次迁移的一个副作用证据。

        List<ArticleListItemVO> result = recommendService.recommend(1L, 5);

        assertTrue(result.isEmpty());
    }

    /* ---------- 契约:靶子文章走模块 ---------- */

    @Test
    @DisplayName("靶子文章不可见时,原样抛出模块的 404(消息与「不存在」一致)")
    void recommend_shouldPropagateTheModules404() {
        when(articleMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> recommendService.recommend(99L, 5));

        assertEquals(404, e.getCode());
        assertEquals("文章不存在", e.getMessage());
    }

    @Test
    @DisplayName("靶子文章的可见性由模块判断,而不是 service 自己查 status")
    void recommend_shouldAskTheModuleForTheTarget() {
        when(articleMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> recommendService.recommend(5L, 5));

        ArgumentCaptor<Wrapper<Article>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(articleMapper).selectOne(captor.capture());
        String sql = captor.getValue().getSqlSegment();

        assertTrue(sql.contains("FROM users"),
                "靶子的取数必须带封禁谓词 —— 迁移前这里只查 status,而同一函数的候选过滤却查了封禁,自相矛盾: " + sql);
        assertTrue(sql.contains("status = #{"),
                "同时限定已发布,所以草稿自然 404: " + sql);
    }

    @Test
    @DisplayName("候选查询同样带封禁谓词 —— 过滤发生在 SQL 里、LIMIT 之前")
    void recommend_shouldFilterCandidatesInSql_beforeTheLimit() {
        Article target = published(1L, 10L, "Java 入门", "JVM 基础");
        when(articleMapper.selectOne(any())).thenReturn(target);
        when(articleMapper.selectList(any())).thenReturn(List.of());

        recommendService.recommend(1L, 5);

        ArgumentCaptor<Wrapper<Article>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(articleMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();

        assertTrue(sql.contains("FROM users"), "候选必须排除被封禁作者,实际: " + sql);
        assertTrue(sql.contains("LIMIT"), "仍然限制候选数量,实际: " + sql);
        assertTrue(sql.contains("user_id <> #{"),
                "仍要排除靶子作者自己的文章,实际: " + sql);
    }

    private User active(long id) {
        User user = new User();
        user.setId(id);
        user.setStatus(0);
        return user;
    }

    private ArticleTag rel(long articleId, long tagId) {
        ArticleTag articleTag = new ArticleTag();
        articleTag.setArticleId(articleId);
        articleTag.setTagId(tagId);
        return articleTag;
    }
}
