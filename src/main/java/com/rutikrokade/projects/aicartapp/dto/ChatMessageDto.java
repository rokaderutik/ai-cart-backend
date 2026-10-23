package com.rutikrokade.projects.aicartapp.dto;

import java.time.Instant;
import java.util.*;
import lombok.Data;

@Data
public class ChatMessageDto {

    private Long id;

    private UUID requestId;

    private String role;

    private String content;

    private Instant createdAt;

    private String requestStatus;

    private Map<String, Object> metadata = new LinkedHashMap<>();
}
