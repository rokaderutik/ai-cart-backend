package com.rutikrokade.projects.aicartapp.config;

import java.time.Duration;
import com.google.genai.Client;
import com.google.genai.types.*;
import com.rutikrokade.projects.aicartapp.util.StructuredChatClient;
import okhttp3.OkHttpClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.retry.*;

@Configuration(proxyBeanMethods = false)
@Profile({"dev", "prod"})
public class ChatModelConfig {

    @Bean(destroyMethod = "close")
    public StructuredChatClient structuredChatClient(Environment environment) {
        String key = environment.getRequiredProperty("spring.ai.google.genai.api-key");
        String model = environment.getRequiredProperty("spring.ai.google.genai.chat.model");
        if (key.isBlank() || key.contains("${")) throw new IllegalArgumentException("Chat API key must be resolved");
        OkHttpClient transport = new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(30))
                .connectTimeout(Duration.ofSeconds(30)).readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30)).retryOnConnectionFailure(false).build();
        // Private ownership avoids eager shared Client auto-configuration and unresolved placeholders.
        Client client = Client.builder().apiKey(key).vertexAI(false)
                .clientOptions(ClientOptions.builder().customHttpClient(transport).build())
                .httpOptions(HttpOptions.builder().timeout(30_000)
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build();
        var chatModel = GoogleGenAiChatModel.builder().genAiClient(client)
                .options(GoogleGenAiChatOptions.builder().model(model).googleSearchRetrieval(false).build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build();
        return new StructuredChatClient(chatModel, client, model);
    }
}
