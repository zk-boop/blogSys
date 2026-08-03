package com.blogsys.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "blogsys.ai")
public class AiProperties {

    /** OpenAI 兼容 API 基础地址,如 https://api.openai.com/v1 */
    private String baseUrl = "https://api.openai.com/v1";

    /** 为空时 AI 功能返回"未配置"提示 */
    private String apiKey = "";

    private String model = "gpt-4o-mini";

    private int timeoutSeconds = 120;

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
