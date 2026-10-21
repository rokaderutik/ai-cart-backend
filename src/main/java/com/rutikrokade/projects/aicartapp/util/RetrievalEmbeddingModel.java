package com.rutikrokade.projects.aicartapp.util;

import java.util.ArrayList;
import java.util.List;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.EmbedContentConfig;
import com.rutikrokade.projects.aicartapp.exception.EmbeddingProviderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;

@RequiredArgsConstructor
@Slf4j
public class RetrievalEmbeddingModel implements EmbeddingModel, AutoCloseable {

    private final Client client;
    private final String model;
    private final int dimensions;

    public String fingerprint() {
        return model + "|dimensions=" + dimensions + "|retrieval-document-query|l2-v1|renderer-v1|chunker-v1";
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public void close() {
        client.close();
    }

    @Override
    public float[] embed(Document document) {
        return generate(document.getText(), "RETRIEVAL_DOCUMENT");
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        return documents.stream().map(this::embed).toList();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> results = new ArrayList<>();
        for (String text : request.getInstructions()) {
            results.add(new Embedding(generate(text, "RETRIEVAL_QUERY"), results.size()));
        }
        return new EmbeddingResponse(results);
    }

    private float[] generate(String text, String taskType) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding text must not be empty");
        }
        try {
            var config = EmbedContentConfig.builder().taskType(taskType).outputDimensionality(dimensions).build();
            var response = client.models.embedContent(model, text, config);
            var embeddings = response.embeddings().orElseThrow();
            if (embeddings.size() != 1) {
                throw new IllegalArgumentException("Expected one embedding response");
            }
            var values = embeddings.getFirst().values().orElseThrow();
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = values.get(i);
            }
            return VectorValidation.normalize(vector, dimensions);
        } catch (ApiException failure) {
            var safeFailure = new EmbeddingProviderException(failure);
            log.warn("Embedding request rejected: model={}, task={}, HTTP={}, reason={}",
                    model, taskType, safeFailure.getStatusCode(), safeFailure.getReason());
            throw safeFailure;
        } catch (RuntimeException failure) {
            log.warn("Embedding request failed for task {}; failure type {}", taskType, failure.getClass().getSimpleName());
            throw new IllegalStateException("Embedding request failed for " + taskType + "; check provider credentials, quota, input and connectivity");
        }
    }
}
