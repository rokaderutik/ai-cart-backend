package com.rutikrokade.projects.aicartapp.dto;

import java.util.List;
import java.util.UUID;
import lombok.Value;

@Value
public class IngestionPlanDto {

    KnowledgeSourceDto source;

    KnowledgeSnapshotDto snapshot;

    String embeddingFingerprint;

    List<KnowledgeDocumentDto> toEmbed;

    List<KnowledgeDocumentDto> metadataOnly;

    List<UUID> toDelete;

    List<String> toRetire;

    int menuInserted;

    int menuUpdated;

    int skipped;

    public boolean hasChanges() {
        return menuInserted + menuUpdated + toEmbed.size() + metadataOnly.size()
                + toDelete.size() + toRetire.size() > 0
                || !source.getSourceHash().equals(snapshot.getSourceHash())
                || !embeddingFingerprint.equals(snapshot.getEmbeddingFingerprint());
    }
}
