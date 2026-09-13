package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blogsys.entity.Article;
import com.blogsys.entity.ArticleTag;
import com.blogsys.entity.Tag;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleTagMapper;
import com.blogsys.mapper.TagMapper;
import com.blogsys.vo.ArticleListItemVO;
import com.blogsys.vo.UserBriefVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 文章 → 列表项的**唯一投影**:基础字段、作者、标签、缩略图。
 *
 * <p>此前这份组装被写了两次 —— {@code ArticleService} 的
 * {@code copyBase} + {@code attachUserAndTags},与 {@code RecommendService} 的
 * {@code build}。第二份逐字复制了九个字段,却**漏掉了 {@code coverThumb}**,
 * 并把批量解析换成了在候选循环里逐个查作者(候选最多 100 篇)。
 *
 * <p>这份漂移之所以一直没被发现,是因为三件事同时成立:前端用
 * {@code article.coverThumb || article.cover} 把它悄悄降级掩盖了;单测里的
 * {@code userService} 是 mock,数不出查询次数;而两份实现在各自的测试里都「通过」。
 *
 * <p>现在两条路径都穿过这里。新增字段自动到达每个列表,N+1 在 module 内部消失,
 * 而漂移不可能再静默发生 —— 因为已经只剩一份实现。
 */
@Service
@RequiredArgsConstructor
public class ArticleListItems {

    private final UserService userService;
    private final ArticleTagMapper articleTagMapper;
    private final TagMapper tagMapper;

    /**
     * 批量投影。作者与标签<b>各查一次,与条数无关</b> —— 这就是「列表」在这里的全部含义。
     */
    public List<ArticleListItemVO> of(List<Article> articles) {
        if (articles.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, User> users = userService.findByIds(articles.stream()
                .map(Article::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        Map<Long, List<String>> tagsByArticle = tagNamesByArticle(
                articles.stream().map(Article::getId).toList());
        return articles.stream().map(article -> {
            ArticleListItemVO vo = base(article, new ArticleListItemVO());
            User user = users.get(article.getUserId());
            if (user != null) {
                vo.setAuthor(briefOf(user));
            }
            vo.setTags(tagsByArticle.getOrDefault(article.getId(), List.of()));
            return vo;
        }).toList();
    }

    /**
     * 单篇:填充一个<b>已存在</b>的 VO —— 详情 VO 是它的子类,所以这里用泛型把它还回去。
     */
    public <T extends ArticleListItemVO> T fill(Article article, T vo) {
        base(article, vo);
        User user = userService.findByIds(List.of(article.getUserId())).get(article.getUserId());
        if (user != null) {
            vo.setAuthor(briefOf(user));
        }
        vo.setTags(tagNamesByArticle(List.of(article.getId())).getOrDefault(article.getId(), List.of()));
        return vo;
    }

    /** 只有标量字段 —— 作者与标签由调用方决定是批量取还是单篇取。 */
    private <T extends ArticleListItemVO> T base(Article article, T vo) {
        vo.setId(article.getId());
        vo.setTitle(article.getTitle());
        vo.setSummary(article.getSummary());
        vo.setCover(article.getCover());
        vo.setCoverThumb(coverThumb(article.getCover()));
        vo.setStatus(article.getStatus());
        vo.setViewCount(article.getViewCount());
        vo.setLikeCount(article.getLikeCount());
        vo.setCommentCount(article.getCommentCount());
        vo.setCreatedAt(article.getCreatedAt());
        return vo;
    }

    private UserBriefVO briefOf(User user) {
        return new UserBriefVO(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar());
    }

    private Map<Long, List<String>> tagNamesByArticle(List<Long> articleIds) {
        List<ArticleTag> relations = articleTagMapper.selectList(
                Wrappers.<ArticleTag>lambdaQuery().in(ArticleTag::getArticleId, articleIds));
        if (relations.isEmpty()) {
            return Map.of();
        }
        List<Long> tagIds = relations.stream().map(ArticleTag::getTagId).distinct().toList();
        Map<Long, String> tagNames = tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, Tag::getName));
        return relations.stream().collect(Collectors.groupingBy(
                ArticleTag::getArticleId,
                Collectors.mapping(at -> tagNames.get(at.getTagId()), Collectors.toList())));
    }

    /**
     * 封面缩略图的命名约定:上传侧生成 {@code base + "-thumb.jpg"}。
     *
     * <p>它对 jpg/png 之外的原图(以及 {@code .webp})不成立,那时返回原图 ——
     * 「退化到原图」是安全的,猜一个不存在的文件名才会 404。
     */
    private String coverThumb(String cover) {
        if (!StringUtils.hasText(cover) || !cover.startsWith("/uploads/") || cover.endsWith(".webp")) {
            return cover;
        }
        return cover.replaceAll("\\.\\w+$", "-thumb.jpg");
    }
}
