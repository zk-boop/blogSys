package com.blogsys.ai.tool;

import com.blogsys.service.AdminService;
import com.blogsys.visibility.Viewer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SiteStatsTool extends AbstractTool {

    private final AdminService adminService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "getSiteStats";
    }

    @Override
    public String description() {
        return "获取全站统计:文章数、用户数、评论数、标签数、点赞数、今日新增文章与用户";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return new LinkedHashMap<>();
    }

    /**
     * 只有管理员能用。
     *
     * <p>{@code AdminService.stats()} 返回的是<b>原始汇报值</b>(含草稿与封禁作者的内容,
     * 这是刻意保留的,见该方法的 javadoc)。HTTP 层 {@code GET /api/admin/stats} 是
     * ADMIN-only,靠的是 {@code SecurityConfig} 的 URL 规则 —— 而工具路径不经过任何 URL 规则,
     * 于是同一个数据在这里曾经对任意登录用户敞开。这个判定就是补上那一道。
     *
     * <p>不放宽成「可见性口径的计数」:那会让管理员失去「12 篇里有 5 篇草稿」这个更有用的
     * 数字,而普通用户本来也没有必要知道全站总量。
     */
    @Override
    public boolean isAvailableTo(Viewer viewer) {
        return viewer.isAdmin();
    }

    @Override
    public String execute(Map<String, Object> args) {
        return json(objectMapper, adminService.stats());
    }
}
