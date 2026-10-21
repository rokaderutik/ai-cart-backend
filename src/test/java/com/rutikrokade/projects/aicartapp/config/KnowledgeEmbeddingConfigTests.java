package com.rutikrokade.projects.aicartapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import com.google.genai.Client;
import com.rutikrokade.projects.aicartapp.util.RetrievalEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class KnowledgeEmbeddingConfigTests {

    @Test
    void resolvesNestedKeyEvenWhenCreatedBeforePlaceholderPostProcessing() {
        AtomicBoolean createdEarly = new AtomicBoolean();
        new ApplicationContextRunner()
                .withUserConfiguration(KnowledgeEmbeddingConfig.class)
                .withPropertyValues("spring.profiles.active=dev",
                        "GOOGLE_API_KEY=test-key-only",
                        "spring.ai.google.genai.embedding.api-key=${GOOGLE_API_KEY}",
                        "spring.ai.google.genai.embedding.text.model=gemini-embedding-001",
                        "spring.ai.google.genai.embedding.text.dimensions=768")
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> {
                    var model = factory.getBean(RetrievalEmbeddingModel.class);
                    Client client = (Client) ReflectionTestUtils.getField(model, "client");
                    // Compare as a boolean so assertion output can never reveal a credential.
                    createdEarly.set(client != null && "test-key-only".equals(client.apiKey()));
                }))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RetrievalEmbeddingModel.class);
                    assertThat(createdEarly.get()).isTrue();
                    assertThat(context.getBeansOfType(Client.class)).isEmpty();
                    assertThat(context.getBean(RetrievalEmbeddingModel.class).dimensions()).isEqualTo(768);
                });
    }

    @Test
    void rejectsBlankKeyDuringStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(KnowledgeEmbeddingConfig.class)
                .withPropertyValues("spring.profiles.active=dev",
                        "spring.ai.google.genai.embedding.api-key=",
                        "spring.ai.google.genai.embedding.text.model=gemini-embedding-001",
                        "spring.ai.google.genai.embedding.text.dimensions=768")
                .run(context -> assertThat(context).hasFailed());
    }
}
