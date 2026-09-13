package com.blogsys.ai.tool;

import com.blogsys.common.BizException;
import com.blogsys.service.AdminService;
import com.blogsys.service.ArticleService;
import com.blogsys.service.RecommendService;
import com.blogsys.service.UserService;
import com.blogsys.visibility.Viewer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具的**契约**:参数怎么取、schema 怎么声明必填、重名怎么办。
 *
 * <p>这三件事此前都没有归属:参数强转被复制了三次(两个工具各一份私有 `idOf`,一个内联),
 * schema 里没有 `required`,而 `AbstractTool` 一个抽象成员都没有 —— 它只是命名空间。
 */
class ToolContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ArticleDetailTool detailTool() {
        // schema 与参数校验都不碰 service,所以这里不需要它
        return new ArticleDetailTool(null, mapper);
    }

    // ---------- 必填参数 ----------

    @Test
    @DisplayName("漏传必填参数时给一句模型看得懂的话,而不是 Java 的 For input string: \"null\"")
    void requiredLong_shouldExplain_whenTheParameterIsMissing() {
        BizException e = assertThrows(BizException.class, () -> detailTool().execute(Map.of()));

        assertTrue(e.getMessage().contains("id 是必填参数"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("参数不是整数时也说清楚收到了什么")
    void requiredLong_shouldExplain_whenTheParameterIsNotANumber() {
        BizException e = assertThrows(BizException.class,
                () -> detailTool().execute(Map.of("id", "第十七篇")));

        assertTrue(e.getMessage().contains("需要是一个整数"), "实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("第十七篇"), "把收到的值带出来,实际: " + e.getMessage());
    }

    @Test
    @DisplayName("数字与数字字符串都收 —— 模型两种都可能给")
    void requiredLong_shouldAcceptNumbersAndNumericStrings() {
        // 走到 service 就会 NPE(这里是 null),所以只验「没在取参数这一步抛」
        assertThrows(NullPointerException.class, () -> detailTool().execute(Map.of("id", 17)));
        assertThrows(NullPointerException.class, () -> detailTool().execute(Map.of("id", "17")));
    }

    // ---------- schema 里的 required ----------

    @Test
    @DisplayName("需要 id 的三个工具都把它声明成必填")
    void toolsWithAnIdParameter_shouldDeclareItRequired() {
        assertEquals(List.of("id"), new ArticleDetailTool(null, mapper).requiredParameters());
        assertEquals(List.of("articleId"),
                new RecommendArticlesTool(null, mapper).requiredParameters());
        assertEquals(List.of("userId"), new UserProfileTool(null, mapper).requiredParameters());
    }

    @Test
    @DisplayName("零参数工具不声明必填 —— 它们的调用不该被 required 挡住")
    void toolsWithoutParameters_shouldDeclareNothingRequired() {
        assertTrue(new SiteStatsTool(null, mapper).requiredParameters().isEmpty());
        assertTrue(new HotArticlesTool(null, mapper).requiredParameters().isEmpty());
    }

    @Test
    @DisplayName("required 真的出现在发给模型的 schema 里")
    void registry_shouldEmitRequiredInTheSchema() {
        ToolRegistry registry = new ToolRegistry(List.of(new ArticleDetailTool(null, mapper)));

        Map<String, Object> schema = parameters(registry.definitions(Viewer.anonymous()).get(0));

        assertEquals(List.of("id"), schema.get("required"));
        assertTrue(schema.containsKey("properties"));
    }

    @Test
    @DisplayName("没有必填参数的工具有 schema 里就不带 required 这个键")
    void registry_shouldOmitRequired_whenThereIsNone() {
        ToolRegistry registry = new ToolRegistry(List.of(new SiteStatsTool(null, mapper)));

        Map<String, Object> schema = parameters(registry.definitions(Viewer.of(1L, true)).get(0));

        assertFalse(schema.containsKey("required"));
    }

    // ---------- 重名 ----------

    @Test
    @DisplayName("工具重名在启动时就说清是谁重了,而不是一句 Duplicate key")
    void registry_shouldNameTheClashingTools() {
        AgentTool a = new SiteStatsTool(null, mapper);
        AgentTool b = new SiteStatsTool(null, mapper);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ToolRegistry(List.of(a, b)));

        assertTrue(e.getMessage().contains("工具名重复"), "实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("SiteStatsTool"), "要指出是谁,实际: " + e.getMessage());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parameters(com.blogsys.ai.ToolDefinition definition) {
        return (Map<String, Object>) definition.function().parameters();
    }

    // ---------- 序列化失败 ----------

    @Test
    @DisplayName("序列化失败要留下痕迹 —— 那个 catch 此前是个黑洞")
    void json_shouldLogAndReturnAnEnvelope_whenSerializationFails() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AbstractTool.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String result = AbstractTool.json(mapper, cyclic());

            assertTrue(result.contains("结果序列化失败"), "仍然要给出一段 JSON,实际: " + result);
            assertTrue(appender.list.stream()
                            .anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN),
                    "失败必须留下痕迹 —— 否则它与一个正常的工具结果长得一模一样");
        } finally {
            logger.detachAppender(appender);
        }
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
