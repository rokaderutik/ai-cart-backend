package com.rutikrokade.projects.aicartapp.util;

import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;

/** Pure evaluation functions: no provider calls and no access to held-out data. */
public final class RetrievalEvaluation {

    private RetrievalEvaluation() { }

    public static double selectThreshold(List<RetrievalEvaluationCaseDto> cases,
                                          List<RetrievalResponseDto> responses, boolean menu) {
        double selected = 0.90;
        double bestRecall = -1;
        for (int step = 40; step <= 90; step++) {
            double threshold = step / 100.0;
            var metrics = metrics(cases, responses, menu, threshold);
            double recall = ((Number) metrics.get("recallAt5")).doubleValue();
            if ((int) metrics.get("unrelatedFalsePositives") == 0 && recall >= bestRecall) {
                selected = threshold;
                bestRecall = recall;
            }
        }
        return selected;
    }

    public static Map<String, Object> metrics(List<RetrievalEvaluationCaseDto> cases,
                                              List<RetrievalResponseDto> responses, boolean menu, double threshold) {
        int expected = 0, hits = 0, negatives = 0, falsePositives = 0, outcomes = 0, correct = 0, returned = 0, violations = 0;
        for (int i = 0; i < cases.size(); i++) {
            var label = cases.get(i);
            var response = responses.get(i);
            var branch = menu ? response.getMenu() : response.getGuide();
            if (branch == null) continue;
            var docs = accepted(branch, threshold);
            var sources = docs.stream().map(RetrievedDocumentDto::getSourceId).toList();
            var relevant = menu ? label.getMenuSources() : label.getGuideSources();
            expected += relevant.size();
            hits += (int) relevant.stream().filter(sources::contains).count();
            if (label.isUnrelated()) {
                negatives++;
                if (!sources.isEmpty()) falsePositives++;
            }
            String expectedOutcome = menu ? label.getMenuOutcome() : label.getGuideOutcome();
            if (expectedOutcome != null) {
                outcomes++;
                if (expectedOutcome.equals(outcome(branch, threshold))) correct++;
            }
            returned += docs.size();
            if (menu) {
                var filters = RetrievalValidation.normalize(label.getRequest()).getFilters();
                violations += (int) docs.stream().filter(doc -> !complies(doc.getMenuItem(), filters)).count();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threshold", threshold);
        result.put("relevantSourceCount", expected);
        result.put("retrievedRelevantCount", hits);
        result.put("recallAt5", expected == 0 ? 0.0 : (double) hits / expected);
        result.put("unrelatedCaseCount", negatives);
        result.put("unrelatedFalsePositives", falsePositives);
        result.put("expectedOutcomeCount", outcomes);
        result.put("correctOutcomeCount", correct);
        result.put("returnedCount", returned);
        result.put("constraintViolations", violations);
        return result;
    }

    public static List<RetrievedDocumentDto> accepted(RetrievalBranchDto branch, double threshold) {
        return branch == null ? List.of() : branch.getDocuments().stream().filter(doc -> doc.getScore() >= threshold).toList();
    }

    public static String outcome(RetrievalBranchDto branch, double threshold) {
        if (branch == null) return "NOT_REQUESTED";
        if (branch.getOutcome() == RetrievalBranchDto.Outcome.NO_ELIGIBLE_ITEMS) return "NO_ELIGIBLE_ITEMS";
        return accepted(branch, threshold).isEmpty() ? "NO_RELEVANT_EVIDENCE" : "EVIDENCE_FOUND";
    }

    public static boolean complies(RetrievalMenuItemDto item, RetrievalFiltersDto filters) {
        return item != null && (!filters.getAvailableOnly() || item.isAvailable())
                && (filters.getMaxPrice() == null || "INR".equals(item.getCurrency()) && item.getPrice().compareTo(filters.getMaxPrice()) <= 0)
                && item.getDietaryTags().containsAll(filters.getDietaryTags())
                && (filters.getCategories().isEmpty() || filters.getCategories().contains(item.getCategory()))
                && (filters.getMinSpiceLevel() == null || item.getSpiceLevel() >= filters.getMinSpiceLevel())
                && (filters.getMaxSpiceLevel() == null || item.getSpiceLevel() <= filters.getMaxSpiceLevel());
    }
}
