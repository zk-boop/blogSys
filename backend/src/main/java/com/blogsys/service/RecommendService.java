package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blogsys.common.ArticleStatus;
import com.blogsys.common.BizException;
import com.blogsys.entity.Article;
import com.blogsys.entity.ArticleTag;
import com.blogsys.entity.Tag;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.ArticleTagMapper;
import com.blogsys.mapper.TagMapper;
import com.blogsys.vo.ArticleListItemVO;
import com.blogsys.vo.UserBriefVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 基于标签重叠与标题/摘要关键词相似度的内容推荐,不依赖外部 AI 服务。 */
@Service
@RequiredArgsConstructor
public class RecommendService {

    private static final int CANDIDATE_LIMIT = 100;
    private static final int CONTENT_KEYWORD_LIMIT = 500;
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "就", "我", "你", "他", "她", "它",
            "这", "那", "也", "都", "而", "及", "或", "但", "等", "不", "有", "个",
            "对", "上", "中", "下", "a", "an", "the", "of", "to", "in", "for",
            "and", "or", "on", "with", "at", "by");

    private static final Pattern CHINESE_RUN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");
    private static final Pattern ENGLISH_WORD = Pattern.compile("[a-zA-Z]{3,}");

    private final ArticleMapper articleMapper;
    private final ArticleTagMapper articleTagMapper;
    private final TagMapper tagMapper;
    private final UserService userService;

    public List<ArticleListItemVO> recommend(Long articleId, int size) {
        Article target = articleMapper.selectById(articleId);
        if (target == null || target.getStatus() != ArticleStatus.PUBLISHED.getValue()) {
            throw new BizException(404, "文章不存在");
        }
        Set<String> targetTags = tagsOf(Set.of(target.getId())).getOrDefault(articleId, Set.of());
        Set<String> targetKeywords = keywords(target.getTitle(), target.getSummary(), target.getContent());

        List<Article> candidates = articleMapper.selectList(
                Wrappers.<Article>lambdaQuery()
                        .eq(Article::getStatus, ArticleStatus.PUBLISHED.getValue())
                        .ne(Article::getUserId, target.getUserId())
                        .orderByDesc(Article::getCreatedAt)
                        .last("LIMIT " + CANDIDATE_LIMIT));
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<Long> bannedIds = bannedUserIdsOf(candidates);
        Map<Long, Set<String>> tagsByArticle = tagsOf(
                candidates.stream().map(Article::getId).collect(Collectors.toSet()));

        List<ArticleListItemVO> result = new ArrayList<>();
        for (Article candidate : candidates) {
            if (bannedIds.contains(candidate.getUserId())) {
                continue;
            }
            Set<String> tags = tagsByArticle.getOrDefault(candidate.getId(), Set.of());
            Set<String> kw = keywords(candidate.getTitle(), candidate.getSummary(), candidate.getContent());
            double score = score(targetTags, targetKeywords, tags, kw);
            if (score > 0) {
                result.add(build(candidate, tags, score));
            }
        }
        result.sort(Comparator
                .comparingDouble(ArticleListItemVO::getRecommendScore).reversed()
                .thenComparing(ArticleListItemVO::getViewCount, Comparator.reverseOrder()));
        return result.stream().limit(Math.min(size, 10)).toList();
    }

    private double score(Set<String> targetTags, Set<String> targetKeywords,
                         Set<String> tags, Set<String> keywords) {
        int sharedTags = intersectSize(targetTags, tags);
        int sharedKeywords = intersectSize(targetKeywords, keywords);
        return sharedTags * 5.0 + sharedKeywords * 2.0;
    }

    private ArticleListItemVO build(Article article, Set<String> tags, double score) {
        ArticleListItemVO vo = new ArticleListItemVO();
        vo.setId(article.getId());
        vo.setTitle(article.getTitle());
        vo.setSummary(article.getSummary());
        vo.setCover(article.getCover());
        vo.setStatus(article.getStatus());
        vo.setViewCount(article.getViewCount());
        vo.setLikeCount(article.getLikeCount());
        vo.setCommentCount(article.getCommentCount());
        vo.setCreatedAt(article.getCreatedAt());
        vo.setRecommendScore(score);
        User user = userService.findByIds(List.of(article.getUserId())).get(article.getUserId());
        if (user != null) {
            vo.setAuthor(new UserBriefVO(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar()));
        }
        vo.setTags(new ArrayList<>(tags));
        return vo;
    }

    private Set<Long> bannedUserIdsOf(List<Article> articles) {
        List<Long> userIds = articles.stream().map(Article::getUserId).distinct().toList();
        return userService.findByIds(userIds).values().stream()
                .filter(user -> Integer.valueOf(1).equals(user.getStatus()))
                .map(User::getId)
                .collect(Collectors.toSet());
    }

    private Map<Long, Set<String>> tagsOf(Set<Long> articleIds) {
        if (articleIds.isEmpty()) {
            return Map.of();
        }
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
                Collectors.mapping(at -> tagNames.get(at.getTagId()), Collectors.toCollection(LinkedHashSet::new))));
    }

    /** 提取标题/摘要/正文(截断)中的关键词:中文双字片段 + 英文单词,过滤停用词。 */
    static Set<String> keywords(String title, String summary, String content) {
        Set<String> keywords = new LinkedHashSet<>();
        String body = content == null ? "" : content;
        if (body.length() > CONTENT_KEYWORD_LIMIT) {
            body = body.substring(0, CONTENT_KEYWORD_LIMIT);
        }
        String text = (title == null ? "" : title) + " "
                + (summary == null ? "" : summary) + " " + body;
        Matcher chinese = CHINESE_RUN.matcher(text);
        while (chinese.find()) {
            String run = chinese.group();
            for (int i = 0; i + 1 < run.length(); i++) {
                String gram = run.substring(i, i + 2);
                if (!STOP_WORDS.contains(gram)) {
                    keywords.add(gram);
                }
            }
        }
        Matcher english = ENGLISH_WORD.matcher(text.toLowerCase());
        while (english.find()) {
            String word = english.group();
            if (!STOP_WORDS.contains(word)) {
                keywords.add(word);
            }
        }
        return keywords;
    }

    private int intersectSize(Set<String> a, Set<String> b) {
        int count = 0;
        for (String item : a) {
            if (b.contains(item)) {
                count++;
            }
        }
        return count;
    }
}
