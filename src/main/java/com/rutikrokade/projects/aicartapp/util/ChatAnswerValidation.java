package com.rutikrokade.projects.aicartapp.util;

import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;

public final class ChatAnswerValidation {

    private ChatAnswerValidation() { }

    public static void validate(ChatAnswerDraftDto draft, ChatPlanDto plan,
                                List<RetrievedDocumentDto> evidence, RetrievalFiltersDto filters) {
        require(draft != null && draft.getSections() != null && draft.getRecommendationIds() != null);
        require(!draft.getSections().isEmpty() && draft.getSections().size() <= 8);
        Set<String> sources = new HashSet<>();
        evidence.forEach(doc -> sources.add(doc.getSourceId()));
        for (var section : draft.getSections()) {
            require(section != null && section.getText() != null && !section.getText().isBlank()
                    && section.getText().length() <= 1000 && section.getSourceIds() != null);
            require(section.getSourceIds().size() <= 10 && sources.containsAll(section.getSourceIds()));
            require(draft.isInformationMissing() || !section.getSourceIds().isEmpty());
            // Prices, numeric spice, availability and comparisons are rendered from DB by the server.
            // Prose may contain qualitative descriptions; numeric claims are deliberately conservative.
            require(!section.getText().matches("(?s).*\\d.*"));
            String text = section.getText().toLowerCase(Locale.ROOT);
            require(!text.matches("(?s).*(is|are) (guaranteed safe|allergen.free|allergy.safe|safe for your allergy|completely safe).*"));
        }
        require(draft.getRecommendationIds().size() <= 5
                && new HashSet<>(draft.getRecommendationIds()).size() == draft.getRecommendationIds().size());
        Set<Long> eligible = new HashSet<>();
        evidence.stream().filter(doc -> doc.getMenuItem() != null)
                .map(RetrievedDocumentDto::getMenuItem).filter(item -> ChatPlanValidation.eligible(item, filters))
                .forEach(item -> eligible.add(item.getId()));
        require(eligible.containsAll(draft.getRecommendationIds()));
        require(!plan.isRequiresRestaurantConfirmation() || draft.getRecommendationIds().isEmpty());
        require(plan.getOperation() == ChatPlanDto.Operation.RECOMMEND || draft.getRecommendationIds().isEmpty());
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Unverified answer output");
    }
}
