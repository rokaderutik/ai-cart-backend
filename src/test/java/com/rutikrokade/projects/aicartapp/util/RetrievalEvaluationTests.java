package com.rutikrokade.projects.aicartapp.util;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import org.junit.jupiter.api.Test;

class RetrievalEvaluationTests {

    @Test
    void calibrationRejectsNegativesAndUsesHigherThresholdForRecallTie() {
        var positive = label(false, List.of("menu:rice"));
        var negative = label(true, List.of());
        var cases = List.of(positive, negative);
        var responses = List.of(response(.75), response(.60));
        assertThat(RetrievalEvaluation.selectThreshold(cases, responses, true)).isEqualTo(.75);
        var metrics = RetrievalEvaluation.metrics(cases, responses, true, .75);
        assertThat(metrics.get("recallAt5")).isEqualTo(1.0);
        assertThat(metrics.get("unrelatedFalsePositives")).isEqualTo(0);
        assertThat(RetrievalEvaluation.metrics(cases, responses, true, .60).get("unrelatedFalsePositives")).isEqualTo(1);
    }

    @Test
    void missingFactCanHaveRelevantEvidence() {
        var label = label(false, List.of("menu:rice"));
        label.setFactSupported(false);
        assertThat(RetrievalEvaluation.metrics(List.of(label), List.of(response(.8)), true, .7).get("recallAt5"))
                .isEqualTo(1.0);
    }

    private RetrievalEvaluationCaseDto label(boolean negative, List<String> sources) {
        var label = new RetrievalEvaluationCaseDto();
        var request = new RetrievalRequestDto();
        request.setTarget(RetrievalRequestDto.Target.MENU);
        request.setQuery("query");
        label.setRequest(request);
        label.setUnrelated(negative);
        label.setMenuSources(sources);
        return label;
    }

    private RetrievalResponseDto response(double score) {
        var response = new RetrievalResponseDto();
        response.setMenu(new RetrievalBranchDto(RetrievalBranchDto.Outcome.EVIDENCE_FOUND, 1, 0,
                List.of(new RetrievedDocumentDto(UUID.randomUUID(), "menu:rice", "MENU", "Rice", score,
                        "Rice", "Rice", Map.of(), null))));
        return response;
    }
}
