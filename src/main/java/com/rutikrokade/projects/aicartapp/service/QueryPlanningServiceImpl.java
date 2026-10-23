package com.rutikrokade.projects.aicartapp.service;

import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile({"dev", "prod"})
@RequiredArgsConstructor
@Slf4j
public class QueryPlanningServiceImpl implements QueryPlanningService {

    private final StructuredChatClient client;

    @Override
    public ChatPlanDto plan(String message, ChatTurnDto turn, CartDto cart, ChatCallBudget budget) {
        Map<String,Object> input = new LinkedHashMap<>();
        input.put("message", message);
        input.put("history", turn.getHistory().stream().map(m -> Map.of("role", m.getRole(), "content", m.getContent())).toList());
        input.put("context", turn.getContext());
        input.put("catalog", turn.getCatalog());
        input.put("cart", cart);
        ChatPlanDto result = client.generate("planner", input, ChatPlanDto.class,
                plan -> ChatPlanValidation.validate(plan, message, turn), budget);
        log.info("Planned chat request {} intent={} target={} operation={} confirmation={}",
                turn.getRequestId(), result.getIntent(), result.getTarget(), result.getOperation(), result.isRequiresRestaurantConfirmation());
        return result;
    }
}
