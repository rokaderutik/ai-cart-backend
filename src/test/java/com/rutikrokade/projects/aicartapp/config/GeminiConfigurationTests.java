package com.rutikrokade.projects.aicartapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GeminiConfigurationTests {

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void configuresEmbeddingApiKeyWithoutVertexCredentials(String profile) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(GoogleGenAiEmbeddingConnectionAutoConfiguration.class))
                .withPropertyValues("spring.profiles.active=" + profile,
                        "GOOGLE_API_KEY=test-api-key", "FRONTEND_ORIGIN=http://localhost:5173")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(GoogleGenAiEmbeddingConnectionDetails.class);
                    assertThat(context.getBean(GoogleGenAiEmbeddingConnectionProperties.class).getApiKey())
                            .isEqualTo("test-api-key");
                    assertThat(context.getEnvironment().getProperty("spring.ai.google.genai.api-key"))
                            .isEqualTo("test-api-key");
                });
    }
}
