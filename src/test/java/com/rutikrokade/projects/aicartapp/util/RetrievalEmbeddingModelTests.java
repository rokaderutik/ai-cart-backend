package com.rutikrokade.projects.aicartapp.util;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.genai.Client;
import com.google.genai.types.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.ai.document.Document;
import tools.jackson.databind.json.JsonMapper;

class RetrievalEmbeddingModelTests {

    private HttpServer server;
    private Client client;
    private RetrievalEmbeddingModel model;
    private final List<String> bodies = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private int failureStatus;
    private String providerError = "test error";
    private String response = "{\"embeddings\":[{\"values\":[3,4]}]}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int call = calls.incrementAndGet();
            int status = failureStatus != 0 && call == 1 ? failureStatus : 200;
            byte[] bytes = (status == 200 ? response : JsonMapper.builder().build()
                    .writeValueAsString(Map.of("error", Map.of("message", providerError))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = Client.builder().apiKey("test-only").vertexAI(false)
                .clientOptions(ClientOptions.builder().customHttpClient(new okhttp3.OkHttpClient.Builder()
                        .addInterceptor(new EmbeddingRetryInterceptor()).build()).build())
                .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build();
        model = new RetrievalEmbeddingModel(client, "gemini-embedding-001", 2);
    }

    @AfterEach
    void stop() {
        client.close();
        server.stop(0);
    }

    @Test
    void sendsActualDocumentAndQueryTasksAndNormalizesVectorsWithoutEmbeddingMetadata() {
        var document = new Document("a mild dish", Map.of("privateValue", "should-not-be-embedded"));
        assertThat(model.embed(document)).containsExactly(0.6f, 0.8f);
        assertThat(model.embed("mild food")).containsExactly(0.6f, 0.8f);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(bodies.get(0)).contains("RETRIEVAL_DOCUMENT").doesNotContain("should-not-be-embedded");
        assertThat(bodies.get(1)).contains("RETRIEVAL_QUERY");
        assertThat(bodies.get(0)).contains("outputDimensionality");
        assertThat(model.dimensions()).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void retriesTransientHttpFailure() {
        failureStatus = 429;
        assertThat(model.embed("query")).hasSize(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void doesNotRetryAuthenticationFailuresOrExposeProviderErrorDetails() {
        failureStatus = 401;
        assertThatThrownBy(() -> model.embed("query"))
                .hasMessageContaining("HTTP 401").hasMessageContaining("authentication failed")
                .hasMessageNotContaining("test error").hasNoCause();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void explainsInvalidApiKeyWithoutLeakingProviderResponseOrRequestContent() {
        failureStatus = 400;
        providerError = "API key not valid. key=test-only; request=private restaurant content";
        assertThatThrownBy(() -> model.embed("query"))
                .hasMessageContaining("HTTP 400").hasMessageContaining("API key is invalid")
                .hasMessageNotContaining("test-only").hasMessageNotContaining("private restaurant content").hasNoCause();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void rejectsWrongDimensionAndResponseCounts() {
        response = "{\"embeddings\":[{\"values\":[1,2,3]}]}";
        assertThatThrownBy(() -> model.embed("query")).isInstanceOf(IllegalStateException.class);
        response = "{\"embeddings\":[]}";
        assertThatThrownBy(() -> model.embed("query")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonFiniteAndZeroVectors() {
        assertThatThrownBy(() -> VectorValidation.normalize(new float[]{Float.NaN, 1}, 2))
                .hasMessageContaining("non-finite");
        assertThatThrownBy(() -> VectorValidation.normalize(new float[2], 2)).hasMessageContaining("zero");
    }
}
