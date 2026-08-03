package com.blogsys.ai.tool;

import com.blogsys.service.ArticleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.blogsys.vo.ArticleListItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class HotArticlesTool extends AbstractTool {

    private final ArticleService articleService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "getHotArticles";
    }

    @Override
    public String description() {
        return "获取站内热门文章(按浏览量排序,最多 5 篇)";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String execute(Map<String, Object> args) {
        ArrayNode articles = objectMapper.createArrayNode();
        for (ArticleListItemVO vo : articleService.hot(5)) {
            articles.add(compactArticle(objectMapper, vo));
        }
        return json(objectMapper, countResult(objectMapper, articles));
    }
}
