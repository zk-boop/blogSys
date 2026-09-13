package com.blogsys.ai.tool;

import com.blogsys.ai.ToolDefinition;
import com.blogsys.visibility.Viewer;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> toolList) {
        // 工具重名会在启动时崩 —— 崩是对的(那是配置错误),但消息必须说清是谁重了。
        // 此前用 Collectors.toMap 建表,重名时抛的是一句看不出所以然的
        // `Duplicate key`。顺带保留注入顺序:发给模型的工具清单因此是确定的。
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        for (AgentTool tool : toolList) {
            AgentTool clash = byName.putIfAbsent(tool.name(), tool);
            if (clash != null) {
                throw new IllegalStateException("工具名重复: " + tool.name()
                        + "(" + clash.getClass().getSimpleName()
                        + " 与 " + tool.getClass().getSimpleName() + ")");
            }
        }
        this.tools = Collections.unmodifiableMap(byName);
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
        List<String> required = tool.requiredParameters();
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }
}
