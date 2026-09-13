package com.blogsys.ai.tool;

import com.blogsys.service.RecommendService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.blogsys.vo.ArticleListItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RecommendArticlesTool extends AbstractTool {

    private final RecommendService recommendService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "recommendArticles";
    }

    @Override
    public String description() {
        return "按文章 ID 获取相关推荐(基于标签与关键词相似度),返回推荐文章列表";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> articleId = new LinkedHashMap<>();
        articleId.put("type", "integer");
        articleId.put("description", "文章 ID");
        schema.put("articleId", articleId);
        return schema;
    }

    @Override
    public List<String> requiredParameters() {
        return List.of("articleId");
    }

    @Override
    public String execute(Map<String, Object> args) {
        long articleId = requiredLong(args, "articleId");
        ArrayNode articles = objectMapper.createArrayNode();
        for (ArticleListItemVO vo : recommendService.recommend(articleId, 5)) {
            articles.add(compactArticle(objectMapper, vo));
        }
        return json(objectMapper, countResult(objectMapper, articles));
    }
}
