package com.rutikrokade.projects.aicartapp.service;

import com.rutikrokade.projects.aicartapp.dto.*;

public interface KnowledgeIngestionService {

    IngestionPlanDto plan(String mode);

    IngestionSummaryDto ingest(String mode);
}
