package com.rutikrokade.projects.aicartapp.util;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.rutikrokade.projects.aicartapp.config.RetrievalProperties;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.repository.KnowledgeIndexRepository;
import com.rutikrokade.projects.aicartapp.service.RetrievalService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RetrievalEvaluationRunnerTests {

    @TempDir
    Path directory;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final RetrievalService service = mock(RetrievalService.class);
    private final KnowledgeIndexRepository knowledge = mock(KnowledgeIndexRepository.class);
    private final RetrievalProperties properties = new RetrievalProperties();
    private RetrievalEvaluationRunner runner;

    @BeforeEach
    void setup() throws Exception {
        properties.setEvaluationReport(directory.resolve("report.json"));
        runner = new RetrievalEvaluationRunner(service, properties, knowledge, json);
        List<RetrievalEvaluationCaseDto> cases = json.readValue(new ClassPathResource("retrieval-evaluation/development.json")
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                json.getTypeFactory().constructCollectionType(List.class, RetrievalEvaluationCaseDto.class));
        assertThat(cases).hasSize(30);
        Map<String, RetrievalMenuItemDto> items = new HashMap<>();
        Map<UUID, StoredKnowledgeDocumentDto> docs = new HashMap<>();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var sources = new KnowledgeSourceReader(factory.getValidator()).read(new ClassPathResource("knowledge/menu.json"),
                    new ClassPathResource("knowledge/restaurant-guide.md"));
            for (var item : sources.getMenu()) {
                items.put("menu:" + item.getSourceKey(), new RetrievalMenuItemDto((long) items.size() + 1,
                        item.getName(), item.getDescription(), item.getCategory(), item.getIngredients(), item.getDietaryTags(),
                        item.getSpiceLevel(), item.getPrice(), item.getCurrency(), item.getAvailable()));
            }
            sources.getDocuments().forEach(doc -> docs.put(doc.getId(), new StoredKnowledgeDocumentDto(doc.getId(),
                    doc.getContent(), Map.of("sourceId", doc.getSourceId()))));
        }
        when(knowledge.snapshot()).thenReturn(new KnowledgeSnapshotDto(1, "test", "source", docs, List.of()));
        AtomicInteger index = new AtomicInteger();
        when(service.retrieve(any())).thenAnswer(invocation -> {
            assertThat(properties.getMenuThreshold()).isZero();
            assertThat(properties.getGuideThreshold()).isZero();
            var label = cases.get(index.getAndIncrement());
            var response = new RetrievalResponseDto();
            response.setCorpusRevision(1);
            response.setEmbeddingFingerprint("test");
            if (label.getRequest().getTarget() != RetrievalRequestDto.Target.GUIDE) {
                response.setMenu(branch(label.getMenuSources(), label.getMenuOutcome(), "MENU", items));
            }
            if (label.getRequest().getTarget() != RetrievalRequestDto.Target.MENU) {
                response.setGuide(branch(label.getGuideSources(), label.getGuideOutcome(), "GUIDE", items));
            }
            return response;
        });
    }

    @Test
    void writesReproducibleReportAndRestoresServingThresholds() throws Exception {
        runner.run(new DefaultApplicationArguments());
        var report = json.readTree(Files.readString(properties.getEvaluationReport()));
        assertThat(report.get("qualityGatePassed").asBoolean()).isTrue();
        assertThat(report.get("cases").size()).isEqualTo(30);
        assertThat(report.get("mixedCasesCovered").asInt()).isEqualTo(4);
        assertThat(report.get("configuredMixedCasesCovered").asInt()).isEqualTo(4);
        assertThat(properties.getMenuThreshold()).isEqualTo(.69);
        assertThat(properties.getGuideThreshold()).isEqualTo(.61);
        verify(service, times(30)).retrieve(any());
    }

    @Test
    void failsWhenCalibratedSettingsPassButConfiguredSettingsFail() throws Exception {
        properties.setMenuThreshold(.95);
        properties.setGuideThreshold(.95);
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments())).hasMessageContaining("quality gate incomplete");
        var report = json.readTree(Files.readString(properties.getEvaluationReport()));
        assertThat(report.get("calibratedQualityGatePassed").asBoolean()).isTrue();
        assertThat(report.get("configuredQualityGatePassed").asBoolean()).isFalse();
        assertThat(report.get("qualityGatePassed").asBoolean()).isFalse();
        assertThat(properties.getMenuThreshold()).isEqualTo(.95);
    }

    @Test
    void failureRestoresConfigurationAndDoesNotWriteSuccessReport() {
        doThrow(new IllegalStateException("provider unavailable")).when(service).retrieve(any());
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments())).hasMessage("provider unavailable");
        assertThat(properties.getMenuThreshold()).isEqualTo(.69);
        assertThat(properties.getGuideThreshold()).isEqualTo(.61);
        assertThat(properties.getEvaluationReport()).doesNotExist();
    }

    @Test
    void refusesToEvaluateAcrossDifferentPublications() {
        var response = new RetrievalResponseDto();
        response.setCorpusRevision(2);
        doReturn(response).when(service).retrieve(any());
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments())).hasMessageContaining("Knowledge changed");
        assertThat(properties.getMenuThreshold()).isEqualTo(.69);
        assertThat(properties.getEvaluationReport()).doesNotExist();
    }

    private RetrievalBranchDto branch(List<String> sources, String outcome, String type, Map<String, RetrievalMenuItemDto> items) {
        List<RetrievedDocumentDto> documents = sources.stream().map(source -> new RetrievedDocumentDto(UUID.randomUUID(),
                source, type, source, .8, "content", "snippet", Map.of(), items.get(source))).toList();
        return new RetrievalBranchDto(RetrievalBranchDto.Outcome.valueOf(outcome),
                outcome.equals("NO_ELIGIBLE_ITEMS") ? 0 : 10, 0, documents);
    }
}
