package com.blogsys.ai.tool;

import com.blogsys.service.AdminService;
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

    @Override
    public String execute(Map<String, Object> args) {
        return json(objectMapper, adminService.stats());
    }
}
