package com.rutikrokade.projects.aicartapp.dto;

import java.util.Map;
import java.util.UUID;
import lombok.Value;

@Value
public class StoredKnowledgeDocumentDto {

    UUID id;

    String content;

    Map<String, Object> metadata;
}
