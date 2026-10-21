package com.rutikrokade.projects.aicartapp.config;

import java.time.Duration;
import com.google.genai.Client;
import com.google.genai.types.*;
import com.rutikrokade.projects.aicartapp.util.EmbeddingRetryInterceptor;
import com.rutikrokade.projects.aicartapp.util.RetrievalEmbeddingModel;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile({"dev", "prod", "ingest", "model-smoke"})
public class KnowledgeEmbeddingConfig {

    private Client knowledgeEmbeddingClient(String apiKey) {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30)).readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30)).retryOnConnectionFailure(false)
                .addInterceptor(new EmbeddingRetryInterceptor()).build();
        return Client.builder().apiKey(apiKey).vertexAI(false)
                .clientOptions(ClientOptions.builder().customHttpClient(http).build())
                .httpOptions(HttpOptions.builder().timeout(30_000)
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build();
    }

    @Bean(destroyMethod = "close")
    @Primary
    public RetrievalEmbeddingModel retrievalEmbeddingModel(Environment environment) {
        String model = environment.getRequiredProperty("spring.ai.google.genai.embedding.text.model");
        int dimensions = environment.getRequiredProperty("spring.ai.google.genai.embedding.text.dimensions", Integer.class);
        if (dimensions != 768) {
            throw new IllegalArgumentException("Changing embedding dimensions requires a vector schema migration and rebuild");
        }
        String apiKey = environment.getRequiredProperty("spring.ai.google.genai.embedding.api-key");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("Google embedding API key must not be blank");
        }
        // Keep this client private: Spring AI cache conditions can eagerly instantiate shared Client beans.
        return new RetrievalEmbeddingModel(knowledgeEmbeddingClient(apiKey), model, dimensions);
    }
}
