package com.blogsys.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.blogsys.common.BizException;
import com.blogsys.entity.Article;
import com.blogsys.entity.Comment;
import com.blogsys.entity.Like;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.CommentMapper;
import com.blogsys.mapper.LikeMapper;
import com.blogsys.security.LoginUser;
import com.blogsys.visibility.DefaultVisibility;
import com.blogsys.visibility.Viewer;
import com.blogsys.visibility.ViewerSource;
import com.blogsys.vo.LikeVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 点赞。
 *
 * <p>取数方式变了一处,值得记下:文章的<b>可见性检查</b>现在走模块(内部用 {@code selectOne}),
 * 而读回最新点赞数仍是 {@code selectById}。迁移前两者共用同一个 {@code selectById},
 * 所以旧测试的桩要一次给两个返回值;现在各给各的。
 */
@ExtendWith(MockitoExtension.class)
class LikeServiceTest {

    @Mock
    private LikeMapper likeMapper;

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private CommentMapper commentMapper;

    private LikeService likeService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(Article.class, User.class, Comment.class, Like.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        likeService = new LikeService(likeMapper, articleMapper,
                new DefaultVisibility(articleMapper, commentMapper, ViewerSource.fixed(Viewer.of(1L, false))));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(1L, "alice", "USER"), null));
    }

    @AfterEach
    void clearSecurityContext() {
        // 不清的话,登录态会泄漏给后面跑的测试类:线程局部的寿命比这个类长。
        SecurityContextHolder.clearContext();
    }

    private static Article article(long id, int likeCount) {
        Article article = new Article();
        article.setId(id);
        article.setStatus(1);
        article.setLikeCount(likeCount);
        return article;
    }

    @Test
    void toggle_shouldLike_whenNotLikedYet() {
        when(articleMapper.selectOne(any())).thenReturn(article(10L, 3));
        when(articleMapper.selectById(10L)).thenReturn(article(10L, 4));
        when(likeMapper.selectOne(any())).thenReturn(null);

        LikeVO result = likeService.toggle(10L);

        assertTrue(result.isLiked());
        assertEquals(4, result.getLikeCount());
        verify(likeMapper).insert(any(Like.class));
        verify(articleMapper).incrLikeCount(10L, 1);
    }

    @Test
    void toggle_shouldUnlike_whenAlreadyLiked() {
        Like existing = new Like();
        existing.setId(99L);
        when(articleMapper.selectOne(any())).thenReturn(article(10L, 4));
        when(articleMapper.selectById(10L)).thenReturn(article(10L, 3));
        when(likeMapper.selectOne(any())).thenReturn(existing);

        LikeVO result = likeService.toggle(10L);

        assertFalse(result.isLiked());
        assertEquals(3, result.getLikeCount());
        verify(likeMapper).deleteById(99L);
        verify(articleMapper).incrLikeCount(10L, -1);
    }

    @Test
    @DisplayName("不可见的文章:404,且不产生任何写入")
    void toggle_shouldFail_whenArticleNotVisible() {
        when(articleMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> likeService.toggle(10L));

        assertEquals(404, e.getCode());
        assertEquals("文章不存在", e.getMessage());
        verify(likeMapper, never()).insert(any(Like.class));
        verify(articleMapper, never()).incrLikeCount(any(), anyInt());
    }

    @Test
    @DisplayName("可见性检查问的是模块,而不是 service 自己查 status")
    void toggle_shouldAskTheModuleForVisibility() {
        when(articleMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> likeService.toggle(10L));

        ArgumentCaptor<Wrapper<Article>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(articleMapper).selectOne(captor.capture());
        String sql = captor.getValue().getSqlSegment();

        assertTrue(sql.contains("FROM users"),
                "点赞的可见性检查必须带封禁谓词 —— 迁移前只查 status,于是详情页 404 的文章照样能被点赞: " + sql);
        assertTrue(sql.contains("id = #{"), "仍然是针对这一篇: " + sql);
    }
}
