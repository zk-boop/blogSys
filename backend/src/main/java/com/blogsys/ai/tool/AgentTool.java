package com.blogsys.ai.tool;

import java.util.Map;

/** Agent 可调用工具:执行后返回给 LLM 的 JSON 文本。 */
public interface AgentTool {

    String name();

    String description();

    /** OpenAI JSON Schema 格式的参数定义(type=object)。 */
    Map<String, Object> parametersSchema();

    /** 执行工具并返回给 LLM 的紧凑 JSON 结果。 */
    String execute(Map<String, Object> args);
}
