package com.rutikrokade.projects.aicartapp.service;

import java.util.Map;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile({"dev", "prod"})
@RequiredArgsConstructor
@Slf4j
public class CartActionServiceImpl implements CartActionService {

    private final StructuredChatClient client;
    private final CartQuantityTool tool;
    private final ChatPersistenceService persistence;

    @Override
    public ChatResponseDto handle(String session, String message, ChatPlanDto plan, ChatTurnDto turn, CartDto cart,
                                  ChatResponseDto response, ChatContextDto context, ChatCallBudget budget, long start) {
        var action = CartActionResolver.prepare(message, plan, turn, cart, context, response);
        response.getDiagnostics().put("embeddingCalls", 0);
        if (action == null) {
            ChatResponses.finish(response, budget, start);
            log.info("Cart request={} resolved without tool execution outcome={}", turn.getRequestId(), response.getOutcome());
            return persistence.complete(session, turn, response, context);
        }
        long toolStart = System.nanoTime();
        String arguments = client.cartToolArguments(action, tool, budget);
        response.getTimings().put("toolPlanningMillis", (System.nanoTime() - toolStart) / 1_000_000);
        response.getDiagnostics().put("tool", CartQuantityTool.NAME);
        var toolContext = new ToolContext(Map.of("sessionId", session, "turn", turn, "action", action,
                "response", response, "context", context, "budget", budget, "start", start));
        // Native tool generation has finished. Execute exactly once, outside any model/retry loop.
        String completed = tool.call(arguments, toolContext);
        return StructuredChatClient.JSON.readValue(completed, ChatResponseDto.class);
    }
}
