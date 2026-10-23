package com.rutikrokade.projects.aicartapp.dto;

import java.util.*;
import lombok.Data;

@Data
public class ChatAnswerSectionDto {

    private String text;

    private List<String> sourceIds = new ArrayList<>();
}
