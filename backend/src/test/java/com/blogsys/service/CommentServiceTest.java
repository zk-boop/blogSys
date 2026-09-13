package com.blogsys.service;

import com.blogsys.common.BizException;
import com.blogsys.dto.CommentRequest;
import com.blogsys.entity.Article;
import com.blogsys.entity.Comment;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.CommentMapper;
import com.blogsys.security.LoginUser;
import com.blogsys.visibility.DefaultVisibility;
import com.blogsys.visibility.Viewer;
import com.blogsys.visibility.ViewerSource;
import com.blogsys.visibility.Visibility;
import com.blogsys.vo.CommentVO;
import org.junit.jupiter.api.AfterEach;
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
        Visibility visibility = new DefaultVisibility(articleMapper, commentMapper,
                ViewerSource.fixed(Viewer.of(1L, false)));
        commentService = new CommentService(commentMapper, articleMapper, userService, visibility);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(1L, "alice", "USER"), null));
    }

    @AfterEach
    void clearSecurityContext() {
        // 不清的话,登录态会泄漏给后面跑的测试类:线程局部的寿命比这个类长。
        // 实测它曾让 ViewerSourceTest 在「无上下文」用例里拿到 Viewer(1)。
        SecurityContextHolder.clearContext();
    }

    @Test
    void create_shouldSucceed_whenArticleExists() {
        Article article = new Article();
        article.setId(10L);
        article.setStatus(1);
        // 可见性检查走模块(内部用 selectOne),不再是 service 自己 selectById + 判 status
        when(articleMapper.selectOne(any())).thenReturn(article);

        CommentRequest request = new CommentRequest();
        request.setContent("写得好!");
        CommentVO vo = commentService.create(10L, request);

        assertEquals("写得好!", vo.getContent());
        assertEquals(10L, vo.getArticleId());
        verify(commentMapper).insert(any(Comment.class));
        verify(articleMapper).incrCommentCount(10L, 1);
    }

    @Test
    void createReply_shouldKeepRealParent_whenReplyingToReply() {
        Article article = new Article();
        article.setId(10L);
        article.setStatus(1);
        // 可见性检查走模块(内部用 selectOne),不再是 service 自己 selectById + 判 status
        when(articleMapper.selectOne(any())).thenReturn(article);

        Comment topLevel = new Comment();
        topLevel.setId(1L);
        topLevel.setArticleId(10L);
        topLevel.setParentId(null);
        Comment nestedReply = new Comment();
        nestedReply.setId(2L);
        nestedReply.setArticleId(10L);
        nestedReply.setParentId(1L);
        when(commentMapper.selectById(2L)).thenReturn(nestedReply);

        CommentRequest request = new CommentRequest();
        request.setContent("回复回复");
        request.setParentId(2L);
        CommentVO vo = commentService.create(10L, request);

        assertEquals(2L, vo.getParentId());
    }

    @Test
    void createReply_shouldFail_whenParentNotInArticle() {
        Article article = new Article();
        article.setId(10L);
        article.setStatus(1);
        // 可见性检查走模块(内部用 selectOne),不再是 service 自己 selectById + 判 status
        when(articleMapper.selectOne(any())).thenReturn(article);
        when(commentMapper.selectById(99L)).thenReturn(null);

        CommentRequest request = new CommentRequest();
        request.setContent("x");
        request.setParentId(99L);

        assertThrows(BizException.class, () -> commentService.create(10L, request));
    }

    @Test
    void create_shouldFail_whenArticleNotExists() {
        // 模块判定不可见/不存在时 find 拿不到行 —— 两者是同一条路径、同一个 404
        when(articleMapper.selectOne(any())).thenReturn(null);

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
        when(commentMapper.selectList(any())).thenReturn(java.util.List.of());

        commentService.delete(5L);

        verify(commentMapper).deleteBatchIds(java.util.List.of(5L));
        verify(articleMapper).incrCommentCount(10L, -1);
    }

    @Test
    void deleteTopLevel_shouldRemoveWholeSubtree() {
        Comment comment = new Comment();
        comment.setId(5L);
        comment.setUserId(1L);
        comment.setArticleId(10L);
        comment.setParentId(null);
        when(commentMapper.selectById(5L)).thenReturn(comment);

        Comment reply = new Comment();
        reply.setId(6L);
        reply.setUserId(2L);
        reply.setArticleId(10L);
        reply.setParentId(5L);
        Comment nested = new Comment();
        nested.setId(7L);
        nested.setUserId(3L);
        nested.setArticleId(10L);
        nested.setParentId(6L);
        when(commentMapper.selectList(any())).thenReturn(java.util.List.of(reply, nested));

        commentService.delete(5L);

        verify(commentMapper).deleteBatchIds(java.util.List.of(5L, 6L, 7L));
        verify(articleMapper).incrCommentCount(10L, -3);
    }
}
