package com.rutikrokade.projects.aicartapp.service;

import com.rutikrokade.projects.aicartapp.dto.SessionDto;
import com.rutikrokade.projects.aicartapp.entity.GuestSession;

public interface SessionService {

    SessionDto createSession();

    GuestSession requireActiveSession(String sessionId);

    GuestSession lockActiveSession(String sessionId);

    void endSession(String sessionId);
}
