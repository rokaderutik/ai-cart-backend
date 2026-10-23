package com.rutikrokade.projects.aicartapp.util;

import java.nio.file.*;
import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

@Component
@Profile("chat-smoke & dev & !prod")
@RequiredArgsConstructor
@Slf4j
public class ChatSmokeRunner implements ApplicationRunner {

    private final ChatService chat;
    private final SessionService sessions;
    private final CartService carts;
    private final ChatPersistenceService history;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Map<String,Object>> scenarios;
        try (var stream = new ClassPathResource("evaluation/chat-development.json").getInputStream()) {
            scenarios = StructuredChatClient.JSON.readValue(stream, new TypeReference<List<Map<String,Object>>>() { });
        }
        var report = new ArrayList<Map<String,Object>>();
        for (var scenario : scenarios) {
            String session = sessions.createSession().getSessionId();
            try {
                @SuppressWarnings("unchecked")
                var turns = (List<Map<String,Object>>)scenario.get("turns");
                for (var test : turns) {
                    var row = new LinkedHashMap<String,Object>();
                    row.put("scenario", scenario.get("id")); row.put("message", test.get("message"));
                    var request = new ChatRequestDto();
                    request.setRequestId(UUID.randomUUID()); request.setMessage((String)test.get("message"));
                    try {
                        var result = chat.chat(session, request);
                        var checks = new LinkedHashMap<String,Boolean>();
                        checks.put("boundedModelCalls", ((Number)result.getDiagnostics().get("modelCalls")).intValue() <= 3);
                        int expectedQuantity = test.containsKey("cartQuantity") ? ((Number)test.get("cartQuantity")).intValue() : 0;
                        checks.put("cartQuantity", carts.getCart(session).getItems().stream().mapToInt(CartItemDto::getQuantity).sum() == expectedQuantity);
                        if (test.containsKey("target")) checks.put("target", test.get("target").equals(String.valueOf(result.getDiagnostics().get("target"))));
                        if (test.containsKey("outcomes")) checks.put("outcome", ((List<?>)test.get("outcomes")).contains(result.getOutcome()));
                        if (test.containsKey("cards")) checks.put("cards", result.getRecommendations().size() >= 2
                                && result.getRecommendations().stream().allMatch(item -> item.getPrice().compareTo(new java.math.BigDecimal("300")) < 0
                                && item.getSpiceLevel() <= 1 && item.getDietaryTags().contains("VEGETARIAN")));
                        if (test.containsKey("exact")) checks.put("exact", Boolean.TRUE.equals(result.getDiagnostics().get("exactItemLookup")));
                        if (test.containsKey("noCards")) checks.put("noCards", result.getRecommendations().isEmpty());
                        if (test.containsKey("both")) checks.put("bothSources", result.getSources().stream().anyMatch(s -> s.getSourceType().equals("MENU"))
                                && result.getSources().stream().anyMatch(s -> s.getSourceType().equals("GUIDE")));
                        if (test.containsKey("skipRetrieval")) checks.put("skipRetrieval", ((Number)result.getDiagnostics().getOrDefault("embeddingCalls", 0)).intValue() == 0);
                        if (test.containsKey("scopeNotice")) checks.put("scopeNotice", result.getReply().contains("restaurant-related part"));
                        var replay = chat.chat(session, request);
                        checks.put("replay", result.getAssistantMessageId().equals(replay.getAssistantMessageId()) && result.getReply().equals(replay.getReply()));
                        checks.put("history", history.history(session, null, 100).getMessages().stream()
                                .filter(m -> m.getRequestId().equals(request.getRequestId())).count() == 2);
                        row.put("response", result); row.put("checks", checks);
                        row.put("passed", checks.values().stream().allMatch(Boolean.TRUE::equals));
                    } catch (RuntimeException failure) {
                        row.put("passed", false);
                        row.put("error", failure instanceof com.rutikrokade.projects.aicartapp.exception.ApiException api
                                ? api.getCode() : failure.getClass().getSimpleName());
                    }
                    report.add(row);
                    log.info("Chat smoke scenario={} passed={}", scenario.get("id"), row.get("passed"));
                    Files.createDirectories(Path.of("target"));
                    Files.writeString(Path.of("target/chat-development-results.json"),
                            StructuredChatClient.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                    if ("CHAT_MODEL_UNAVAILABLE".equals(row.get("error"))) {
                        throw new IllegalStateException("Chat provider unavailable; stopped early to avoid repeated failed calls. Inspect the smoke report.");
                    }
                }
            } finally { sessions.endSession(session); }
        }
        long passed = report.stream().filter(row -> Boolean.TRUE.equals(row.get("passed"))).count();
        log.info("Chat development smoke completed: {}/{} passed; report target/chat-development-results.json", passed, report.size());
        if (passed != report.size()) throw new IllegalStateException("Some chat development scenarios failed; inspect the report");
    }
}
