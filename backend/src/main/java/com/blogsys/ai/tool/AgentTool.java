package com.blogsys.ai.tool;

import com.blogsys.visibility.Viewer;

import java.util.Map;

/** Agent 可调用工具:执行后返回给 LLM 的 JSON 文本。 */
public interface AgentTool {

    String name();

    String description();

    /** OpenAI JSON Schema 格式的参数定义(type=object)。 */
    Map<String, Object> parametersSchema();

    /**
     * 本次提问的人能不能用这个工具。
     *
     * <p>返回 {@code false} 表示该工具<b>根本不出现在发给模型的 tools 列表里</b>。
     * 这比「调用后拒绝」干净:拒绝本身就等于告诉模型「这儿有个你看不到的能力」,
     * 它还可能反复重试,并把失败讲给用户听 —— 那是个存在性预言机。
     *
     * <p><b>它不是安全边界,只是一道 UX 门槛。</b>真正的强制在
     * {@code ChatService#executeTool} —— 那里对每一次工具调用复核同一个判定,
     * 因为模型可以凭空说出一个它没被给过的工具名。两处用同一个谓词,不可能分歧。
     *
     * <p>默认对所有人可用:站内文章、用户公开资料本就是公开语料。需要收紧的工具
     * 自己覆盖它(目前只有 {@code getSiteStats})。
     */
    default boolean isAvailableTo(Viewer viewer) {
        return true;
    }

    /** 执行工具并返回给 LLM 的紧凑 JSON 结果。 */
    String execute(Map<String, Object> args);
}
