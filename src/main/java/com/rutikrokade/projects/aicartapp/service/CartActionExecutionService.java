package com.rutikrokade.projects.aicartapp.service;

import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.util.ChatCallBudget;

public interface CartActionExecutionService {

    ChatResponseDto execute(String session, ChatTurnDto turn, PreparedCartActionDto action,
                            ChatResponseDto response, ChatContextDto context, ChatCallBudget budget, long start);
}
