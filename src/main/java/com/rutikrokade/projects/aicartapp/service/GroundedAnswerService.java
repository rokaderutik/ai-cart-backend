package com.rutikrokade.projects.aicartapp.service;
import java.util.List;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.util.ChatCallBudget;
public interface GroundedAnswerService {
    ChatAnswerDraftDto answer(String message, ChatPlanDto plan, ChatTurnDto turn, CartDto cart,
                             List<RetrievedDocumentDto> evidence, RetrievalFiltersDto filters, ChatCallBudget budget);
}
