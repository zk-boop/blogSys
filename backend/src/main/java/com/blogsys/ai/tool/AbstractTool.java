package com.blogsys.ai.tool;

import com.blogsys.common.BizException;
import com.blogsys.vo.ArticleListItemVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

public abstract class AbstractTool implements AgentTool {

    /**
     * 序列化工具结果。这是所有工具结果的**唯一出口**,所以两件事都归它:
     *
     * <p><b>大小。</b>超过 {@link ResultBudget#LIMIT} 就按结构裁 —— 整条丢弃数组尾部,
     * 重新序列化,永远不切开任何一个 JSON token(见 {@link ResultBudget} 的类注释)。
     *
     * <p><b>失败。</b>抛出,不再返回 {@code {"error":"结果序列化失败"}} 那种假成功:
     * 那个信封与一个正常的工具结果长得一模一样,调用者分不出「工具坏了」和「工具跑完了」。
     * 抛出去之后走 {@code ChatService.executeTool} 的统一失败路径:记一条 WARN,
     * 并给模型一个错误信封 —— 模型照样知道发生了什么,但日志里不再是一片安静。
     */
    protected static String json(ObjectMapper objectMapper, Object value) {
        return ResultBudget.encode(objectMapper, value);
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
