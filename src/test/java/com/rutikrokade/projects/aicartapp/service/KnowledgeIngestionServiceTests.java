package com.rutikrokade.projects.aicartapp.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.rutikrokade.projects.aicartapp.config.IngestionProperties;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.repository.KnowledgeIndexRepository;
import com.rutikrokade.projects.aicartapp.util.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.ai.document.Document;
import org.springframework.core.io.*;

class KnowledgeIngestionServiceTests {

    private final KnowledgeIndexRepository repository = mock(KnowledgeIndexRepository.class);
    private final KnowledgeSourceReader reader = mock(KnowledgeSourceReader.class);
    private final RetrievalEmbeddingModel embedding = mock(RetrievalEmbeddingModel.class);
    private final KnowledgePublicationService publication = mock(KnowledgePublicationService.class);
    private final KnowledgeIngestionService service = new KnowledgeIngestionServiceImpl(new IngestionProperties(),
            new DefaultResourceLoader(), reader, repository, embedding, publication);
    private KnowledgeSourceDto source;

    @BeforeEach
    void setup() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            source = new KnowledgeSourceReader(factory.getValidator()).read(
                    new ClassPathResource("knowledge/menu.json"), new ClassPathResource("knowledge/restaurant-guide.md"));
        }
        when(reader.read(any(), any())).thenAnswer(invocation -> source);
        when(embedding.fingerprint()).thenReturn("test-fingerprint");
        when(repository.snapshot()).thenReturn(new KnowledgeSnapshotDto(0, null, null, Map.of(), List.of()));
        when(publication.publish(any(), any())).thenReturn(1L);
        float[] vector = new float[768];
        vector[0] = 1;
        when(embedding.embed(any(Document.class))).thenReturn(vector);
    }

    private KnowledgeSnapshotDto published() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Long> ids = new HashMap<>();
        for (SourceMenuItemDto item : source.getMenu()) {
            long id = rows.size() + 1;
            ids.put(item.getSourceKey(), id);
            Map<String, Object> row = new HashMap<>();
            row.put("id", id);
            row.put("source_key", item.getSourceKey());
            row.put("source_active", true);
            row.put("name", item.getName());
            row.put("description", item.getDescription());
            row.put("category", item.getCategory());
            row.put("ingredients", item.getIngredients());
            row.put("dietary_tags", item.getDietaryTags());
            row.put("spice_level", item.getSpiceLevel());
            row.put("price", item.getPrice());
            row.put("currency", item.getCurrency());
            row.put("available", item.getAvailable());
            rows.add(row);
        }
        Map<UUID, StoredKnowledgeDocumentDto> docs = new HashMap<>();
        source.getDocuments().forEach(d -> docs.put(d.getId(), new StoredKnowledgeDocumentDto(d.getId(),
                d.getContent(), KnowledgeDocuments.metadata(d, "test-fingerprint", ids.get(d.getMenuSourceKey())))));
        return new KnowledgeSnapshotDto(1, "test-fingerprint", source.getSourceHash(), docs, rows);
    }

    @Test
    void dryRunValidatesWithoutEmbeddingOrPublishing() {
        var result = service.ingest("dry-run");
        assertThat(result.getDocumentsEmbedded()).isZero();
        assertThat(result.getMenuInserted()).isEqualTo(40);
        verify(embedding, never()).embed(any(Document.class));
        verifyNoInteractions(publication);
    }

    @Test
    void firstRunEmbedsAllDocumentsAndSecondRunEmbedsNone() {
        assertThat(service.ingest("apply").getDocumentsEmbedded()).isEqualTo(50);
        verify(embedding, times(50)).embed(any(Document.class));
        when(repository.snapshot()).thenReturn(published());
        clearInvocations(embedding);
        var second = service.ingest("apply");
        assertThat(second.getDocumentsEmbedded()).isZero();
        assertThat(second.getDocumentsSkipped()).isEqualTo(50);
        assertThat(service.plan("apply").hasChanges()).isFalse();
        verify(embedding, never()).embed(any(Document.class));
    }

    @Test
    void changedDocumentAndMissingVectorEachRequireOnlyOneEmbedding() {
        var old = published();
        var first = source.getDocuments().getFirst();
        old.getDocuments().put(first.getId(), new StoredKnowledgeDocumentDto(first.getId(), "Old content",
                old.getDocuments().get(first.getId()).getMetadata()));
        when(repository.snapshot()).thenReturn(old);
        assertThat(service.ingest("apply").getDocumentsEmbedded()).isEqualTo(1);
        old.getDocuments().remove(first.getId());
        assertThat(service.plan("apply").getToEmbed()).hasSize(1);
    }

    @Test
    void priceAndAvailabilityChangesOnlyUpdateMenu() {
        var old = published();
        old.getMenu().getFirst().put("price", new java.math.BigDecimal("999.00"));
        old.getMenu().getFirst().put("available", false);
        when(repository.snapshot()).thenReturn(old);
        var result = service.ingest("apply");
        assertThat(result.getMenuUpdated()).isEqualTo(1);
        assertThat(result.getDocumentsEmbedded()).isZero();
    }

    @Test
    void metadataChangesDoNotGenerateEmbeddings() {
        var old = published();
        old.getDocuments().values().iterator().next().getMetadata().put("sourcePath", "legacy.json");
        when(repository.snapshot()).thenReturn(old);
        assertThat(service.plan("apply").getMetadataOnly()).hasSize(1);
        assertThat(service.ingest("apply").getDocumentsEmbedded()).isZero();
    }

    @Test
    void providerFailureAndInvalidVectorPreventPublication() {
        when(embedding.embed(any(Document.class))).thenThrow(new IllegalStateException("provider failure"));
        assertThatThrownBy(() -> service.ingest("apply")).hasMessageContaining("nothing published");
        verifyNoInteractions(publication);
        when(embedding.embed(any(Document.class))).thenReturn(new float[768]);
        assertThatThrownBy(() -> service.ingest("apply")).hasMessageContaining("nothing published");
        verifyNoInteractions(publication);
    }

    @Test
    void safeProviderStatusSurvivesTheIngestionErrorHandler() {
        var providerFailure = new com.rutikrokade.projects.aicartapp.exception.EmbeddingProviderException(
                new com.google.genai.errors.ApiException(403, "PERMISSION_DENIED", "private provider response"));
        when(embedding.embed(any(Document.class))).thenThrow(providerFailure);
        assertThatThrownBy(() -> service.ingest("apply"))
                .hasMessageContaining("guide:allergens").hasMessageContaining("HTTP 403")
                .hasMessageContaining("permission denied").hasMessageContaining("nothing published")
                .hasMessageNotContaining("private provider response");
        verifyNoInteractions(publication);
    }

    @Test
    void modelMismatchRequiresRebuildWhichReembedsEveryDocument() {
        var old = published();
        when(repository.snapshot()).thenReturn(new KnowledgeSnapshotDto(1, "old-model", old.getSourceHash(),
                old.getDocuments(), old.getMenu()));
        assertThatThrownBy(() -> service.ingest("apply")).hasMessageContaining("rebuild");
        assertThat(service.plan("dry-run").getToEmbed()).hasSize(50);
        assertThat(service.ingest("rebuild").getDocumentsEmbedded()).isEqualTo(50);
    }

    @Test
    void rejectsUnmanagedNameConflictAndInvalidModeBeforeProviderCalls() {
        var row = new HashMap<String, Object>();
        row.put("name", source.getMenu().getFirst().getName());
        when(repository.snapshot()).thenReturn(new KnowledgeSnapshotDto(0, null, null, Map.of(), List.of(row)));
        assertThatThrownBy(() -> service.ingest("apply")).hasMessageContaining("unmanaged");
        assertThatThrownBy(() -> service.ingest("invalid")).hasMessageContaining("mode");
        verifyNoInteractions(publication);
        verify(embedding, never()).embed(any(Document.class));
    }
}
