package com.rutikrokade.projects.aicartapp.dto;

import java.util.List;
import lombok.Data;

@Data
public class RetrievalEvaluationCaseDto {

    private String id;

    private String group;

    private RetrievalRequestDto request;

    private List<String> menuSources = List.of();

    private List<String> guideSources = List.of();

    private String menuOutcome;

    private String guideOutcome;

    private boolean unrelated;

    private boolean factSupported = true;

    private String note;
}
