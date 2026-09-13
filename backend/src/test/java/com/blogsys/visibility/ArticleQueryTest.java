package com.blogsys.visibility;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsys.common.BizException;
import com.blogsys.entity.Article;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 按 viewer 种类断言实际发出的谓词形状。
 *
 * <p>判断标准只有两条:有没有出现封禁子查询、有没有出现那个被括号包住的 OR 组。
 * 不能用「有没有绑定参数」当代理 —— MyBatis-Plus 连 {@code eq} 的值也会绑定。
 */
@ExtendWith(MockitoExtension.class)
class ArticleQueryTest {

    private static final Pattern PARENTHESIZED_OR_GROUP =
            Pattern.compile("\\(\\s*status = #\\{[^}]+} OR user_id = #\\{[^}]+}\\s*\\)");

    @Mock
    private ArticleMapper articleMapper;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Article.class);
        TableInfoHelper.initTableInfo(assistant, User.class);
    }

    private Visibility visibilityFor(Viewer viewer) {
        return new DefaultVisibility(articleMapper, ViewerSource.fixed(viewer));
    }

    /**
     * 跑一次 list(),把真正交给 mapper 的 wrapper 抓回来渲染成 SQL。
     *
     * <p>用 {@link java.util.function.UnaryOperator} 而不是 {@code Consumer}:查询对象是不可变的,
     * {@code where} / {@code includingOwnDrafts} 返回新实例 —— 用 Consumer 会把返回值丢掉,
     * 于是配置从未生效。第一次写这个辅助方法就踩了这个坑。
     */
    private String sqlFor(Viewer viewer, java.util.function.UnaryOperator<ArticleQuery> configure) {
        when(articleMapper.selectList(any())).thenReturn(List.of());
        ArticleQuery query = configure.apply(visibilityFor(viewer).articles());
        query.list(10);

        ArgumentCaptor<Wrapper<Article>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(articleMapper).selectList(captor.capture());
        return captor.getValue().getSqlSegment();
    }

    private String anonymousSql() {
        return sqlFor(Viewer.anonymous(), q -> q);
    }

    /* ---------- 谓词形状 ---------- */

    @Test
    @DisplayName("匿名:只要已发布 + 作者未封禁")
    void anonymous_shouldRequirePublishedAndActiveAuthor() {
        String sql = anonymousSql();

        assertTrue(sql.contains("status = #{"), "应限定已发布,实际: " + sql);
        assertTrue(sql.contains("FROM users WHERE status = #{"), "应排除被封禁作者,实际: " + sql);
        assertFalse(PARENTHESIZED_OR_GROUP.matcher(sql).find(), "匿名没有草稿可纳入,实际: " + sql);
    }

    @Test
    @DisplayName("管理员:完全省略谓词,只留下调用方自己的条件")
    void admin_shouldHaveNoPredicateAtAll() {
        String sql = sqlFor(Viewer.of(3L, true), q -> q.where(w -> w.eq(Article::getTitle, "x")));

        assertFalse(sql.contains("FROM users"), "管理员路径不该有封禁子查询,实际: " + sql);
        assertFalse(sql.contains("status = #{"), "管理员路径不该有发布状态条件,实际: " + sql);
        assertFalse(PARENTHESIZED_OR_GROUP.matcher(sql).find(), "管理员没有草稿分支,实际: " + sql);
        assertTrue(sql.contains("title = #{"), "调用方自己的条件要留着,实际: " + sql);
    }

    @Test
    @DisplayName("登录者默认仍看不到自己的草稿 —— 公开语料就是公开语料")
    void member_withoutOptIn_shouldNotSeeOwnDrafts() {
        String sql = sqlFor(Viewer.of(7L, false), q -> q);

        assertFalse(PARENTHESIZED_OR_GROUP.matcher(sql).find(),
                "没登记 includingOwnDrafts 就不该出现草稿分支,实际: " + sql);
    }

    @Test
    @DisplayName("包括自己的草稿:出现被括号包住的 OR 组")
    void member_withOwnDrafts_shouldHaveParenthesizedOrGroup() {
        String sql = sqlFor(Viewer.of(7L, false), ArticleQuery::includingOwnDrafts);

        assertTrue(PARENTHESIZED_OR_GROUP.matcher(sql).find(),
                "应出现 (已发布 OR 是我的) 且被括号整体包住,实际: " + sql);
        assertTrue(sql.contains("FROM users"), "封禁条件仍然要在,实际: " + sql);
    }

    @Test
    @DisplayName("匿名即便登记了 includingOwnDrafts 也退化成公开语料,而不是出错")
    void anonymous_withOwnDraftsOptIn_shouldDegradeToPublic() {
        String sql = sqlFor(Viewer.anonymous(), ArticleQuery::includingOwnDrafts);

        assertFalse(PARENTHESIZED_OR_GROUP.matcher(sql).find(), "匿名没有草稿,实际: " + sql);
        assertTrue(sql.contains("FROM users"), "实际: " + sql);
    }

    /* ---------- 调用方无法改写谓词 ---------- */

    @Test
    @DisplayName("调用方的同级 or() 混不进谓词:两个条件各自被括号包住")
    void callerRestriction_cannotWidenThePredicate() {
        String sql = sqlFor(Viewer.of(7L, false), q -> q
                .includingOwnDrafts()
                .where(w -> w.eq(Article::getTitle, "a").or().eq(Article::getSummary, "b")));

        assertTrue(PARENTHESIZED_OR_GROUP.matcher(sql).find(),
                "谓词自己的 OR 组必须完整,实际: " + sql);
        assertTrue(Pattern.compile("\\(\\s*title = #\\{[^}]+} OR summary = #\\{[^}]+}\\s*\\)")
                        .matcher(sql).find(),
                "调用方自己的 OR 组也应当被单独包住,实际: " + sql);
    }

    @Test
    @DisplayName("排序直接作用在外层,不能被包成 AND (ORDER BY ...) —— 那是非法 SQL")
    void ordering_shouldNotBeNestedIntoThePredicate() {
        String sql = sqlFor(Viewer.of(7L, false), q -> q.orderByDesc(Article::getCreatedAt));

        assertTrue(sql.contains("ORDER BY"), "应含排序,实际: " + sql);
        assertFalse(sql.contains("(ORDER BY"), "排序不该被括号包住,实际: " + sql);
        assertFalse(sql.contains("AND ORDER BY"), "排序不该被 AND 连进谓词,实际: " + sql);
    }

    /* ---------- 绑定的值 ---------- */

    @Test
    @DisplayName("封禁条件用绑定参数,值不进 SQL 文本")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void banPredicate_shouldBindTheActiveStatus() {
        when(articleMapper.selectList(any())).thenReturn(List.of());
        visibilityFor(Viewer.anonymous()).articles().list(10);

        // 参数表在 AbstractWrapper 上,不在 Wrapper 接口上
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.AbstractWrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.AbstractWrapper.class);
        verify(articleMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        Map<String, Object> params = captor.getValue().getParamNameValuePairs();

        assertTrue(sql.contains("MPGENVAL"), "应渲染成绑定参数,实际: " + sql);
        assertFalse(Pattern.compile("status\\s*=\\s*0").matcher(sql).find(),
                "ACTIVE 的值不该出现在 SQL 文本里,实际: " + sql);
        assertTrue(params.containsValue(0), "参数表里应有 ACTIVE=0,实际: " + params);
    }

    /* ---------- 终态语义 ---------- */

    @Test
    @DisplayName("require 不可见时抛 404,消息与「不存在」完全一致")
    void require_shouldThrowCanonical404_whenNotVisible() {
        when(articleMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> visibilityFor(Viewer.anonymous()).articles().require(5L));

        assertEquals(404, e.getCode());
        assertEquals("文章不存在", e.getMessage());
    }

    @Test
    void require_shouldReturnToken_whenVisible() {
        Article article = new Article();
        article.setId(5L);
        when(articleMapper.selectOne(any())).thenReturn(article);

        VisibleArticle visible = visibilityFor(Viewer.anonymous()).articles().require(5L);

        assertEquals(5L, visible.id());
        assertSame(article, visible.article());
    }

    @Test
    @DisplayName("分页的 total 与 records 出自同一个 wrapper,不可能再对不上")
    void page_shouldReturnWhateverTheMapperProduced() {
        Page<Article> expected = new Page<>(1, 10);
        expected.setTotal(42);
        when(articleMapper.selectPage(any(), any())).thenReturn(expected);

        Page<Article> actual = visibilityFor(Viewer.anonymous()).articles().page(1, 10);

        assertSame(expected, actual);
        ArgumentCaptor<Wrapper<Article>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(articleMapper).selectPage(any(), captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("FROM users"),
                "分页查询同样带着谓词,实际: " + captor.getValue().getSqlSegment());
    }

    @Test
    void as_shouldRebindTheViewer() {
        when(articleMapper.selectList(any())).thenReturn(List.of());

        Visibility rebindable = new DefaultVisibility(articleMapper, ViewerSource.fixed(Viewer.anonymous()));
        rebindable.as(Viewer.of(3L, true)).articles().list(5);

        ArgumentCaptor<Wrapper<Article>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(articleMapper).selectList(captor.capture());
        assertFalse(captor.getValue().getSqlSegment().contains("FROM users"),
                "绑定成管理员后不该再有谓词,实际: " + captor.getValue().getSqlSegment());
    }
}
