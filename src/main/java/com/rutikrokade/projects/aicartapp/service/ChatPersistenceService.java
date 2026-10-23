package com.rutikrokade.projects.aicartapp.service;

import java.util.UUID;
import com.rutikrokade.projects.aicartapp.dto.*;

public interface ChatPersistenceService {

    ChatTurnDto begin(String session, ChatRequestDto request);

    ChatResponseDto complete(String session, ChatTurnDto turn, ChatResponseDto response, ChatContextDto context);

    void requireAttempt(String session, ChatTurnDto turn);

    void fail(String session, ChatTurnDto turn, String code);

    ChatHistoryDto history(String session, Long before, int limit);
}
