package com.blogsys.ai.tool;

import com.blogsys.service.ArticleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.blogsys.vo.ArticleListItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SearchArticlesTool extends AbstractTool {

    private final ArticleService articleService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "searchArticles";
    }

    @Override
    public String description() {
        return "按关键词搜索博客文章列表,返回标题、作者、标签、浏览量与点赞量";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> keyword = new LinkedHashMap<>();
        keyword.put("type", "string");
        keyword.put("description", "搜索关键词,会匹配文章标题与正文");
        schema.put("keyword", keyword);
        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String keyword = arg(args, "keyword", "");
        var page = articleService.page(1, 5, null, keyword);
        ArrayNode articles = objectMapper.createArrayNode();
        for (ArticleListItemVO vo : page.getRecords()) {
            ObjectNode node = compactArticle(objectMapper, vo);
            node.put("summary", firstLine(vo.getSummary()));
            articles.add(node);
        }
        return json(objectMapper, countResult(objectMapper, articles));
    }

    private String firstLine(String summary) {
        if (summary == null) {
            return "";
        }
        int idx = summary.indexOf('\n');
        String line = idx >= 0 ? summary.substring(0, idx) : summary;
        return line.length() > 80 ? line.substring(0, 80) + "…" : line;
    }
}
