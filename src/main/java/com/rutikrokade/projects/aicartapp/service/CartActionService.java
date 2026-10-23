package com.rutikrokade.projects.aicartapp.service;

import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.util.ChatCallBudget;

public interface CartActionService {

    ChatResponseDto handle(String session, String message, ChatPlanDto plan, ChatTurnDto turn, CartDto cart,
                           ChatResponseDto response, ChatContextDto context, ChatCallBudget budget, long start);
}
