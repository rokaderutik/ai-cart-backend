package com.rutikrokade.projects.aicartapp.util;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.genai.Client;
import com.google.genai.types.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import com.rutikrokade.projects.aicartapp.service.CartActionExecutionService;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.google.genai.*;
import org.springframework.core.retry.*;

class StructuredChatWireTests {

    private HttpServer server;
    private StructuredChatClient gateway;
    private final List<String> bodies = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private int status = 200;
    private String answer;
    private List<Map<String,Object>> nativeParts;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.incrementAndGet();
            String json = status == 200 ? StructuredChatClient.JSON.writeValueAsString(Map.of(
                    "candidates", List.of(Map.of("content", Map.of("role", "model", "parts", nativeParts == null ? List.of(Map.of("text", answer)) : nativeParts), "finishReason", "STOP")),
                    "modelVersion", "test-model",
                    "usageMetadata", Map.of("promptTokenCount", 100, "candidatesTokenCount", 20, "totalTokenCount", 120)))
                    : "{\"error\":{\"code\":429,\"message\":\"provider secret\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var client = Client.builder().apiKey("test-only").vertexAI(false)
                .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build();
        var model = GoogleGenAiChatModel.builder().genAiClient(client)
                .options(GoogleGenAiChatOptions.builder().model("test-model").build())
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build();
        gateway = new StructuredChatClient(model, client, "test-model");
    }

    @AfterEach void stop() { gateway.close(); server.stop(0); }

    @Test void bothNativeSchemasReachProviderWithoutToolsAndUsageIsCaptured() {
        var plan = new ChatPlanDto(); plan.setIntent(ChatPlanDto.Intent.GREETING);
        answer = StructuredChatClient.JSON.writeValueAsString(plan);
        var budget = new ChatCallBudget();
        var result = gateway.generate("planner", Map.of("message", "hello"), ChatPlanDto.class, p -> {}, budget);
        assertThat(result.getIntent()).isEqualTo(ChatPlanDto.Intent.GREETING);
        var draft = new ChatAnswerDraftDto();
        answer = StructuredChatClient.JSON.writeValueAsString(draft);
        gateway.generate("answer", Map.of(), ChatAnswerDraftDto.class, p -> {}, budget);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(paths).allSatisfy(path -> assertThat(path).contains("models/test-model:generateContent"));
        assertThat(bodies).allSatisfy(body -> assertThat(body).contains("responseJsonSchema", "application/json").doesNotContain("\"tools\""));
        assertThat(budget.getInputTokens()).isEqualTo(200);
        assertThat(budget.getOutputTokens()).isEqualTo(40);
    }

    @Test void transientProviderFailureHasNoHiddenRetriesOrSecretInPublicError() {
        status = 429;
        assertThatThrownBy(() -> gateway.generate("planner", Map.of(), ChatPlanDto.class, p -> {}, new ChatCallBudget()))
                .isInstanceOf(ApiException.class).hasMessageNotContaining("provider secret").hasNoCause();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void nativeToolRequestUsesRealSdkWithoutJsonModeContextLeakOrAutomaticExecution() {
        nativeParts = List.of(Map.of("functionCall", Map.of("name", CartQuantityTool.NAME, "args", Map.of("itemId", 11, "quantity", 2))));
        var execution = mock(CartActionExecutionService.class);
        var arguments = gateway.cartToolArguments(new PreparedCartActionDto(CartInstructionDto.Operation.SET, 11L, 1, 2),
                new CartQuantityTool(execution), new ChatCallBudget());
        assertThat(arguments).contains("11", "2");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(bodies.getFirst()).contains("functionDeclarations", CartQuantityTool.NAME, "ANY")
                .doesNotContain("responseJsonSchema", "application/json", "sessionId", "attemptToken");
        verifyNoInteractions(execution);
    }

    @Test void nativeToolRateLimitMakesOneHttpCallAndNeverExecutes() {
        status = 429;
        var execution = mock(CartActionExecutionService.class);
        assertThatThrownBy(() -> gateway.cartToolArguments(new PreparedCartActionDto(CartInstructionDto.Operation.SET, 11L, 0, 2),
                new CartQuantityTool(execution), new ChatCallBudget())).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("CHAT_MODEL_UNAVAILABLE");
        assertThat(calls.get()).isEqualTo(1);
        verifyNoInteractions(execution);
    }
}
