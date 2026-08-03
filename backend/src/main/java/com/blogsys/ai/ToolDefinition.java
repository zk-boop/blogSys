package com.blogsys.ai;

import java.util.Map;

/** OpenAI 兼容格式的 function 工具定义:顶层 type + function。 */
public record ToolDefinition(String type, ToolFunction function) {

    public static ToolDefinition of(String name, String description, Map<String, Object> parameters) {
        return new ToolDefinition("function", new ToolFunction(name, description, parameters));
    }

    public record ToolFunction(String name, String description, Map<String, Object> parameters) {
    }
}
