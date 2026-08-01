package com.blogsys.service;

import com.blogsys.common.BizException;
import com.blogsys.dto.CommentRequest;
import com.blogsys.entity.Article;
import com.blogsys.entity.Comment;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.CommentMapper;
import com.blogsys.security.LoginUser;
import com.blogsys.vo.CommentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private UserService userService;

    private CommentService commentService;

    @BeforeEach
    void setUp() {
        commentService = new CommentService(commentMapper, articleMapper, userService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(1L, "alice", "USER"), null));
    }

    @Test
    void create_shouldSucceed_whenArticleExists() {
        Article article = new Article();
        article.setId(10L);
        article.setStatus(1);
        when(articleMapper.selectById(10L)).thenReturn(article);

        CommentRequest request = new CommentRequest();
        request.setContent("写得好!");
        CommentVO vo = commentService.create(10L, request);

        assertEquals("写得好!", vo.getContent());
        assertEquals(10L, vo.getArticleId());
        verify(commentMapper).insert(any(Comment.class));
        verify(articleMapper).update(any(), any());
    }

    @Test
    void create_shouldFail_whenArticleNotExists() {
        when(articleMapper.selectById(10L)).thenReturn(null);

        CommentRequest request = new CommentRequest();
        request.setContent("写得好!");

        assertThrows(BizException.class, () -> commentService.create(10L, request));
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    void delete_shouldFail_whenNotAuthorAndNotAdmin() {
        Comment comment = new Comment();
        comment.setId(5L);
        comment.setUserId(999L);
        comment.setArticleId(10L);
        when(commentMapper.selectById(5L)).thenReturn(comment);

        assertThrows(BizException.class, () -> commentService.delete(5L));
        verify(commentMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void delete_shouldSucceed_forAuthor() {
        Comment comment = new Comment();
        comment.setId(5L);
        comment.setUserId(1L);
        comment.setArticleId(10L);
        when(commentMapper.selectById(5L)).thenReturn(comment);

        commentService.delete(5L);

        verify(commentMapper).deleteById(5L);
        verify(articleMapper).update(any(), any());
    }
}
