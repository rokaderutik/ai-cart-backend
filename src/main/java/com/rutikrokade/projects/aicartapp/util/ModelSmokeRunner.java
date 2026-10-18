package com.rutikrokade.projects.aicartapp.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("model-smoke")
@RequiredArgsConstructor
@Slf4j
public class ModelSmokeRunner implements ApplicationRunner {

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;

    @Override
    public void run(ApplicationArguments args) {
        String response = chatModel.call("Reply with only the word ready.");
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Gemini chat returned an empty response");
        }
        log.info("gemini response: {}", response);

        float[] embedding = embeddingModel.embed("A mildly spicy vegetarian dish");
        if (embedding.length != 768) {
            throw new IllegalStateException("Expected 768 embedding dimensions, got " + embedding.length);
        }

        log.info("Gemini smoke check passed: chat response received; embedding dimensions={}",
                embedding.length);
    }
}
