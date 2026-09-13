package com.blogsys.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.blogsys.common.BizException;
import com.blogsys.entity.Article;
import com.blogsys.entity.ArticleTag;
import com.blogsys.entity.Comment;
import com.blogsys.entity.Favorite;
import com.blogsys.entity.Like;
import com.blogsys.entity.Tag;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.ArticleTagMapper;
import com.blogsys.mapper.CommentMapper;
import com.blogsys.mapper.FavoriteMapper;
import com.blogsys.mapper.LikeMapper;
import com.blogsys.mapper.TagMapper;
import com.blogsys.security.LoginUser;
import com.blogsys.visibility.DefaultVisibility;
import com.blogsys.visibility.Viewer;
import com.blogsys.visibility.ViewerSource;
import com.blogsys.visibility.Visibility;
import com.blogsys.vo.ArticleDetailVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ArticleService} 的第一个测试面。它此前是全库最大(429 行)、改动最多(10 次提交)
 * 却<b>零测试</b>的 module。
 *
 * <p><b>这个测试证明了什么、没证明什么,必须说清楚。</b>
 * 可见性判定活在 SQL 谓词里,而这里的 mapper 是 mock —— mock 不分 viewer 一律返回桩定的行。
 * 所以本测试<b>不能</b>证明「封禁作者的文章对匿名不可见」。
 *
 * <p>那件事由另外两处负责:
 * <ol>
 *   <li>{@code com.blogsys.visibility.ArticleQueryTest} —— 断言每种 viewer 实际发出的谓词文本</li>
 *   <li>针对真实数据的一次性核对(见本次提交说明):8 个用例逐条比对期望行集,全部一致</li>
 * </ol>
 *
 * <p>本测试负责的是 service 与模块之间的契约:模块说不可见时,service 必须抛出
 * 与「不存在」完全相同的 404;以及浏览量只在已发布时递增。
 */
@ExtendWith(MockitoExtension.class)
class ArticleServiceTest {

    @Mock
    private ArticleMapper articleMapper;
    @Mock
    private TagMapper tagMapper;
    @Mock
    private ArticleTagMapper articleTagMapper;
    @Mock
    private CommentMapper commentMapper;
    @Mock
    private LikeMapper likeMapper;
    @Mock
    private FavoriteMapper favoriteMapper;
    @Mock
    private UserService userService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(Article.class, User.class, ArticleTag.class, Tag.class,
                Comment.class, Like.class, Favorite.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private ArticleService serviceFor(Viewer viewer) {
        Visibility visibility = new DefaultVisibility(articleMapper, commentMapper, ViewerSource.fixed(viewer));
        return new ArticleService(articleMapper, tagMapper, articleTagMapper, commentMapper,
                likeMapper, favoriteMapper, userService, visibility);
    }

    /** 只有走 SecurityUtil 的路径(所有权校验)才需要它。 */
    private static void authenticate(Long id, String role) {
        LoginUser loginUser = new LoginUser(id, "someone", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private static Article article(long id, long authorId, int status, int viewCount) {
        Article article = new Article();
        article.setId(id);
        article.setUserId(authorId);
        article.setStatus(status);
        article.setTitle("标题");
        article.setContent("正文");
        article.setSummary("摘要");
        article.setCover("");
        article.setViewCount(viewCount);
        article.setLikeCount(0);
        article.setCommentCount(0);
        return article;
    }

    private void stubAttachments() {
        when(userService.findByIds(anyList())).thenReturn(Map.of());
        when(articleTagMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("不可见时抛 404,消息与「不存在」逐字相同 —— 两处稍有差别就是存在性预言机")
    void detail_shouldThrowCanonical404_whenNotVisible() {
        when(articleMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> serviceFor(Viewer.anonymous()).detail(5L));

        assertEquals(404, e.getCode());
        assertEquals("文章不存在", e.getMessage());
        verify(articleMapper, never()).incrViewCount(any());
    }

    @Test
    @DisplayName("不存在与不可见走的是同一条路径,拿到的是同一个异常")
    void detail_shouldNotDistinguishAbsentFromInvisible() {
        when(articleMapper.selectOne(any())).thenReturn(null);

        BizException absent = assertThrows(BizException.class,
                () -> serviceFor(Viewer.anonymous()).detail(999L));
        BizException invisible = assertThrows(BizException.class,
                () -> serviceFor(Viewer.anonymous()).detail(5L));

        assertEquals(absent.getCode(), invisible.getCode());
        assertEquals(absent.getMessage(), invisible.getMessage());
    }

    @Test
    @DisplayName("已发布:返回详情,并且浏览量 +1")
    void detail_shouldIncrementViewCount_whenPublished() {
        when(articleMapper.selectOne(any())).thenReturn(article(3L, 3L, 1, 5));
        stubAttachments();

        ArticleDetailVO vo = serviceFor(Viewer.anonymous()).detail(3L);

        assertEquals(3L, vo.getId());
        assertEquals("正文", vo.getContent());
        assertEquals(6, vo.getViewCount(), "返回给前端的浏览量应当已经含本次");
        verify(articleMapper).incrViewCount(3L);
    }

    @Test
    @DisplayName("草稿(作者本人可见):不递增浏览量 —— 与迁移前一致")
    void detail_shouldNotIncrementViewCount_whenDraft() {
        when(articleMapper.selectOne(any())).thenReturn(article(16L, 3L, 0, 0));
        stubAttachments();

        ArticleDetailVO vo = serviceFor(Viewer.of(3L, false)).detail(16L);

        assertEquals(16L, vo.getId());
        assertEquals(0, vo.getViewCount());
        verify(articleMapper, never()).incrViewCount(any());
    }

    @Test
    @DisplayName("编辑态:不递增浏览量")
    void editDetail_shouldNotTouchViewCount() {
        // 所有权校验走的是 SecurityUtil.requireOwnerOrAdmin,读的是 SecurityContextHolder,
        // 而不是模块的 ViewerSource —— 本测试因此必须两处都设。
        //
        // 这个摩擦本身是个设计信号:「谁在看」目前有两个来源,它们可以不一致。
        // 统一它属于 §4.3(viewer 作为被接受的依赖),本次重构刻意不做,但值得记住。
        authenticate(3L, "USER");
        when(articleMapper.selectById(7L)).thenReturn(article(7L, 3L, 1, 9));
        stubAttachments();

        serviceFor(Viewer.of(3L, false)).editDetail(7L);

        verify(articleMapper, never()).incrViewCount(any());
    }

    @Test
    @DisplayName("管理员:谓词被省略,所以被封禁作者的已发布文章也能打开")
    void detail_shouldReachBannedAuthorsArticle_whenAdmin() {
        // mock 的 mapper 不评估谓词,所以这里断言的是「管理员路径确实调用了查询」,
        // 真正的豁免语义由 ArticleQueryTest 的 SQL 形状断言与真实数据核对负责。
        when(articleMapper.selectOne(any())).thenReturn(article(5L, 6L, 1, 3));
        stubAttachments();

        ArticleDetailVO vo = serviceFor(Viewer.of(3L, true)).detail(5L);

        assertEquals(5L, vo.getId());
        verify(articleMapper).incrViewCount(5L);
    }
}
