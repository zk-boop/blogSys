package com.blogsys.ai.tool;

import com.blogsys.vo.ArticleListItemVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

public abstract class AbstractTool implements AgentTool {

    protected static String json(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"结果序列化失败\"}";
        }
    }

    protected static String arg(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** 文章列表项 → 紧凑 JSON(供 LLM 阅读)。 */
    protected static ObjectNode compactArticle(ObjectMapper objectMapper, ArticleListItemVO vo) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", vo.getId());
        node.put("title", vo.getTitle());
        if (vo.getAuthor() != null) {
            node.put("author", vo.getAuthor().getNickname() == null
                    ? vo.getAuthor().getUsername() : vo.getAuthor().getNickname());
        }
        if (vo.getTags() != null) {
            node.put("tags", String.join(",", vo.getTags()));
        }
        node.put("viewCount", vo.getViewCount());
        node.put("likeCount", vo.getLikeCount());
        return node;
    }

    /** {count, articles} 包装。 */
    protected static ObjectNode countResult(ObjectMapper objectMapper, ArrayNode articles) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("count", articles.size());
        node.set("articles", articles);
        return node;
    }
}
