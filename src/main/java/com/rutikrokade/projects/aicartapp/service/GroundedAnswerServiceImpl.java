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
public class GroundedAnswerServiceImpl implements GroundedAnswerService {

    private final StructuredChatClient client;

    @Override
    public ChatAnswerDraftDto answer(String message, ChatPlanDto plan, ChatTurnDto turn, CartDto cart,
                                    List<RetrievedDocumentDto> evidence, RetrievalFiltersDto filters, ChatCallBudget budget) {
        var input = new LinkedHashMap<String,Object>();
        input.put("message", message);
        input.put("plan", plan);
        input.put("preferences", filters);
        input.put("cart", cart);
        input.put("history", turn.getHistory().stream().map(m -> Map.of("role", m.getRole(), "content", m.getContent())).toList());
        input.put("evidence", evidence);
        var result = client.generate("answer", input, ChatAnswerDraftDto.class,
                draft -> ChatAnswerValidation.validate(draft, plan, evidence, filters), budget);
        log.info("Grounded chat request {} sections={} recommendations={} informationMissing={}",
                turn.getRequestId(), result.getSections().size(), result.getRecommendationIds().size(), result.isInformationMissing());
        return result;
    }
}
