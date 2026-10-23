package com.rutikrokade.projects.aicartapp.dto;

import java.util.*;
import lombok.Data;

@Data
public class ChatResponseDto {

    private UUID requestId;

    private Long assistantMessageId;

    private String outcome;

    private String reply;

    private List<ChatAnswerSectionDto> sections = new ArrayList<>();

    private List<RetrievalMenuItemDto> recommendations = new ArrayList<>();

    private List<ChatSourceDto> sources = new ArrayList<>();

    private CartDto cart;

    private CartActionResultDto actionResult;

    private Map<String, Long> timings = new LinkedHashMap<>();

    private Map<String, Object> diagnostics = new LinkedHashMap<>();
}
