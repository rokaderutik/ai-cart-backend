package com.rutikrokade.projects.aicartapp.dto;

import java.util.List;
import lombok.Value;

@Value
public class KnowledgeSourceDto {

    List<SourceMenuItemDto> menu;

    List<KnowledgeDocumentDto> documents;

    String sourceHash;
}
