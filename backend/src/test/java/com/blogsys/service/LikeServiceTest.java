package com.blogsys.service;

import com.blogsys.common.BizException;
import com.blogsys.entity.Article;
import com.blogsys.entity.Like;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.LikeMapper;
import com.blogsys.security.LoginUser;
import com.blogsys.vo.LikeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikeServiceTest {

    @Mock
    private LikeMapper likeMapper;

    @Mock
    private ArticleMapper articleMapper;

    private LikeService likeService;

    @BeforeEach
    void setUp() {
        likeService = new LikeService(likeMapper, articleMapper);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(1L, "alice", "USER"), null));
    }

    @AfterEach
    void clearSecurityContext() {
        // 不清的话,登录态会泄漏给后面跑的测试类:线程局部的寿命比这个类长。
        SecurityContextHolder.clearContext();
    }

    @Test
    void toggle_shouldLike_whenNotLikedYet() {
        Article article = new Article();
        article.setId(10L);
        article.setStatus(1);
        article.setLikeCount(3);
        Article afterLike = new Article();
        afterLike.setId(10L);
        afterLike.setLikeCount(4);
        when(articleMapper.selectById(10L)).thenReturn(article, afterLike);
        when(likeMapper.selectOne(any())).thenReturn(null);

        LikeVO result = likeService.toggle(10L);

        assertTrue(result.isLiked());
        assertEquals(4, result.getLikeCount());
        verify(likeMapper).insert(any(Like.class));
        verify(articleMapper).incrLikeCount(10L, 1);
    }

    @Test
    void toggle_shouldUnlike_whenAlreadyLiked() {
        Article article = new Article();
        article.setId(10L);
        article.setStatus(1);
        article.setLikeCount(4);
        Article afterUnlike = new Article();
        afterUnlike.setId(10L);
        afterUnlike.setLikeCount(3);
        Like existing = new Like();
        existing.setId(99L);
        when(articleMapper.selectById(10L)).thenReturn(article, afterUnlike);
        when(likeMapper.selectOne(any())).thenReturn(existing);

        LikeVO result = likeService.toggle(10L);

        assertFalse(result.isLiked());
        assertEquals(3, result.getLikeCount());
        verify(likeMapper).deleteById(99L);
        verify(articleMapper).incrLikeCount(10L, -1);
    }

    @Test
    void toggle_shouldFail_whenArticleNotFound() {
        when(articleMapper.selectById(10L)).thenReturn(null);
        assertThrows(BizException.class, () -> likeService.toggle(10L));
    }
}
