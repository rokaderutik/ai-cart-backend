package com.rutikrokade.projects.aicartapp.util;

import java.math.BigDecimal;
import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;

public final class ChatPlanValidation {

    private static final Set<String> CLEAR = Set.of("maxPrice", "dietaryTags", "categories", "spiceLevel", "restaurantConfirmation");

    private ChatPlanValidation() { }

    public static void validate(ChatPlanDto plan, String message, ChatTurnDto turn) {
        require(plan != null && plan.getIntent() != null && plan.getOperation() != null);
        require(plan.getPreferences() != null && plan.getItemIds() != null && plan.getReferencePositions() != null);
        var update = plan.getPreferences();
        require(update.getClearFields() != null && update.getClearFields().size() <= 5
                && update.getClearFields().stream().allMatch(CLEAR::contains));
        require(Set.of("LT", "LTE").contains(update.getPriceComparison()));
        require(plan.getItemIds().size() <= 5 && plan.getReferencePositions().size() <= 5);
        require(plan.getItemIds().stream().allMatch(id -> id != null && id > 0));
        require(plan.getReferencePositions().stream().allMatch(position -> position != null && position > 0 && position <= 5));
        bounded(plan.getClarification(), 500);
        bounded(plan.getUnsupportedPart(), 500);
        for (Long id : plan.getItemIds()) {
            boolean named = turn.getCatalog().stream().anyMatch(item ->
                    ((Number)item.get("id")).longValue() == id
                    && message.toLowerCase(Locale.ROOT).contains(item.get("name").toString().toLowerCase(Locale.ROOT)));
            require(named || turn.getContext().getRecommendationIds().contains(id));
        }
        // Apply validation even to non-knowledge output; malformed model filters never enter session context.
        mergedFilters(turn.getContext().getPreferences(), update);
        if (plan.getIntent() == ChatPlanDto.Intent.KNOWLEDGE) {
            require(plan.getTarget() != null);
            if (plan.getTarget() != RetrievalRequestDto.Target.GUIDE) query(plan.getMenuQuery());
            if (plan.getTarget() != RetrievalRequestDto.Target.MENU) query(plan.getGuideQuery());
            if (plan.getTarget() != RetrievalRequestDto.Target.GUIDE && turn.getContext().isRestaurantConfirmationRequired()
                    && !update.getClearFields().contains("restaurantConfirmation")) {
                plan.setRequiresRestaurantConfirmation(true);
            }
            if (plan.getTarget() != RetrievalRequestDto.Target.GUIDE
                    && message.toLowerCase(Locale.ROOT).matches("(?s).*\\b(halal|kosher|jain|certified)\\b.*")) {
                plan.setIntent(ChatPlanDto.Intent.CLARIFICATION);
                plan.setClarification("This menu does not verify that requirement. Would you like documented ingredient information, or to confirm the requirement directly with the restaurant?");
            }
            if (plan.isRequiresRestaurantConfirmation()
                    || message.toLowerCase(Locale.ROOT).matches("(?s).*(allerg|cross.contamin|celiac|coeliac|nut.free|gluten.free).*")) {
                plan.setRequiresRestaurantConfirmation(true);
                if (plan.getTarget() == RetrievalRequestDto.Target.MENU) plan.setTarget(RetrievalRequestDto.Target.BOTH);
                // Always include the documented limitations, not a search for an unsupported safety label.
                plan.setGuideQuery("allergen policy cross-contact shared kitchen restaurant confirmation");
            }
            if ((!plan.getReferencePositions().isEmpty() || plan.isUsePreviousRecommendations())
                    && (turn.getContext().getRecommendationIds().isEmpty()
                    || plan.getReferencePositions().stream().anyMatch(p -> p > turn.getContext().getRecommendationIds().size()))) {
                plan.setIntent(ChatPlanDto.Intent.CLARIFICATION);
                plan.setClarification("Which menu item do you mean? Please give its name.");
            }
        }
        if (plan.getIntent() == ChatPlanDto.Intent.CLARIFICATION) {
            require(plan.getClarification() != null && !plan.getClarification().isBlank());
        }
    }

    public static RetrievalFiltersDto mergedFilters(RetrievalFiltersDto previous, ChatPreferenceUpdateDto update) {
        var filters = StructuredChatClient.JSON.convertValue(previous, RetrievalFiltersDto.class);
        for (String field : update.getClearFields()) {
            switch (field) {
                case "maxPrice" -> filters.setMaxPrice(null);
                case "dietaryTags" -> filters.setDietaryTags(List.of());
                case "categories" -> filters.setCategories(List.of());
                case "spiceLevel" -> { filters.setMinSpiceLevel(null); filters.setMaxSpiceLevel(null); }
                case "restaurantConfirmation" -> { /* Stored separately from database filters. */ }
                default -> throw new IllegalArgumentException("Unsupported preference");
            }
        }
        if (update.getMaxPrice() != null) {
            require(update.getMaxPrice().scale() <= 2);
            filters.setMaxPrice(update.getMaxPrice().subtract("LT".equals(update.getPriceComparison()) ? new BigDecimal("0.01") : BigDecimal.ZERO));
        }
        if (update.getDietaryTags() != null) filters.setDietaryTags(update.getDietaryTags());
        if (update.getCategories() != null) filters.setCategories(update.getCategories());
        if (update.getMinSpiceLevel() != null || update.getMaxSpiceLevel() != null) {
            filters.setMinSpiceLevel(update.getMinSpiceLevel());
            filters.setMaxSpiceLevel(update.getMaxSpiceLevel());
        }
        filters.setAvailableOnly(true);
        var request = new RetrievalRequestDto();
        request.setTarget(RetrievalRequestDto.Target.MENU);
        request.setQuery("validate");
        request.setFilters(filters);
        return RetrievalValidation.normalize(request).getFilters();
    }

    public static List<Long> references(ChatPlanDto plan, ChatContextDto context) {
        var ids = new LinkedHashSet<>(plan.getItemIds());
        for (int position : plan.getReferencePositions()) ids.add(context.getRecommendationIds().get(position - 1));
        if (plan.isUsePreviousRecommendations()) ids.addAll(context.getRecommendationIds());
        require(ids.size() <= 5);
        return List.copyOf(ids);
    }

    public static boolean eligible(RetrievalMenuItemDto item, RetrievalFiltersDto filters) {
        return item.isAvailable() && (filters.getMaxPrice() == null
                || "INR".equals(item.getCurrency()) && item.getPrice().compareTo(filters.getMaxPrice()) <= 0)
                && (filters.getMinSpiceLevel() == null || item.getSpiceLevel() >= filters.getMinSpiceLevel())
                && (filters.getMaxSpiceLevel() == null || item.getSpiceLevel() <= filters.getMaxSpiceLevel())
                && item.getDietaryTags().containsAll(filters.getDietaryTags())
                && (filters.getCategories().isEmpty() || filters.getCategories().contains(item.getCategory()));
    }

    private static void bounded(String value, int max) { require(value == null || value.length() <= max); }
    private static void query(String value) { require(value != null && !value.isBlank() && value.length() <= 1000); }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid chat plan"); }
}
