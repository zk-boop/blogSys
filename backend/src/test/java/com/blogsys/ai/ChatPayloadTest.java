package com.blogsys.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求载荷此前也长在 {@code OpenAiClient} 里,而那个类只能靠一次真实 HTTP 调用触达 ——
 * 于是「tools 为空时不该带 tools 字段」这条约定从来没有人断言过。
 */
class ChatPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode payload(List<ToolDefinition> tools) throws Exception {
        return mapper.readTree(ChatPayload.of(mapper, "gpt-4o-mini",
                List.of(ChatMessage.user("你好")), tools));
    }

    @Test
    @DisplayName("基本形状:model / stream / temperature / messages")
    void shouldBuildTheUsualShape() throws Exception {
        JsonNode node = payload(List.of());

        assertEquals("gpt-4o-mini", node.path("model").asText());
        assertTrue(node.path("stream").asBoolean(), "这条链路是流式的");
        assertEquals("你好", node.path("messages").path(0).path("content").asText());
    }

    @Test
    @DisplayName("tools 为空或为 null 时不带 tools,也不带 tool_choice")
    void shouldOmitTools_whenThereAreNone() throws Exception {
        assertFalse(payload(List.of()).has("tools"));
        assertFalse(payload(null).has("tools"));
        assertFalse(payload(List.of()).has("tool_choice"));
        assertFalse(payload(null).has("tool_choice"));
    }

    @Test
    @DisplayName("有工具时带上 tools 与 tool_choice=auto")
    void shouldIncludeTools_whenPresent() throws Exception {
        JsonNode node = payload(List.of(ToolDefinition.of("getHotArticles", "热门文章", Map.of())));

        assertEquals("getHotArticles",
                node.path("tools").path(0).path("function").path("name").asText());
        assertEquals("auto", node.path("tool_choice").asText());
    }
}
