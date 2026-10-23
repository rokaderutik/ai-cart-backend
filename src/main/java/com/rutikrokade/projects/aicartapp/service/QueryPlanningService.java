package com.rutikrokade.projects.aicartapp.service;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.util.ChatCallBudget;
public interface QueryPlanningService {
    ChatPlanDto plan(String message, ChatTurnDto turn, CartDto cart, ChatCallBudget budget);
}
