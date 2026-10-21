package com.rutikrokade.projects.aicartapp.service;

import java.util.*;
import com.rutikrokade.projects.aicartapp.config.IngestionProperties;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.EmbeddingProviderException;
import com.rutikrokade.projects.aicartapp.repository.KnowledgeIndexRepository;
import com.rutikrokade.projects.aicartapp.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

@Service
@Profile("ingest")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeIngestionServiceImpl implements KnowledgeIngestionService {

    private final IngestionProperties properties;
    private final ResourceLoader resources;
    private final KnowledgeSourceReader reader;
    private final KnowledgeIndexRepository repository;
    private final RetrievalEmbeddingModel embedding;
    private final KnowledgePublicationService publication;

    @Override
    public IngestionPlanDto plan(String mode) {
        if (!Set.of("dry-run", "apply", "rebuild").contains(mode)) {
            throw new IllegalArgumentException("Ingestion mode must be dry-run, apply or rebuild");
        }
        log.info("Validating complete knowledge sources for mode {}", mode);
        KnowledgeSourceDto source = reader.read(resources.getResource(properties.getMenu()),
                resources.getResource(properties.getGuide()));
        KnowledgeSnapshotDto snapshot = repository.snapshot();
        String fingerprint = embedding.fingerprint();
        boolean incompatible = snapshot.getEmbeddingFingerprint() != null
                && !snapshot.getEmbeddingFingerprint().equals(fingerprint);
        if (incompatible && mode.equals("apply")) {
            throw new IllegalStateException("Embedding configuration changed; use explicit rebuild mode");
        }
        Map<String, Map<String, Object>> managed = new HashMap<>();
        Set<String> unmanagedNames = new HashSet<>();
        snapshot.getMenu().forEach(row -> {
            if (row.get("source_key") == null) {
                unmanagedNames.add(KnowledgeDocuments.normalizedName((String) row.get("name")));
            } else {
                managed.put((String) row.get("source_key"), row);
            }
        });
        int inserted = 0;
        int updated = 0;
        Set<String> keys = new HashSet<>();
        for (SourceMenuItemDto item : source.getMenu()) {
            if (unmanagedNames.contains(KnowledgeDocuments.normalizedName(item.getName()))) {
                throw new IllegalArgumentException("Dish conflicts with an unmanaged database row: " + item.getSourceKey());
            }
            keys.add(item.getSourceKey());
            var old = managed.get(item.getSourceKey());
            if (old == null) {
                inserted++;
            } else if (!KnowledgeIndexRepository.matchesMenu(item, old)) {
                updated++;
            }
        }
        List<String> retire = managed.entrySet().stream()
                .filter(e -> !keys.contains(e.getKey()) && (Boolean.TRUE.equals(e.getValue().get("source_active"))
                        || Boolean.TRUE.equals(e.getValue().get("available"))))
                .map(Map.Entry::getKey).sorted().toList();
        List<KnowledgeDocumentDto> changed = new ArrayList<>();
        List<KnowledgeDocumentDto> metadataOnly = new ArrayList<>();
        Set<UUID> desiredIds = new HashSet<>();
        int skipped = 0;
        for (KnowledgeDocumentDto doc : source.getDocuments()) {
            desiredIds.add(doc.getId());
            var old = snapshot.getDocuments().get(doc.getId());
            if (mode.equals("rebuild") || incompatible || old == null || !doc.getContent().equals(old.getContent())
                    || !doc.getContentHash().equals(old.getMetadata().get("contentHash"))
                    || !fingerprint.equals(old.getMetadata().get("embeddingFingerprint"))) {
                changed.add(doc);
            } else {
                var row = managed.get(doc.getMenuSourceKey());
                Long itemId = row == null ? null : ((Number) row.get("id")).longValue();
                var expected = KnowledgeDocuments.metadata(doc, fingerprint, itemId);
                // Jackson may deserialize small numeric IDs as Integer instead of Long.
                Map<String, Object> previous = new HashMap<>(old.getMetadata());
                if (previous.get("itemId") instanceof Number number) {
                    previous.put("itemId", number.longValue());
                }
                if (!expected.equals(previous) || doc.getMenuSourceKey() != null && row == null) {
                    metadataOnly.add(doc);
                } else {
                    skipped++;
                }
            }
        }
        List<UUID> deleted = snapshot.getDocuments().keySet().stream().filter(id -> !desiredIds.contains(id)).sorted().toList();
        log.info("Ingestion preview: menu +{} ~{} retired {}; embeddings {}, unchanged {}, metadata {}, deleted {}",
                inserted, updated, retire.size(), changed.size(), skipped, metadataOnly.size(), deleted.size());
        return new IngestionPlanDto(source, snapshot, fingerprint, changed, metadataOnly, deleted, retire,
                inserted, updated, skipped);
    }

    @Override
    public IngestionSummaryDto ingest(String mode) {
        long start = System.nanoTime();
        IngestionPlanDto plan = plan(mode);
        return execute(mode, plan, start);
    }

    private IngestionSummaryDto execute(String mode, IngestionPlanDto plan, long start) {
        Map<UUID, float[]> vectors = new HashMap<>();
        if (!mode.equals("dry-run")) {
            for (KnowledgeDocumentDto doc : plan.getToEmbed()) {
                log.info("Embedding source {} ({}/{})", doc.getSourceId(), vectors.size() + 1, plan.getToEmbed().size());
                float[] vector;
                try {
                    vector = embedding.embed(new Document(doc.getId().toString(), doc.getContent(), Map.of()));
                    VectorValidation.requireValid(vector, 768);
                } catch (RuntimeException failure) {
                    if (failure instanceof EmbeddingProviderException providerFailure) {
                        log.warn("Ingestion stopped at source {}: {}", doc.getSourceId(), providerFailure.getMessage());
                        throw new IllegalStateException("Embedding failed for " + doc.getSourceId() + ": "
                                + providerFailure.getMessage() + "; nothing published", providerFailure);
                    }
                    throw new IllegalStateException("Embedding failed for " + doc.getSourceId() + "; nothing published");
                }
                vectors.put(doc.getId(), vector);
            }
        }
        long revision = mode.equals("dry-run") ? plan.getSnapshot().getRevision() : publication.publish(plan, vectors);
        var summary = new IngestionSummaryDto(mode, revision, plan.getMenuInserted(), plan.getMenuUpdated(),
                plan.getToRetire().size(), vectors.size(), plan.getSkipped(), plan.getToDelete().size(),
                plan.getMetadataOnly().size(), (System.nanoTime() - start) / 1_000_000);
        log.info("Knowledge ingestion completed: {}", summary);
        return summary;
    }
}
