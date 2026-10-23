package com.rutikrokade.projects.aicartapp.util;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import com.google.genai.Client;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@RequiredArgsConstructor
@Slf4j
public class StructuredChatClient implements AutoCloseable {

    public static final String VERSION = "v1";
    private static final int INPUT_TOKEN_LIMIT = 12_000;
    private static final com.knuddels.jtokkit.api.Encoding ENCODING =
            Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    public static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    private final ChatModel model;
    private final Client client;
    private final String modelName;

    public <T> T generate(String stage, Map<String,Object> input, Class<T> type,
                          Consumer<T> validator, ChatCallBudget budget) {
        String system = resource(stage + ".txt");
        String schema = resource(stage + ".schema.json");
        // Drop complete oldest conversation pairs only; never truncate knowledge or its caveats.
        var payload = new LinkedHashMap<>(input);
        var history = new ArrayList<>((List<?>)payload.getOrDefault("history", List.of()));
        payload.put("history", history);
        String data = JSON.writeValueAsString(payload);
        while (tokens(system + schema + data) > INPUT_TOKEN_LIMIT - 200 && history.size() >= 2) {
            history.subList(0, 2).clear();
            data = JSON.writeValueAsString(payload);
        }
        if (tokens(system + schema + data) > INPUT_TOKEN_LIMIT - 200) {
            throw unavailable("CHAT_CONTEXT_TOO_LARGE", "This conversation context is too large; try a more specific question");
        }
        for (;;) {
            String output;
            long start = System.nanoTime();
            try {
                // Runtime options have a provider default model; explicitly retain the profile's model.
                var options = GoogleGenAiChatOptions.builder().model(modelName).responseMimeType("application/json")
                        .responseSchema(schema).maxOutputTokens(4096)
                        .googleSearchRetrieval(false).build();
                var response = model.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(data)), options));
                var usage = response.getMetadata().getUsage();
                budget.called(usage == null ? 0 : usage.getPromptTokens(), usage == null ? 0 : usage.getCompletionTokens());
                if (response.getResult() == null || response.getResult().getOutput() == null) throw new IllegalStateException();
                output = response.getResult().getOutput().getText();
            } catch (RuntimeException failure) {
                log.warn("Chat model stage={} unavailable, type={}", stage, failure.getClass().getSimpleName());
                throw unavailable("CHAT_MODEL_UNAVAILABLE", "The chat model is temporarily unavailable; retry this request");
            }
            try {
                if (output == null || output.length() > 40_000) throw new IllegalArgumentException();
                T result = JSON.readValue(output, type);
                validator.accept(result);
                log.info("Chat model stage={} calls={} elapsedMs={} inputTokens={} outputTokens={}",
                        stage, budget.getCalls(), (System.nanoTime()-start)/1_000_000,
                        budget.getInputTokens(), budget.getOutputTokens());
                return result;
            } catch (RuntimeException invalid) {
                if (!budget.claimRepair()) {
                    throw unavailable("CHAT_INVALID_MODEL_OUTPUT", "The model could not produce a verified response; retry this request");
                }
                log.warn("Chat model stage={} needs the single validation repair", stage);
                // Do not feed unchecked output or exception details back as instructions.
                system += "\nYour previous output failed validation. Re-read the original input. Return only schema-valid JSON, "
                        + "use only supplied IDs and allowed filters, cite supplied sources, omit numeric menu claims from prose.";
            }
        }
    }

    private static int tokens(String value) { return ENCODING.countTokens(value); }

    private static String resource(String file) {
        try {
            return new ClassPathResource("prompts/chat/" + VERSION + "/" + file).getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) { throw new IllegalStateException("Chat prompt resource is missing", failure); }
    }

    private static ApiException unavailable(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }

    @Override
    public void close() { client.close(); }
}
