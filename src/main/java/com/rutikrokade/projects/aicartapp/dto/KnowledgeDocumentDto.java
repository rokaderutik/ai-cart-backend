package com.rutikrokade.projects.aicartapp.dto;

import java.util.UUID;
import lombok.Value;

@Value
public class KnowledgeDocumentDto {

    UUID id;

    String sourceType;

    String sourceId;

    String sourcePath;

    String title;

    String content;

    String contentHash;

    String menuSourceKey;
}
