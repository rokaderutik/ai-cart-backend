package com.rutikrokade.projects.aicartapp.dto;

import java.util.Map;
import java.util.UUID;
import lombok.Value;

@Value
public class RetrievedDocumentDto {

    UUID documentId;

    String sourceId;

    String sourceType;

    String title;

    double score;

    String content;

    String snippet;

    Map<String, Object> metadata;

    RetrievalMenuItemDto menuItem;
}
