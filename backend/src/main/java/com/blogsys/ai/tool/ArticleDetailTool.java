package com.blogsys.ai.tool;

import com.blogsys.service.ArticleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.blogsys.vo.ArticleDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ArticleDetailTool extends AbstractTool {

    private static final int CONTENT_LIMIT = 2000;

    private final ArticleService articleService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "getArticleDetail";
    }

    @Override
    public String description() {
        return "按文章 ID 获取单篇文章详情,含正文内容(截断)、标签、作者与互动数据";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("type", "integer");
        id.put("description", "文章 ID");
        schema.put("id", id);
        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) {
        long id = idOf(args);
        ArticleDetailVO vo = articleService.detail(id);
        ObjectNode node = compactArticle(objectMapper, vo);
        node.put("summary", vo.getSummary());
        node.put("commentCount", vo.getCommentCount());
        String content = vo.getContent() == null ? "" : vo.getContent();
        node.put("content", content.length() > CONTENT_LIMIT
                ? content.substring(0, CONTENT_LIMIT) + "…(已截断)" : content);
        return json(objectMapper, node);
    }

    private long idOf(Map<String, Object> args) {
        Object raw = args.get("id");
        return raw instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(raw));
    }
}
