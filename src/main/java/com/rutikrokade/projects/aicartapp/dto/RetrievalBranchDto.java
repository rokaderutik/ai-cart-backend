package com.rutikrokade.projects.aicartapp.dto;

import java.util.List;
import lombok.Value;

@Value
public class RetrievalBranchDto {

    public enum Outcome { EVIDENCE_FOUND, NO_ELIGIBLE_ITEMS, NO_RELEVANT_EVIDENCE }

    Outcome outcome;

    int eligibleCount;

    double threshold;

    List<RetrievedDocumentDto> documents;
}
