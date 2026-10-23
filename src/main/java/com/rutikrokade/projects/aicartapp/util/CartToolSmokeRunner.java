package com.rutikrokade.projects.aicartapp.util;

import java.nio.file.*;
import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("cart-tool-smoke & dev & !prod")
@RequiredArgsConstructor
@Slf4j
public class CartToolSmokeRunner implements ApplicationRunner {

    private final ChatService chat;
    private final SessionService sessions;
    private final CartService carts;
    private final ChatPersistenceService persistence;

    @Value("${app.cart-tool-smoke.pause-millis:10000}")
    private long pauseMillis;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var report = new ArrayList<Map<String,Object>>();
        String session = sessions.createSession().getSessionId();
        try {
            var recommendation = send(session, "Suggest at least two mild vegetarian main dishes under 300 rupees.", report);
            require(recommendation.getRecommendations().size() >= 2, "Two recommendations are required for the ordinal-reference check");
            long chosen = recommendation.getRecommendations().getFirst().getId();
            check(session, "Add the first dish.", chosen, 1, "CART_UPDATED", report);
            check(session, "Make that two.", chosen, 2, "CART_UPDATED", report);
            check(session, "Add one more.", chosen, 3, "CART_UPDATED", report);
            check(session, "Remove one.", chosen, 2, "CART_UPDATED", report);
            check(session, "Make that two.", chosen, 2, "CART_UNCHANGED", report);
            check(session, "Remove it.", chosen, 0, "CART_UPDATED", report);
            var unknown = send(session, "Add one Moon Rock Soup.", report);
            require("CLARIFICATION".equals(unknown.getOutcome()) && carts.getCart(session).getItems().isEmpty(), "Unknown item must not change the cart");
            var multiple = send(session, "Add the first and second dishes.", report);
            require("CLARIFICATION".equals(multiple.getOutcome()) && carts.getCart(session).getItems().isEmpty(), "Multiple dishes must not partially execute");
            var checkout = send(session, "Checkout.", report);
            require("CART_ACTION_UNAVAILABLE".equals(checkout.getOutcome()), "Checkout is unsupported");
            log.info("Cart tool smoke passed: {} turns, including replay and persisted cart checks", report.size());
        } catch (Exception failure) {
            report.add(Map.of("passed", false, "failure", failure instanceof com.rutikrokade.projects.aicartapp.exception.ApiException api
                    ? api.getCode() : failure.getClass().getSimpleName()));
            throw failure;
        } finally {
            try { sessions.endSession(session); }
            finally {
                Files.createDirectories(Path.of("target"));
                Files.writeString(Path.of("target/cart-tool-development-results.json"),
                        StructuredChatClient.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            }
        }
    }

    private ChatResponseDto send(String session, String message, List<Map<String,Object>> report) throws InterruptedException {
        if (!report.isEmpty()) Thread.sleep(Math.clamp(pauseMillis, 0, 60_000));
        var request = new ChatRequestDto(); request.setRequestId(UUID.randomUUID()); request.setMessage(message);
        var result = chat.chat(session, request);
        require(((Number)result.getDiagnostics().get("modelCalls")).intValue() <= 3, "Model call budget exceeded");
        var beforeReplay = carts.getCart(session);
        var replay = chat.chat(session, request);
        require(result.getAssistantMessageId().equals(replay.getAssistantMessageId()), "Replay must reuse the assistant message");
        require(beforeReplay.equals(carts.getCart(session)), "Replay changed the cart");
        require(persistence.history(session, null, 100).getMessages().stream().filter(m -> m.getRequestId().equals(request.getRequestId())).count() == 2,
                "Request must have exactly one user and one assistant message");
        report.add(Map.of("message", message, "response", result, "replayAndHistoryPassed", true));
        log.info("Cart tool smoke outcome={} calls={}", result.getOutcome(), result.getDiagnostics().get("modelCalls"));
        return result;
    }

    private void check(String session, String message, long item, int quantity, String outcome, List<Map<String,Object>> report) throws InterruptedException {
        var result = send(session, message, report);
        require(outcome.equals(result.getOutcome()), "Unexpected cart action outcome");
        require(CartActionResolver.quantity(carts.getCart(session), item) == quantity, "Persisted quantity differs");
        require(Objects.equals(result.getActionResult().getItemId(), item), "The wrong dish was selected");
        require(((Number)result.getDiagnostics().get("embeddingCalls")).intValue() == 0, "Cart action must not embed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
