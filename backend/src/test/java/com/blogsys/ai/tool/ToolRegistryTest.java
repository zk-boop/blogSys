package com.blogsys.ai.tool;

import com.blogsys.service.AdminService;
import com.blogsys.visibility.Viewer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 工具清单是按提问者算出来的。
 *
 * <p>这里用的是<b>真实的</b> {@link SiteStatsTool},不是 mock —— 要钉住的正是它的受众策略。
 * mock 的 {@code isAvailableTo} 默认返回 false,拿它来断言「普通用户看不到」等于什么也没说。
 *
 * <p>D1 的背景:{@code AdminService.stats()} 返回原始汇报值,HTTP 层
 * {@code GET /api/admin/stats} 是 ADMIN-only,靠的是 URL 规则 —— 而工具路径不经过任何
 * URL 规则。这道闸就是补上那一道。
 */
@ExtendWith(MockitoExtension.class)
class ToolRegistryTest {

    private static final AgentTool PUBLIC_TOOL = new StubTool("searchArticles");

    @Mock
    private AdminService adminService;

    private SiteStatsTool siteStatsTool;

    @BeforeEach
    void setUp() {
        siteStatsTool = new SiteStatsTool(adminService, new ObjectMapper());
    }

    private ToolRegistry registry() {
        return new ToolRegistry(List.of(PUBLIC_TOOL, siteStatsTool));
    }

    /** 工具清单的迭代顺序来自 HashMap,所以比较前排序 —— 顺序不是契约,成员才是。 */
    private static List<String> offeredNames(Viewer viewer, ToolRegistry registry) {
        return registry.definitions(viewer).stream()
                .map(definition -> definition.function().name())
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("普通登录用户:ADMIN 专属工具根本不出现在发给模型的清单里")
    void definitions_shouldHideAdminOnlyTool_fromNonAdmin() {
        assertEquals(List.of("searchArticles"), offeredNames(Viewer.of(6L, false), registry()));
    }

    @Test
    @DisplayName("匿名:同样看不到")
    void definitions_shouldHideAdminOnlyTool_fromAnonymous() {
        assertEquals(List.of("searchArticles"), offeredNames(Viewer.anonymous(), registry()));
    }

    @Test
    @DisplayName("管理员:两个都在 —— 过滤的是人,不是工具")
    void definitions_shouldIncludeAdminOnlyTool_forAdmin() {
        assertEquals(List.of("getSiteStats", "searchArticles"),
                offeredNames(Viewer.of(1L, true), registry()));
    }

    @Test
    @DisplayName("getSiteStats 的受众就是管理员")
    void siteStats_shouldBeAdminOnly() {
        assertTrue(siteStatsTool.isAvailableTo(Viewer.of(1L, true)));
        assertFalse(siteStatsTool.isAvailableTo(Viewer.of(6L, false)));
        assertFalse(siteStatsTool.isAvailableTo(Viewer.anonymous()));
    }

    @Test
    @DisplayName("可用时返回的仍是原始汇报值(含草稿与封禁作者)—— 刻意不改口径")
    void siteStats_shouldStillReportRawNumbers() {
        when(adminService.stats()).thenReturn(Map.of("articleCount", 12, "userCount", 4));

        String result = siteStatsTool.execute(Map.of());

        assertTrue(result.contains("\"articleCount\":12"), "实际: " + result);
        assertTrue(result.contains("\"userCount\":4"), "实际: " + result);
    }

    /** 站内公开语料的工具:对所有人可用。 */
    private record StubTool(String name) implements AgentTool {

        @Override
        public String description() {
            return "测试用工具";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of();
        }

        @Override
        public String execute(Map<String, Object> args) {
            return "{}";
        }
    }
}
