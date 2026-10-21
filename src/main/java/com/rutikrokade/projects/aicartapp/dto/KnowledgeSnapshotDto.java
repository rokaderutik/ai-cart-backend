package com.rutikrokade.projects.aicartapp.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Value;

@Value
public class KnowledgeSnapshotDto {

    long revision;

    String embeddingFingerprint;

    String sourceHash;

    Map<UUID, StoredKnowledgeDocumentDto> documents;

    List<Map<String, Object>> menu;
}
