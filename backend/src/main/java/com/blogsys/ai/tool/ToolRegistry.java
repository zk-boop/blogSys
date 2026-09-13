package com.blogsys.ai.tool;

import com.blogsys.ai.ToolDefinition;
import com.blogsys.visibility.Viewer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> toolList) {
        this.tools = toolList.stream()
                .collect(Collectors.toMap(AgentTool::name, Function.identity()));
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    /**
     * 发给模型的工具清单,只含 {@code viewer} 可用的那些。
     *
     * <p>没有「不过滤」的变体 —— 与可见性模块的 {@code articles()} 同一个道理:
     * 调用方拿到清单时过滤已经在了,忘不掉。多一个无参重载,就等于多一次
     * 「谁忘了传 viewer」的机会。
     */
    public List<ToolDefinition> definitions(Viewer viewer) {
        return tools.values().stream()
                .filter(tool -> tool.isAvailableTo(viewer))
                .map(tool -> ToolDefinition.of(tool.name(), tool.description(),
                        schema(tool)))
                .toList();
    }

    private Map<String, Object> schema(AgentTool tool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", tool.parametersSchema());
        return schema;
    }
}
