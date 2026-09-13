package com.blogsys.ai.tool;

import com.blogsys.common.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Map;

/**
 * 工具结果的预算:交给模型的一段 JSON 文本,上限 {@link #LIMIT} 个字符。
 *
 * <p><b>砍的对象是结构,不是文本。</b>此前这条预算长在 {@code ChatService} 里,而且是
 * {@code result.substring(0, 4000)} —— 在**序列化之后**的文本上切。切在字符串里就切出半段
 * JSON,而那段「JSON」会作为工具结果原样回到模型那里:模型读到的不是数据,是语法错误,
 * 而它会拿这段语法错误继续往下推理。与 §24.1 第 3 项(错误信封靠拼接)是同一类问题,
 * 只是这一处更隐蔽 —— 它只在结果真的很大时才发作,平时永远是好的。
 *
 * <p>所以这里整条丢弃:丢掉数组尾部的一个条目、重新序列化、再看还超不超。出去的每一段
 * 都是合法 JSON,因为**从来没有哪一步切开过一个 token**。丢掉条目时 {@code count} 跟着改
 * —— 否则模型会照着 {@code count: 5} 说出一个只有 3 条的数据。
 *
 * <p>只看根节点这一层。工具的结果形状就是「根上的数组 + 根上的短文本」这两种;为不存在的
 * 嵌套结构写一个递归下降,只会得到一段没人敢改的代码。
 */
@Slf4j
final class ResultBudget {

    /** 一段工具结果的上限(字符)。 */
    static final int LIMIT = 4000;

    private static final String TRUNCATED = "truncated";
    private static final String CUT_MARK = "…(已截断)";

    private ResultBudget() {
    }

    /**
     * 序列化成一段给模型看的 JSON 文本。
     *
     * <p><b>失败时抛出,不再返回一个假结果。</b>此前这里返回 {@code {"error":"结果序列化失败"}},
     * 而它与一个正常的工具结果长得一模一样 —— 调用者分不出「工具跑完了但是空的」和
     * 「工具坏了」。工具契约本来就是「返回一段 JSON 文本」,给不出就是给不出。
     * 抛出去之后走 {@code ChatService.executeTool} 那条统一失败路径:记一条 WARN,
     * 并给模型一个错误信封 —— 模型仍然知道发生了什么,但**日志里不再是一片安静**。
     */
    static String encode(ObjectMapper objectMapper, Object value) {
        JsonNode node = toNode(objectMapper, value);
        String text = write(objectMapper, node);
        if (text.length() <= LIMIT || !node.isObject()) {
            return text;
        }
        ObjectNode root = (ObjectNode) node;
        root.put(TRUNCATED, true);
        text = write(objectMapper, root);
        while (text.length() > LIMIT && shrink(root)) {
            text = write(objectMapper, root);
        }
        return text;
    }

    /** 缩小一次:优先整条丢数组尾部;没有数组可丢时,把最长的文本字段砍半(砍在**值**上,序列化在其后)。 */
    private static boolean shrink(ObjectNode root) {
        ArrayNode array = largestArray(root);
        if (array != null) {
            array.remove(array.size() - 1);
            syncCount(root, array);
            return true;
        }
        return cutLongestText(root);
    }

    private static ArrayNode largestArray(ObjectNode root) {
        ArrayNode biggest = null;
        for (JsonNode child : root) {
            if (child.isArray() && child.size() > 0
                    && (biggest == null || child.size() > biggest.size())) {
                biggest = (ArrayNode) child;
            }
        }
        return biggest;
    }

    /**
     * 数组丢了条目之后,根上的 {@code count} 必须跟着改。
     *
     * <p>{@code count} 是「这次给了几条」,不是「站内一共有几条」—— 留着旧值就是让模型
     * 说出一个它手上根本没有的条数。只在根上只有一个数组时才改,避免指错对象。
     */
    private static void syncCount(ObjectNode root, ArrayNode array) {
        if (!root.has("count") || countArrays(root) != 1) {
            return;
        }
        root.put("count", array.size());
    }

    private static int countArrays(ObjectNode root) {
        int arrays = 0;
        for (JsonNode child : root) {
            if (child.isArray()) {
                arrays++;
            }
        }
        return arrays;
    }

    private static boolean cutLongestText(ObjectNode root) {
        String longest = null;
        int length = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().isTextual() && field.getValue().asText().length() > length) {
                longest = field.getKey();
                length = field.getValue().asText().length();
            }
        }
        if (longest == null || length <= CUT_MARK.length()) {
            return false;
        }
        String text = root.get(longest).asText();
        root.put(longest, text.substring(0, text.length() / 2) + CUT_MARK);
        return true;
    }

    private static JsonNode toNode(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.valueToTree(value);
        } catch (IllegalArgumentException e) {
            throw failed(value, e);
        }
    }

    private static String write(ObjectMapper objectMapper, JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw failed(node, e);
        }
    }

    private static BizException failed(Object value, Exception cause) {
        String type = value == null ? "null" : value.getClass().getName();
        // 这一段日志是「留下痕迹」的那一半(§27):序列化失败以前被吞成一个假的成功返回值。
        // 现在它既留下痕迹,也**抛出** —— 调用者不会再把它当成一个好结果。
        log.warn("工具结果序列化失败: {}", type, cause);
        return new BizException("工具结果序列化失败: " + type);
    }
}
