package com.rutikrokade.projects.aicartapp.util;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.rutikrokade.projects.aicartapp.config.RetrievalProperties;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.repository.KnowledgeIndexRepository;
import com.rutikrokade.projects.aicartapp.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("retrieval-eval")
@RequiredArgsConstructor
@Slf4j
public class RetrievalEvaluationRunner implements ApplicationRunner {

    private final RetrievalService service;
    private final RetrievalProperties properties;
    private final KnowledgeIndexRepository knowledge;
    private final ObjectMapper json;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Intentionally no input-file override: this runner never consumes the Phase 8 held-out set.
        var resource = new ClassPathResource("retrieval-evaluation/development.json");
        String dataset = resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        List<RetrievalEvaluationCaseDto> cases = json.readValue(dataset,
                json.getTypeFactory().constructCollectionType(List.class, RetrievalEvaluationCaseDto.class));
        var snapshot = knowledge.snapshot();
        Set<String> published = new HashSet<>();
        snapshot.getDocuments().values().forEach(doc -> published.add((String) doc.getMetadata().get("sourceId")));
        if (cases.size() != 30 || cases.stream().map(RetrievalEvaluationCaseDto::getId).distinct().count() != 30) {
            throw new IllegalStateException("Expected 30 uniquely labeled development cases");
        }
        for (var label : cases) {
            RetrievalValidation.normalize(label.getRequest());
            if (!published.containsAll(label.getMenuSources()) || !published.containsAll(label.getGuideSources())) {
                throw new IllegalStateException("Evaluation labels refer to unpublished sources: " + label.getId());
            }
        }
        List<RetrievalResponseDto> responses = new ArrayList<>();
        double originalMenu = properties.getMenuThreshold(), originalGuide = properties.getGuideThreshold();
        try {
            // Capture candidates once; calibration never sends the same request for every threshold.
            properties.setMenuThreshold(0);
            properties.setGuideThreshold(0);
            for (var label : cases) {
                log.info("Retrieval evaluation case {} ({}/{})", label.getId(), responses.size() + 1, cases.size());
                var response = service.retrieve(label.getRequest());
                if (response.getCorpusRevision() != snapshot.getRevision()
                        || !response.getEmbeddingFingerprint().equals(snapshot.getEmbeddingFingerprint())) {
                    throw new IllegalStateException("Knowledge changed during evaluation; repeat on a stable publication");
                }
                responses.add(response);
            }
        } finally {
            properties.setMenuThreshold(originalMenu);
            properties.setGuideThreshold(originalGuide);
        }
        double menuThreshold = RetrievalEvaluation.selectThreshold(cases, responses, true);
        double guideThreshold = RetrievalEvaluation.selectThreshold(cases, responses, false);
        var menu = RetrievalEvaluation.metrics(cases, responses, true, menuThreshold);
        var guide = RetrievalEvaluation.metrics(cases, responses, false, guideThreshold);
        int mixed = 0, covered = 0, configuredCovered = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            var label = cases.get(i);
            var response = responses.get(i);
            var menuIds = RetrievalEvaluation.accepted(response.getMenu(), menuThreshold).stream().map(RetrievedDocumentDto::getSourceId).toList();
            var guideIds = RetrievalEvaluation.accepted(response.getGuide(), guideThreshold).stream().map(RetrievedDocumentDto::getSourceId).toList();
            if (label.getGroup().equals("mixed")) {
                mixed++;
                if (menuIds.containsAll(label.getMenuSources()) && guideIds.containsAll(label.getGuideSources())
                        && Objects.equals(label.getMenuOutcome(), RetrievalEvaluation.outcome(response.getMenu(), menuThreshold))
                        && Objects.equals(label.getGuideOutcome(), RetrievalEvaluation.outcome(response.getGuide(), guideThreshold))) covered++;
                if (covers(label, response, originalMenu, originalGuide)) configuredCovered++;
            }
            details.add(Map.of("label", label, "rawCandidates", response, "acceptedMenuSources", menuIds,
                    "acceptedGuideSources", guideIds, "menuOutcome", RetrievalEvaluation.outcome(response.getMenu(), menuThreshold),
                    "guideOutcome", RetrievalEvaluation.outcome(response.getGuide(), guideThreshold)));
        }
        var configuredMenu = RetrievalEvaluation.metrics(cases, responses, true, originalMenu);
        var configuredGuide = RetrievalEvaluation.metrics(cases, responses, false, originalGuide);
        boolean calibratedPass = passes(menu) && passes(guide) && mixed == covered;
        boolean configuredPass = passes(configuredMenu) && passes(configuredGuide) && mixed == configuredCovered;
        boolean passed = calibratedPass && configuredPass;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("datasetHash", KnowledgeDocuments.hash(dataset));
        report.put("sourceHash", snapshot.getSourceHash());
        report.put("corpusRevision", snapshot.getRevision());
        report.put("embeddingFingerprint", snapshot.getEmbeddingFingerprint());
        report.put("menu", menu);
        report.put("guide", guide);
        report.put("mixedCases", mixed);
        report.put("mixedCasesCovered", covered);
        report.put("qualityGatePassed", passed);
        report.put("calibratedQualityGatePassed", calibratedPass);
        report.put("configuredQualityGatePassed", configuredPass);
        report.put("configuredMixedCasesCovered", configuredCovered);
        report.put("configuredThresholds", Map.of("menu", originalMenu, "guide", originalGuide));
        report.put("configuredMenuMetrics", configuredMenu);
        report.put("configuredGuideMetrics", configuredGuide);
        report.put("embeddingCalls", responses.stream().mapToInt(RetrievalResponseDto::getEmbeddingCalls).sum());
        var times = responses.stream().mapToLong(RetrievalResponseDto::getTotalMillis).sorted().toArray();
        report.put("latencyMillis", Map.of("p50", times[(times.length - 1) / 2], "p95", times[(int) Math.ceil(times.length * .95) - 1], "max", times[times.length - 1]));
        report.put("cases", details);
        Path path = properties.getEvaluationReport();
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        log.info("Evaluation report {}: menu={}, guide={}, mixed={}/{}, passed={}", path, menu, guide, covered, mixed, passed);
        if (!passed) throw new IllegalStateException("Retrieval quality gate incomplete; inspect " + path);
    }

    private boolean passes(Map<String, Object> metrics) {
        return ((Number) metrics.get("recallAt5")).doubleValue() >= .8
                && (int) metrics.get("unrelatedFalsePositives") == 0
                && (int) metrics.get("constraintViolations") == 0
                && metrics.get("expectedOutcomeCount").equals(metrics.get("correctOutcomeCount"));
    }

    private boolean covers(RetrievalEvaluationCaseDto label, RetrievalResponseDto response, double menu, double guide) {
        return RetrievalEvaluation.accepted(response.getMenu(), menu).stream().map(RetrievedDocumentDto::getSourceId).toList().containsAll(label.getMenuSources())
                && RetrievalEvaluation.accepted(response.getGuide(), guide).stream().map(RetrievedDocumentDto::getSourceId).toList().containsAll(label.getGuideSources())
                && Objects.equals(label.getMenuOutcome(), RetrievalEvaluation.outcome(response.getMenu(), menu))
                && Objects.equals(label.getGuideOutcome(), RetrievalEvaluation.outcome(response.getGuide(), guide));
    }
}
