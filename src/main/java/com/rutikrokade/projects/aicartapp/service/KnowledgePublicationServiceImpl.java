package com.rutikrokade.projects.aicartapp.service;

import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.repository.KnowledgeIndexRepository;
import com.rutikrokade.projects.aicartapp.util.KnowledgeDocuments;
import com.rutikrokade.projects.aicartapp.util.VectorValidation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgePublicationServiceImpl implements KnowledgePublicationService {

    private final KnowledgeIndexRepository repository;

    @Override
    @Transactional
    public long publish(IngestionPlanDto plan, Map<UUID, float[]> vectors) {
        long revision = repository.lockRevision();
        if (revision != plan.getSnapshot().getRevision()) {
            throw new IllegalStateException("Another ingestion published changes; rerun against the new revision");
        }
        if (!plan.hasChanges()) {
            log.info("Knowledge revision {} is unchanged", revision);
            return revision;
        }
        Map<String, Long> ids = new HashMap<>();
        Map<String, Map<String, Object>> existing = new HashMap<>();
        plan.getSnapshot().getMenu().forEach(row -> {
            if (row.get("source_key") != null) {
                existing.put((String) row.get("source_key"), row);
            }
        });
        for (SourceMenuItemDto item : plan.getSource().getMenu()) {
            var old = existing.get(item.getSourceKey());
            Long id = old != null && KnowledgeIndexRepository.matchesMenu(item, old)
                    ? ((Number) old.get("id")).longValue() : repository.upsertMenu(item);
            ids.put(item.getSourceKey(), id);
        }
        for (KnowledgeDocumentDto doc : plan.getToEmbed()) {
            float[] vector = vectors.get(doc.getId());
            VectorValidation.requireValid(vector, 768);
            repository.upsertVector(doc, KnowledgeDocuments.metadata(doc, plan.getEmbeddingFingerprint(),
                    ids.get(doc.getMenuSourceKey())), vector);
        }
        for (KnowledgeDocumentDto doc : plan.getMetadataOnly()) {
            repository.updateMetadata(doc, KnowledgeDocuments.metadata(doc, plan.getEmbeddingFingerprint(),
                    ids.get(doc.getMenuSourceKey())));
        }
        plan.getToRetire().forEach(repository::retire);
        plan.getToDelete().forEach(repository::deleteVector);
        repository.publishState(plan.getEmbeddingFingerprint(), plan.getSource().getSourceHash());
        log.info("Prepared atomic knowledge publication: revision {}, embedded {}, retired {}, deleted {}",
                revision + 1, vectors.size(), plan.getToRetire().size(), plan.getToDelete().size());
        return revision + 1;
    }
}
