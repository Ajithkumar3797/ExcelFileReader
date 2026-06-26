package com.kalsec.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    private String apiKey;

    private Api   api   = new Api();
    private Model model = new Model();

    @Data
    public static class Api {
        private String baseUrl = "https://api.openai.com";
    }

    @Data
    public static class Model {
        private String chat;
        private String embedding;
    }
}
