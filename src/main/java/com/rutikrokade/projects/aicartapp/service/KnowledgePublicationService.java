package com.rutikrokade.projects.aicartapp.service;

import java.util.Map;
import java.util.UUID;
import com.rutikrokade.projects.aicartapp.dto.IngestionPlanDto;

public interface KnowledgePublicationService {

    long publish(IngestionPlanDto plan, Map<UUID, float[]> vectors);
}
