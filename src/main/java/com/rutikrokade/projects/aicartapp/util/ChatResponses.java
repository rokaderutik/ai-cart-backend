package com.rutikrokade.projects.aicartapp.util;

import java.util.List;
import java.util.stream.Collectors;
import com.rutikrokade.projects.aicartapp.dto.*;

public final class ChatResponses {

    private ChatResponses() { }

    public static void text(ChatResponseDto response, String outcome, String text) {
        response.setOutcome(outcome);
        var section = new ChatAnswerSectionDto();
        section.setText(text);
        section.setSourceIds(List.of());
        response.setSections(List.of(section));
    }

    public static void finish(ChatResponseDto response, ChatCallBudget budget, long start) {
        response.setReply(response.getSections().stream().map(ChatAnswerSectionDto::getText).collect(Collectors.joining("\n\n")));
        response.getTimings().put("totalMillis", (System.nanoTime() - start) / 1_000_000);
        response.getDiagnostics().put("modelCalls", budget.getCalls());
        response.getDiagnostics().put("repairUsed", budget.isRepaired());
        response.getDiagnostics().put("inputTokens", budget.getInputTokens());
        response.getDiagnostics().put("outputTokens", budget.getOutputTokens());
    }
}
