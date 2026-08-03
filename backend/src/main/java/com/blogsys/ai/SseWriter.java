package com.blogsys.ai;

import java.io.IOException;

/** SSE 事件写入器,将 ChatService 与 Servlet API 解耦,便于单测。 */
public interface SseWriter {

    void event(String name, String dataJson) throws IOException;
}
