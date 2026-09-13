package com.blogsys.ai.tool;

import com.blogsys.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具结果的预算:超了怎么办。
 *
 * <p>此前这条预算长在 {@code ChatService} 里,而且是 `result.substring(0, 4000)` ——
 * 在**序列化之后的文本**上切。切断一个 JSON 字符串,那段「JSON」就不再是 JSON,
 * 而它会作为工具结果原样回到模型那里。
 *
 * <p>本测试的第一条就是**对照**:同样一份数据、同样的上限,老办法切出来的东西
 * 解析不了。没有这条对照,下面那些「合法 JSON」的断言可能只是碰巧成立。
 */
class ResultBudgetTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 一份超预算的结果:50 篇标题很长的文章。 */
    private ObjectNode bigList(int articles) {
        ArrayNode array = mapper.createArrayNode();
        for (int i = 0; i < articles; i++) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", i);
            node.put("title", "第 " + i + " 篇:" + "很长的标题".repeat(30));
            node.put("viewCount", i);
            array.add(node);
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("count", articles);
        root.set("articles", array);
        return root;
    }

    @Test
    @DisplayName("对照:老办法(substring)切出来的那段东西根本不是 JSON")
    void substringTruncation_producesSomethingThatIsNotJson() throws Exception {
        ObjectNode root = bigList(50);
        String whole = mapper.writeValueAsString(root);
        assertTrue(whole.length() > ResultBudget.LIMIT, "前提:这份结果确实超预算");

        String cut = whole.substring(0, ResultBudget.LIMIT) + "…(已截断)";

        assertThrows(Exception.class, () -> mapper.readTree(cut),
                "如果这句话能解析,说明这条对照失去了分辨力 —— 那下面所有断言也就没有意义了");
    }

    @Test
    @DisplayName("超预算时出来的是合法 JSON,而且还在预算内")
    void encode_shouldStayValidJson_withinTheLimit() throws Exception {
        String text = ResultBudget.encode(mapper, bigList(50));

        JsonNode parsed = mapper.readTree(text);
        assertTrue(text.length() <= ResultBudget.LIMIT, "长度 " + text.length());
        assertTrue(parsed.path("articles").isArray(), "数组还得是数组,而不是半段文本");
        assertTrue(parsed.path("truncated").asBoolean(), "要如实标注自己被动过");
    }

    @Test
    @DisplayName("丢的是整条,保住的是开头 —— 模型至少拿到前几篇")
    void encode_shouldKeepTheHead_ofTheList() throws Exception {
        JsonNode parsed = mapper.readTree(ResultBudget.encode(mapper, bigList(50)));

        assertFalse(parsed.path("articles").isEmpty(), "一条都不剩就不是截断,是丢弃");
        assertEquals(0, parsed.path("articles").get(0).path("id").asInt(), "留下的必须是开头那几条");
    }

    @Test
    @DisplayName("count 跟着实际留下来的条数改 —— 否则模型会照着它说出不存在的数据")
    void encode_shouldKeepCountInSync() throws Exception {
        JsonNode parsed = mapper.readTree(ResultBudget.encode(mapper, bigList(50)));

        assertEquals(parsed.path("articles").size(), parsed.path("count").asInt(),
                "count 说的是「这次给了几条」,不是「站内一共有几条」");
        assertTrue(parsed.path("count").asInt() < 50, "前提:确实丢了条目");
    }

    @Test
    @DisplayName("没有数组可丢时砍最长的那个字段 —— 砍在值上,序列化在其后,所以还是合法 JSON")
    void encode_shouldCutLongText_whenThereIsNoArrayToShrink() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", 17);
        root.put("content", "正文".repeat(5000));

        String text = ResultBudget.encode(mapper, root);

        JsonNode parsed = mapper.readTree(text);
        assertTrue(text.length() <= ResultBudget.LIMIT, "长度 " + text.length());
        assertTrue(parsed.path("content").asText().endsWith("…(已截断)"), "截断要留下痕迹");
        assertEquals(17, parsed.path("id").asInt(), "没超的字段一个都不该动");
    }

    @Test
    @DisplayName("没超预算的结果一个字节都不动")
    void encode_shouldPassThrough_smallResults() {
        ObjectNode root = bigList(1);

        String text = ResultBudget.encode(mapper, root);

        assertEquals(root.toString(), text);
        assertFalse(root.has("truncated"));
    }

    @Test
    @DisplayName("序列化不了就抛,不再返回一个与正常结果长得一样的错误信封")
    void encode_shouldThrow_whenSerializationFails() {
        BizException e = assertThrows(BizException.class, () -> ResultBudget.encode(mapper, cyclic()));

        assertTrue(e.getMessage().contains("序列化失败"), "实际: " + e.getMessage());
        assertTrue(e.getMessage().contains(cyclic().getClass().getName()),
                "要说清是什么东西序列化不了,实际: " + e.getMessage());
    }

    /** 一个 JSON 序列化不了的对象(自引用 ⇒ 无限递归)。 */
    private Object cyclic() {
        return new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                return this;
            }
        };
    }
}
