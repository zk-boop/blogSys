package com.blogsys.visibility;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.blogsys.entity.Article;
import com.blogsys.entity.Comment;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.CommentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评论侧的可见性与令牌语义。
 *
 * <p>评论没有独立的状态字段:它可见 ⟺ 所属文章可见(由令牌保证)∧ 评论作者未被封禁。
 * 所以这里只断言两件事:封禁子查询在不在,以及用的是谁的 viewer。
 */
@ExtendWith(MockitoExtension.class)
class DefaultVisibilityTest {

    @Mock
    private ArticleMapper articleMapper;
    @Mock
    private CommentMapper commentMapper;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(Article.class, Comment.class, User.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    private Visibility visibilityFor(Viewer viewer) {
        return new DefaultVisibility(articleMapper, commentMapper, ViewerSource.fixed(viewer));
    }

    private static Article article(long id) {
        Article article = new Article();
        article.setId(id);
        article.setUserId(3L);
        article.setStatus(1);
        article.setViewCount(0);
        return article;
    }

    /** 拿一个令牌(走真实的 require 路径),再把实际发给 mapper 的 wrapper 抓回来。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private String commentsSql(Visibility visibility, Viewer viewerForToken) {
        when(articleMapper.selectOne(any())).thenReturn(article(7L));
        when(commentMapper.selectList(any())).thenReturn(List.of());

        VisibleArticle token = visibility.as(viewerForToken).articles().require(7L);
        visibility.commentsOf(token);

        ArgumentCaptor<AbstractWrapper> captor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(commentMapper).selectList(captor.capture());
        return captor.getValue().getSqlSegment();
    }

    @Test
    @DisplayName("匿名:评论按作者是否被封禁过滤,并按时间正序")
    void commentsOf_shouldFilterBannedAuthors_andOrderByCreatedAt() {
        String sql = commentsSql(visibilityFor(Viewer.anonymous()), Viewer.anonymous());

        assertTrue(sql.contains("article_id = #{"), "应限定到这一篇,实际: " + sql);
        assertTrue(sql.contains("FROM users WHERE status = #{"), "应排除被封禁的评论者,实际: " + sql);
        assertTrue(sql.toUpperCase().contains("ORDER BY"), "应排序,实际: " + sql);
    }

    @Test
    @DisplayName("管理员:省略封禁子查询 —— 与文章规则同一套豁免")
    void commentsOf_shouldOmitPredicate_forAdmin() {
        String sql = commentsSql(visibilityFor(Viewer.of(3L, true)), Viewer.of(3L, true));

        assertTrue(sql.contains("article_id = #{"), "实际: " + sql);
        assertFalse(sql.contains("FROM users"), "管理员不该被过滤,实际: " + sql);
    }

    @Test
    @DisplayName("封禁值用绑定参数,不进 SQL 文本")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void commentsOf_shouldBindTheActiveStatus() {
        when(articleMapper.selectOne(any())).thenReturn(article(7L));
        when(commentMapper.selectList(any())).thenReturn(List.of());

        Visibility visibility = visibilityFor(Viewer.anonymous());
        visibility.commentsOf(visibility.articles().require(7L));

        ArgumentCaptor<AbstractWrapper> captor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(commentMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        Map<String, Object> params = captor.getValue().getParamNameValuePairs();

        assertTrue(sql.contains("MPGENVAL"), "实际: " + sql);
        assertFalse(sql.matches(".*status\\s*=\\s*0.*"), "值不该出现在 SQL 文本里,实际: " + sql);
        assertTrue(params.containsValue(0), "参数表里应有 ACTIVE=0,实际: " + params);
    }

    @Test
    @DisplayName("用的是令牌里的 viewer,而不是调用时重新读一次环境")
    void commentsOf_shouldUseTheViewerCarriedByTheToken() {
        // 模块的环境里是匿名,但令牌是「以管理员身份」签发的。
        // 若实现重新读环境,这里会错误地加上封禁子查询。
        Visibility anonymousAmbient = visibilityFor(Viewer.anonymous());

        String sql = commentsSql(anonymousAmbient, Viewer.of(3L, true));

        assertFalse(sql.contains("FROM users"),
                "令牌带的 viewer 应当胜过重新读环境,实际: " + sql);
    }

    @Test
    @DisplayName("第三张表:非管理员用相关 EXISTS,列名是约定的 article_id,值全部走绑定")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void restrictToVisibleArticles_shouldUseExistsWithBoundValues() {
        Visibility visibility = visibilityFor(Viewer.anonymous());

        LambdaQueryWrapper<com.blogsys.entity.Favorite> wrapper = visibility.restrictToVisibleArticles(
                new LambdaQueryWrapper<com.blogsys.entity.Favorite>()
                        .eq(com.blogsys.entity.Favorite::getUserId, 1L));
        String sql = wrapper.getSqlSegment();
        Map<String, Object> params = wrapper.getParamNameValuePairs();

        assertTrue(sql.contains("EXISTS (SELECT 1 FROM articles a WHERE a.id = article_id"),
                "外层的文章外键列名是 schema 的约定,实际: " + sql);
        assertTrue(sql.contains("a.status = #{"), "已发布要绑定,实际: " + sql);
        assertTrue(sql.contains("FROM users WHERE status = #{"), "封禁条件要绑定,实际: " + sql);
        assertFalse(sql.matches(".*a\\.status\\s*=\\s*1.*"), "值不该出现在 SQL 文本里,实际: " + sql);
        assertTrue(params.containsValue(1) && params.containsValue(0),
                "参数表里应有已发布=1 与 正常=0,实际: " + params);
    }

    @Test
    @DisplayName("第三张表:管理员完全不加限制")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void restrictToVisibleArticles_shouldAddNothing_forAdmin() {
        Visibility visibility = visibilityFor(Viewer.of(3L, true));

        LambdaQueryWrapper<com.blogsys.entity.Favorite> wrapper = visibility.restrictToVisibleArticles(
                new LambdaQueryWrapper<com.blogsys.entity.Favorite>()
                        .eq(com.blogsys.entity.Favorite::getUserId, 1L));

        assertFalse(wrapper.getSqlSegment().contains("EXISTS"),
                "管理员不该有存在性限制,实际: " + wrapper.getSqlSegment());
    }

    @Test
    @DisplayName("第三张表:登录 viewer 会带上「也包含我的草稿」分支")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void restrictToVisibleArticles_shouldIncludeOwnDraftsBranch_forMember() {
        Visibility visibility = visibilityFor(Viewer.of(7L, false));

        LambdaQueryWrapper<com.blogsys.entity.Favorite> wrapper = visibility.restrictToVisibleArticles(
                new LambdaQueryWrapper<com.blogsys.entity.Favorite>());
        String sql = wrapper.getSqlSegment();

        assertTrue(sql.contains("(a.status = #{") && sql.contains("a.user_id = #{"),
                "登录者应当包含「已发布 或 是我自己的」分支,实际: " + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L),
                "viewer 的 id 应当作为绑定参数,实际: " + wrapper.getParamNameValuePairs());
    }

    @Test
    @DisplayName("commentsOf 原样返回 mapper 的结果,不做二次过滤")
    void commentsOf_shouldReturnMapperResultVerbatim() {
        Comment first = new Comment();
        first.setId(1L);
        Comment second = new Comment();
        second.setId(2L);
        when(articleMapper.selectOne(any())).thenReturn(article(7L));
        when(commentMapper.selectList(any())).thenReturn(List.of(first, second));

        Visibility visibility = visibilityFor(Viewer.anonymous());
        List<Comment> comments = visibility.commentsOf(visibility.articles().require(7L));

        assertEquals(2, comments.size());
        assertSame(first, comments.get(0));
    }
}
