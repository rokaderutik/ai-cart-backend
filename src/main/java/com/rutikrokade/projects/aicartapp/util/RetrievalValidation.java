package com.rutikrokade.projects.aicartapp.util;

import java.util.List;
import java.util.Set;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import org.springframework.http.HttpStatus;

public final class RetrievalValidation {

    private static final Set<String> CATEGORIES = Set.of("MAIN", "RICE", "BREAD", "DESSERT", "STARTER", "SIDE", "DRINK");
    private static final Set<String> TAGS = Set.of("VEGETARIAN", "VEGAN", "NON_VEGETARIAN");

    private RetrievalValidation() { }

    /** Copy and validate: callers cannot change filters while an embedding request is in flight. */
    public static RetrievalRequestDto normalize(RetrievalRequestDto input) {
        if (input == null || input.getTarget() == null) {
            throw invalid("target is required");
        }
        RetrievalRequestDto result = new RetrievalRequestDto();
        result.setTarget(input.getTarget());
        result.setQuery(query(input.getQuery()));
        if (input.getTarget() != RetrievalRequestDto.Target.BOTH
                && (input.getMenuQuery() != null || input.getGuideQuery() != null)) {
            throw invalid("Branch queries are supported only for BOTH");
        }
        result.setMenuQuery(input.getMenuQuery() == null ? result.getQuery() : query(input.getMenuQuery()));
        result.setGuideQuery(input.getGuideQuery() == null ? result.getQuery() : query(input.getGuideQuery()));
        if (input.getTarget() == RetrievalRequestDto.Target.GUIDE && input.getFilters() != null) {
            throw invalid("GUIDE does not accept menu filters");
        }
        RetrievalFiltersDto source = input.getFilters() == null ? new RetrievalFiltersDto() : input.getFilters();
        RetrievalFiltersDto filters = new RetrievalFiltersDto();
        if (source.getMaxPrice() != null && (source.getMaxPrice().signum() < 0
                || source.getMaxPrice().scale() > 2 || source.getMaxPrice().compareTo(new java.math.BigDecimal("99999999.99")) > 0)) {
            throw invalid("maxPrice must be a nonnegative INR amount with at most two decimals and ten digits");
        }
        filters.setMaxPrice(source.getMaxPrice());
        filters.setDietaryTags(values(source.getDietaryTags(), TAGS, "dietaryTags"));
        filters.setCategories(values(source.getCategories(), CATEGORIES, "categories"));
        if (filters.getDietaryTags().contains("NON_VEGETARIAN") && filters.getDietaryTags().size() > 1) {
            throw invalid("Contradictory dietary tags");
        }
        Integer min = source.getMinSpiceLevel();
        Integer max = source.getMaxSpiceLevel();
        if (min != null && (min < 0 || min > 5) || max != null && (max < 0 || max > 5)
                || min != null && max != null && min > max) {
            throw invalid("Spice bounds must be within 0-5 and minimum must not exceed maximum");
        }
        if (source.getAvailableOnly() == null) {
            throw invalid("availableOnly cannot be null");
        }
        filters.setMinSpiceLevel(min);
        filters.setMaxSpiceLevel(max);
        filters.setAvailableOnly(source.getAvailableOnly());
        result.setFilters(filters);
        return result;
    }

    private static String query(String text) {
        if (text == null || text.isBlank() || text.strip().length() > 1000) {
            throw invalid("Queries must contain 1-1000 characters after trimming");
        }
        return text.strip();
    }

    private static List<String> values(List<String> values, Set<String> allowed, String field) {
        if (values == null || values.size() > allowed.size()
                || values.stream().anyMatch(value -> value == null || !allowed.contains(value))) {
            throw invalid("Invalid " + field);
        }
        return values.stream().distinct().sorted().toList();
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RETRIEVAL_REQUEST", message);
    }
}
