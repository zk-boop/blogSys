package com.blogsys.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * 与「不存在」完全相同的 404;以及<b>「读详情」里没有任何写、「记浏览」才是唯一的写入点</b>。
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
        // 一个 ViewerSource 同时喂给可见性模块与 ArticleService —— 于是「这篇文章可见吗」
        // 与「我收藏过吗」不可能来自两个不同的人。在此之前前者读 ViewerSource、
        // 后者读 SecurityContextHolder,而这个测试想造一个「已收藏」的 viewer 时
        // 只能去改线程局部变量(见 docs/lessons.md 第 26 条)。
        ViewerSource viewerSource = ViewerSource.fixed(viewer);
        Visibility visibility = new DefaultVisibility(articleMapper, commentMapper, viewerSource);
        return new ArticleService(articleMapper, tagMapper, articleTagMapper, commentMapper,
                likeMapper, favoriteMapper, new ArticleListItems(userService, articleTagMapper, tagMapper),
                visibility, viewerSource);
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
    @DisplayName("已发布:返回详情,且不写库 —— 读操作没有任何副作用")
    void detail_shouldBeAPureRead_whenPublished() {
        when(articleMapper.selectOne(any())).thenReturn(article(3L, 3L, 1, 5));
        stubAttachments();

        ArticleDetailVO vo = serviceFor(Viewer.anonymous()).detail(3L);

        assertEquals(3L, vo.getId());
        assertEquals("正文", vo.getContent());
        assertEquals(5, vo.getViewCount(), "detail 不再自己 +1 —— 记浏览是调用方显式做的事");
        // D2:就是这一条被 AI 工具路径踩中 —— 每查一次详情,那篇文章就被加热一次,
        // 而浏览量正是 hot() 的排序依据。
        verify(articleMapper, never()).incrViewCount(any());
    }

    @Test
    @DisplayName("草稿(作者本人可见):同样不写库")
    void detail_shouldBeAPureRead_whenDraft() {
        when(articleMapper.selectOne(any())).thenReturn(article(16L, 3L, 0, 0));
        stubAttachments();

        ArticleDetailVO vo = serviceFor(Viewer.of(3L, false)).detail(16L);

        assertEquals(16L, vo.getId());
        assertEquals(0, vo.getViewCount());
        verify(articleMapper, never()).incrViewCount(any());
    }

    @Test
    @DisplayName("记浏览:已发布才 +1")
    void recordView_shouldIncrement_whenPublished() {
        when(articleMapper.selectOne(any())).thenReturn(article(3L, 3L, 1, 5));

        serviceFor(Viewer.anonymous()).recordView(3L);

        verify(articleMapper).incrViewCount(3L);
    }

    @Test
    @DisplayName("记浏览:草稿不计 —— 与拆分前一致")
    void recordView_shouldNotIncrement_whenDraft() {
        when(articleMapper.selectOne(any())).thenReturn(article(16L, 3L, 0, 0));

        serviceFor(Viewer.of(3L, false)).recordView(16L);

        verify(articleMapper, never()).incrViewCount(any());
    }

    @Test
    @DisplayName("记浏览:不可见与不存在抛同一个 404,且不落任何写")
    void recordView_shouldThrowCanonical404_whenNotVisible() {
        when(articleMapper.selectOne(any())).thenReturn(null);

        BizException absent = assertThrows(BizException.class,
                () -> serviceFor(Viewer.anonymous()).recordView(999L));
        BizException invisible = assertThrows(BizException.class,
                () -> serviceFor(Viewer.anonymous()).recordView(5L));

        assertEquals(404, absent.getCode());
        assertEquals(absent.getCode(), invisible.getCode());
        assertEquals(absent.getMessage(), invisible.getMessage());
        verify(articleMapper, never()).incrViewCount(any());
    }

    @Test
    @DisplayName("编辑态:不递增浏览量")
    void editDetail_shouldNotTouchViewCount() {
        // 所有权校验走的是 SecurityUtil.requireOwnerOrAdmin,读的是 SecurityContextHolder ——
        // 那是**写侧**的「你是不是这个动作的施动者」,与读侧的 viewer 是两件事,
        // 所以本测试仍然两处都要设。
        //
        // 读侧在 C1 之前也是这样(两处身份来源),现在统一到 ViewerSource 了;
        // 写侧保持 SecurityUtil —— 那里「没有登录」确实是个错误,不是一个正常取值。
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
        verify(articleMapper, never()).incrViewCount(any());
    }

    // ---------- D12:同一个搜索框,前台与后台必须同一套规则 ----------

    @Test
    @DisplayName("公开搜索转义 LIKE 通配符 —— 搜「%」不该命中全部内容")
    void page_shouldEscapeLikeWildcards() {
        when(articleMapper.selectPage(any(), any())).thenReturn(new Page<>());

        serviceFor(Viewer.of(1L, true)).page(1, 10, null, "100%_x");

        // MyBatis-Plus 的 like 会自行在两端补 %,所以绑定值形如 %100\%\_x%
        assertTrue(boundParams().contains("%100\\%\\_x%"),
                "绑定的应当是转义后的模式串,实际: " + boundParams());
    }

    @Test
    @DisplayName("后台搜索与公开搜索绑定同一个模式串 —— 此前只有前者转义了(D12)")
    void adminPage_shouldEscapeLikeWildcards_likeThePublicSearch() {
        when(articleMapper.selectPage(any(), any())).thenReturn(new Page<>());

        serviceFor(Viewer.of(1L, true)).adminPage(1, 10, "100%_x", null, null);

        assertTrue(boundParams().contains("%100\\%\\_x%"),
                "后台与公开搜索必须给出同一个模式串,实际: " + boundParams());
    }

    // ---------- C1:viewer 是被接受的依赖,不是环境状态 ----------

    @Test
    @DisplayName("收藏与点赞来自被接受的 viewer,不必再伪造登录")
    void detail_shouldReadInteractionFlags_fromTheViewerItWasGiven() {
        when(articleMapper.selectOne(any())).thenReturn(article(3L, 3L, 1, 5));
        stubAttachments();
        when(favoriteMapper.selectCount(any())).thenReturn(1L);
        when(likeMapper.selectCount(any())).thenReturn(1L);

        ArticleDetailVO vo = serviceFor(Viewer.of(7L, false)).detail(3L);

        assertTrue(vo.isFavorited(), "viewer 收藏过就该是 true");
        assertTrue(vo.isLiked(), "viewer 点过赞就该是 true");
        // 文章 id 是 3L、viewer 是 7L —— 绑定的参数里出现 7L,就证明确实问的是这个人
        assertTrue(favoriteQueryBound(7L), "收藏查询该带 viewer 的 id");
        assertTrue(likeQueryBound(7L), "点赞查询该带 viewer 的 id");
    }

    @Test
    @DisplayName("匿名 viewer 不查收藏与点赞 —— 这是一条普通路径,不再靠 catch 一个 401 异常")
    void detail_shouldNotQueryInteractions_forAnonymous() {
        when(articleMapper.selectOne(any())).thenReturn(article(3L, 3L, 1, 5));
        stubAttachments();

        ArticleDetailVO vo = serviceFor(Viewer.anonymous()).detail(3L);

        assertFalse(vo.isFavorited());
        assertFalse(vo.isLiked());
        verify(favoriteMapper, never()).selectCount(any());
        verify(likeMapper, never()).selectCount(any());
    }

    /** 收藏查询绑定的参数里有没有这个值。参数表是惰性填充的,所以必须先渲染一次。 */
    private boolean favoriteQueryBound(Long value) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Favorite>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(favoriteMapper).selectCount(captor.capture());
        AbstractWrapper<Favorite, ?, ?> wrapper = (AbstractWrapper<Favorite, ?, ?>) captor.getValue();
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().containsValue(value);
    }

    private boolean likeQueryBound(Long value) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Like>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(likeMapper).selectCount(captor.capture());
        AbstractWrapper<Like, ?, ?> wrapper = (AbstractWrapper<Like, ?, ?>) captor.getValue();
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().containsValue(value);
    }

    /** 取出实际绑定到 SQL 的参数值。参数表是惰性填充的,所以必须先渲染一次。 */
    private Collection<Object> boundParams() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Article>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(articleMapper).selectPage(any(), captor.capture());
        // getSqlSegment / getParamNameValuePairs 在 AbstractWrapper 上,不在 Wrapper 上
        AbstractWrapper<Article, ?, ?> wrapper = (AbstractWrapper<Article, ?, ?>) captor.getValue();
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().values();
    }
}
