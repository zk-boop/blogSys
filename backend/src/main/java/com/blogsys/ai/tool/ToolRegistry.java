package com.blogsys.ai.tool;

import com.blogsys.ai.ToolDefinition;
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

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream()
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
