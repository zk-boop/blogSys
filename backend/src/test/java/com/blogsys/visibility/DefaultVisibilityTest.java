package com.blogsys.visibility;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
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
