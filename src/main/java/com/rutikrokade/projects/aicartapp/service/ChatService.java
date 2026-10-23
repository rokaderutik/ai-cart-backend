package com.rutikrokade.projects.aicartapp.service;

import com.rutikrokade.projects.aicartapp.dto.*;

public interface ChatService {

    ChatResponseDto chat(String sessionId, ChatRequestDto request);
}
