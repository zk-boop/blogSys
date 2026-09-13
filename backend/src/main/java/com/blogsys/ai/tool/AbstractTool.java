package com.blogsys.ai.tool;

import com.blogsys.common.BizException;
import com.blogsys.vo.ArticleListItemVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractTool implements AgentTool {

    /**
     * 序列化工具结果。
     *
     * <p>失败时返回一个错误信封,而不是抛出 —— 工具契约是「返回一段给模型看的 JSON 文本」,
     * 所以这里必须给出点什么。
     *
     * <p>但**至少留下痕迹**:这段 catch 此前是个黑洞 —— 它返回的
     * {@code {"error":"结果序列化失败"}} 与一个正常的工具结果长得一样,调用者(以及读日志的人)
     * 无法察觉「这个工具其实没跑完」。失败被降级成一个正常的返回值,而没有任何记录。
     */
    protected static String json(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("工具结果序列化失败: {}", value == null ? "null" : value.getClass().getName(), e);
            return "{\"error\":\"结果序列化失败\"}";
        }
    }

    protected static String arg(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * 取一个必填的整数参数。
     *
     * <p>这段强转此前被复制了三次(两个工具各有一份私有 `idOf`,一个内联同一逻辑)。
     * 但比重复更要紧的是**失败时说的话**:以前模型漏传 id 时抛的是
     * {@code Long.parseLong("null")} 的 Java 异常(`For input string: "null"`),
     * 而那句话会原样进到错误信封里交给模型。模型看不懂它,也没法据此改正。
     *
     * <p>现在给一句模型能理解、也能转述给用户的话。
     */
    protected static long requiredLong(Map<String, Object> args, String key) {
        Object raw = args.get(key);
        if (raw == null) {
            throw new BizException(key + " 是必填参数");
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new BizException(key + " 需要是一个整数,收到: " + raw);
        }
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
