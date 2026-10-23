package com.rutikrokade.projects.aicartapp.dto;

import java.util.*;
import lombok.Data;

@Data
public class ChatAnswerDraftDto {

    private List<ChatAnswerSectionDto> sections = new ArrayList<>();

    private List<Long> recommendationIds = new ArrayList<>();

    private boolean informationMissing;
}
