package com.blogsys.ai;

/** 流式对话事件监听器:content 实时回调,结束前回调工具调用(如有)。 */
public interface ChatStreamListener {

    /** 内容增量,直接转发给用户。 */
    void onContent(String delta);

    /** 流结束且本轮包含工具调用(已按 index 聚合完整)。 */
    void onToolCalls(java.util.List<ChatMessage.ToolCall> toolCalls);

    /** 流结束且无工具调用。 */
    void onFinish();
}
