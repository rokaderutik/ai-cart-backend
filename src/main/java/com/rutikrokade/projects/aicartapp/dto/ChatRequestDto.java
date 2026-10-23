package com.rutikrokade.projects.aicartapp.dto;

import java.util.UUID;
import lombok.Data;

@Data
public class ChatRequestDto {

    private UUID requestId;

    private String message;
}
