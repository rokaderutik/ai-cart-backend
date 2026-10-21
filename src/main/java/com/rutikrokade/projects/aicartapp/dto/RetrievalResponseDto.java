package com.rutikrokade.projects.aicartapp.dto;

import lombok.Data;

@Data
public class RetrievalResponseDto {

    public enum Status { EVIDENCE_FOUND, PARTIAL_EVIDENCE, NO_EVIDENCE }

    private String requestId;

    private Status status;

    private RetrievalFiltersDto appliedFilters;

    private RetrievalBranchDto menu;

    private RetrievalBranchDto guide;

    private long corpusRevision;

    private String embeddingFingerprint;

    private int embeddingCalls;

    private long embeddingMillis;

    private long databaseMillis;

    private long totalMillis;
}
