package com.blogsys.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.blogsys.entity.Article;
import com.blogsys.entity.ArticleTag;
import com.blogsys.entity.Tag;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleTagMapper;
import com.blogsys.mapper.TagMapper;
import com.blogsys.vo.ArticleDetailVO;
import com.blogsys.vo.ArticleListItemVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 列表项投影的规格。
 *
 * <p>其中的价值不在「字段有没有填上」,而在两条**曾经静默失效**的性质:
 * <ol>
 *   <li><b>查询次数与条数无关。</b>推荐器那份实现是在候选循环里逐篇查作者,
 *       候选最多 100 篇 —— 而单测的 {@code userService} 是 mock,数不出查询次数,
 *       所以这笔 N+1 从来没有被任何东西发现过。这条用例把次数写死成断言。</li>
 *   <li><b>每条都带 coverThumb。</b>推荐器那份实现逐字复制了九个字段却漏了它,
 *       而前端用 {@code coverThumb || cover} 把缺失悄悄降级掩盖了。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ArticleListItemsTest {

    @Mock
    private UserService userService;
    @Mock
    private ArticleTagMapper articleTagMapper;
    @Mock
    private TagMapper tagMapper;

    private ArticleListItems listItems;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(Article.class, User.class, ArticleTag.class, Tag.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        listItems = new ArticleListItems(userService, articleTagMapper, tagMapper);
    }

    private Article article(long id, long authorId, String cover) {
        Article article = new Article();
        article.setId(id);
        article.setUserId(authorId);
        article.setTitle("标题" + id);
        article.setSummary("摘要");
        article.setCover(cover);
        article.setStatus(1);
        article.setViewCount(0);
        article.setLikeCount(0);
        article.setCommentCount(0);
        return article;
    }

    private User user(long id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setNickname(nickname);
        user.setAvatar("/uploads/a" + id + ".jpg");
        return user;
    }

    private Tag tag(long id, String name) {
        Tag tag = new Tag();
        tag.setId(id);
        tag.setName(name);
        return tag;
    }

    private ArticleTag rel(long articleId, long tagId) {
        ArticleTag relation = new ArticleTag();
        relation.setArticleId(articleId);
        relation.setTagId(tagId);
        return relation;
    }

    @Test
    @DisplayName("批量投影:作者与标签各查一次,与条数无关")
    void of_shouldQueryOncePerRelation_regardlessOfHowManyArticles() {
        List<Article> articles = List.of(
                article(1, 11, ""), article(2, 12, ""), article(3, 13, ""),
                article(4, 14, ""), article(5, 15, ""));
        when(userService.findByIds(anyList())).thenReturn(Map.of(
                11L, user(11, "甲"), 12L, user(12, "乙"), 13L, user(13, "丙"),
                14L, user(14, "丁"), 15L, user(15, "戊")));
        when(articleTagMapper.selectList(any())).thenReturn(List.of());

        List<ArticleListItemVO> vos = listItems.of(articles);

        assertEquals(5, vos.size());
        verify(userService, times(1)).findByIds(anyList());
        verify(articleTagMapper, times(1)).selectList(any());
        assertEquals(11L, vos.get(0).getAuthor().getId());
        assertEquals(15L, vos.get(4).getAuthor().getId());
    }

    @Test
    @DisplayName("每一条都带 coverThumb —— 推荐器那份实现漏掉过它")
    void of_shouldSetCoverThumbOnEveryItem() {
        when(userService.findByIds(anyList())).thenReturn(Map.of());
        when(articleTagMapper.selectList(any())).thenReturn(List.of());

        List<ArticleListItemVO> vos = listItems.of(List.of(
                article(1, 11, "/uploads/abc.jpg"),
                article(2, 12, "/uploads/abc.webp"),
                article(3, 13, "")));

        assertEquals("/uploads/abc-thumb.jpg", vos.get(0).getCoverThumb());
        assertEquals("/uploads/abc.webp", vos.get(1).getCoverThumb(), "webp 没有缩略图,退回原图");
        assertEquals("", vos.get(2).getCoverThumb());
    }

    @Test
    @DisplayName("标签按文章分组,顺序取自关联表")
    void of_shouldGroupTagsByArticle() {
        when(userService.findByIds(anyList())).thenReturn(Map.of());
        when(articleTagMapper.selectList(any())).thenReturn(List.of(
                rel(1L, 1L), rel(1L, 2L), rel(2L, 2L)));
        when(tagMapper.selectBatchIds(any())).thenReturn(List.of(tag(1L, "java"), tag(2L, "随笔")));

        List<ArticleListItemVO> vos = listItems.of(List.of(article(1, 11, ""), article(2, 12, "")));

        assertEquals(List.of("java", "随笔"), vos.get(0).getTags());
        assertEquals(List.of("随笔"), vos.get(1).getTags());
    }

    @Test
    @DisplayName("单篇填充:作者与标签一并到位,并把子类原样还回去")
    void fill_shouldPopulateSubclassAndReturnIt() {
        when(userService.findByIds(anyList())).thenReturn(Map.of(11L, user(11, "甲")));
        when(articleTagMapper.selectList(any())).thenReturn(List.of(rel(7L, 1L)));
        when(tagMapper.selectBatchIds(any())).thenReturn(List.of(tag(1L, "java")));

        ArticleDetailVO returned = listItems.fill(article(7, 11, "/uploads/x.png"), new ArticleDetailVO());

        assertEquals(7L, returned.getId());
        assertEquals(11L, returned.getAuthor().getId());
        assertEquals(List.of("java"), returned.getTags());
        assertEquals("/uploads/x-thumb.jpg", returned.getCoverThumb());
    }

    @Test
    @DisplayName("空批次不查库")
    void of_shouldNotQuery_whenThereAreNoArticles() {
        assertEquals(List.of(), listItems.of(List.of()));
        verify(userService, times(0)).findByIds(anyList());
        verify(articleTagMapper, times(0)).selectList(any());
    }

    @Test
    @DisplayName("作者查不到时留 null,而不是造一个空壳")
    void of_shouldLeaveAuthorNull_whenTheAuthorIsGone() {
        when(userService.findByIds(anyList())).thenReturn(Map.of());
        when(articleTagMapper.selectList(any())).thenReturn(List.of());

        List<ArticleListItemVO> vos = listItems.of(List.of(article(1, 11, "")));

        assertSame(null, vos.get(0).getAuthor());
    }
}
