package com.blogsys.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.blogsys.entity.Article;
import com.blogsys.entity.ArticleTag;
import com.blogsys.entity.Comment;
import com.blogsys.entity.Tag;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.ArticleTagMapper;
import com.blogsys.mapper.CommentMapper;
import com.blogsys.mapper.TagMapper;
import com.blogsys.visibility.DefaultVisibility;
import com.blogsys.visibility.Viewer;
import com.blogsys.visibility.ViewerSource;
import com.blogsys.visibility.Visibility;
import com.blogsys.vo.TagVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标签计数。
 *
 * <p>迁移前这里是 {@code articleTagMapper.selectList(null)} —— 把草稿与封禁作者的文章
 * 都算进计数,于是标签云报 1、点进去却空。缺陷 D5。
 */
@ExtendWith(MockitoExtension.class)
class TagServiceTest {

    @Mock
    private TagMapper tagMapper;
    @Mock
    private ArticleTagMapper articleTagMapper;
    @Mock
    private ArticleMapper articleMapper;
    @Mock
    private CommentMapper commentMapper;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(Article.class, User.class, ArticleTag.class, Tag.class, Comment.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    private TagService serviceFor(Viewer viewer) {
        Visibility visibility = new DefaultVisibility(articleMapper, commentMapper, ViewerSource.fixed(viewer));
        return new TagService(tagMapper, articleTagMapper, visibility);
    }

    private static Tag tag(long id, String name) {
        Tag tag = new Tag();
        tag.setId(id);
        tag.setName(name);
        return tag;
    }

    private static ArticleTag rel(long articleId, long tagId) {
        ArticleTag articleTag = new ArticleTag();
        articleTag.setArticleId(articleId);
        articleTag.setTagId(tagId);
        return articleTag;
    }

    @Test
    @DisplayName("计数查询带上可见性限制 —— 与列表用同一套规则,计数与点进去的结果一致")
    void listAll_shouldRestrictTheCountQuery() {
        when(tagMapper.selectList(any())).thenReturn(List.of(tag(3L, "#夏日")));
        when(articleTagMapper.selectList(any())).thenReturn(List.of(rel(3L, 3L), rel(16L, 3L)));

        List<TagVO> tags = serviceFor(Viewer.anonymous()).listAll();

        ArgumentCaptor<Wrapper<ArticleTag>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(articleTagMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();

        assertTrue(sql.contains("EXISTS (SELECT 1 FROM articles a WHERE a.id = article_id"),
                "计数必须收窄到可见文章,实际: " + sql);
        assertTrue(sql.contains("a.status = #{"), "已发布要限定,实际: " + sql);
        assertTrue(sql.contains("FROM users WHERE status = #{"), "封禁作者要排除,实际: " + sql);
        assertEquals(2L, tags.get(0).getCount(), "计数本身仍由 mapper 返回的行数决定");
    }

    @Test
    @DisplayName("管理员:不加任何限制,拿到的是原始计数")
    void listAll_shouldNotRestrict_forAdmin() {
        when(tagMapper.selectList(any())).thenReturn(List.of(tag(3L, "#夏日")));
        when(articleTagMapper.selectList(any())).thenReturn(List.of(rel(3L, 3L)));

        serviceFor(Viewer.of(3L, true)).listAll();

        ArgumentCaptor<Wrapper<ArticleTag>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(articleTagMapper).selectList(captor.capture());
        assertFalse(captor.getValue().getSqlSegment().contains("EXISTS"),
                "管理员不该被收窄,实际: " + captor.getValue().getSqlSegment());
    }

    @Test
    @DisplayName("没有标签时直接返回,不去查计数")
    void listAll_shouldShortCircuit_whenNoTags() {
        when(tagMapper.selectList(any())).thenReturn(List.of());

        List<TagVO> tags = serviceFor(Viewer.anonymous()).listAll();

        assertTrue(tags.isEmpty());
        verify(articleTagMapper, never()).selectList(any());
    }
}
