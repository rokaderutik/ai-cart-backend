package com.rutikrokade.projects.aicartapp.dto;

import java.util.*;
import lombok.Data;

@Data
public class ChatHistoryDto {

    private List<ChatMessageDto> messages = new ArrayList<>();

    private Long nextBefore;
}
