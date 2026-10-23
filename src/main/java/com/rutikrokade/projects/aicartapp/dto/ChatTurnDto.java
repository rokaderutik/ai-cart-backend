package com.rutikrokade.projects.aicartapp.dto;

import java.util.*;
import lombok.Data;

@Data
public class ChatTurnDto {

    private UUID requestId;

    private UUID attemptToken;

    private ChatContextDto context;

    private List<ChatMessageDto> history = new ArrayList<>();

    private List<Map<String, Object>> catalog = new ArrayList<>();

    private ChatResponseDto replay;
}
