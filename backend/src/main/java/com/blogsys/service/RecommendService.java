package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blogsys.common.ArticleStatus;
import com.blogsys.common.BizException;
import com.blogsys.entity.Article;
import com.blogsys.entity.ArticleTag;
import com.blogsys.entity.Tag;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.ArticleTagMapper;
import com.blogsys.mapper.TagMapper;
import com.blogsys.visibility.Visibility;
import com.blogsys.vo.ArticleListItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
    /** 列表项的组装归它 —— 推荐结果与其它列表因此形状一致(含 coverThumb)。 */
    private final ArticleListItems listItems;
    private final Visibility visibility;

    public List<ArticleListItemVO> recommend(Long articleId, int size) {
        // 靶子文章同样穿过可见性。迁移前这里只查了 status、没查作者是否被封禁,
        // 而同一个函数的候选过滤却查了 —— 一个函数对自己用的规则自相矛盾。
        // 又因为本接口是 permitAll,任何人拿着 id 就能确认被封禁作者的文章存在。
        Article target = visibility.articles().require(articleId).article();
        Set<String> targetTags = tagsOf(Set.of(target.getId())).getOrDefault(articleId, Set.of());
        Set<String> targetKeywords = keywords(target.getTitle(), target.getSummary(), target.getContent());

        // 封禁过滤现在发生在 SQL 里、LIMIT 之前;迁移前是先取 100 行再在内存里剔,
        // 于是候选集会被封禁作者的文章占掉一部分。候选因此比过去更实。
        List<Article> candidates = visibility.articles()
                .where(w -> w.ne(Article::getUserId, target.getUserId()))
                .orderByDesc(Article::getCreatedAt)
                .list(CANDIDATE_LIMIT);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, Set<String>> tagsByArticle = tagsOf(
                candidates.stream().map(Article::getId).collect(Collectors.toSet()));

        // 先算分、排序、截到前 N,**再一次性投影**。
        //
        // 迁移前是在这个循环里逐篇 userService.findByIds(...) 取作者:候选最多 100 篇,
        // 于是最坏 100 次查询;而单测里的 userService 是 mock,数不出查询次数,
        // 所以这笔 N+1 从来没有被任何东西发现过。
        Comparator<Scored> byScoreDesc = Comparator.comparingDouble(Scored::score).reversed();
        Comparator<Scored> byViewsDesc = Comparator.comparingInt(
                (Scored scored) -> scored.article().getViewCount() == null
                        ? 0 : scored.article().getViewCount()).reversed();
        List<Scored> top = candidates.stream()
                .map(candidate -> new Scored(candidate, score(targetTags, targetKeywords,
                        tagsByArticle.getOrDefault(candidate.getId(), Set.of()),
                        keywords(candidate.getTitle(), candidate.getSummary(), candidate.getContent()))))
                .filter(scored -> scored.score() > 0)
                .sorted(byScoreDesc.thenComparing(byViewsDesc))
                .limit(Math.min(size, 10))
                .toList();

        // 作者与标签各一次查询,与返回条数无关。封面缩略图也从此一并到位 ——
        // 迁移前这条路径漏掉了 coverThumb,前端用 `coverThumb || cover` 把它掩盖了。
        List<ArticleListItemVO> result = listItems.of(top.stream().map(Scored::article).toList());
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setRecommendScore(top.get(i).score());
        }
        return result;
    }

    /** 候选与它的得分 —— 排序发生在投影之前,于是投影只需处理最终那几篇。 */
    private record Scored(Article article, double score) {
    }

    private double score(Set<String> targetTags, Set<String> targetKeywords,
                         Set<String> tags, Set<String> keywords) {
        int sharedTags = intersectSize(targetTags, tags);
        int sharedKeywords = intersectSize(targetKeywords, keywords);
        return sharedTags * 5.0 + sharedKeywords * 2.0;
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
