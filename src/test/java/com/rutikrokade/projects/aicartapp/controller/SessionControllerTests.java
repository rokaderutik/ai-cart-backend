package com.rutikrokade.projects.aicartapp.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.rutikrokade.projects.aicartapp.dto.SessionDto;
import com.rutikrokade.projects.aicartapp.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SessionController.class, properties = "app.cors.allowed-origins=http://localhost:5173")
@ActiveProfiles("test")
class SessionControllerTests {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private SessionService sessionService;

    @Test
    void returnsCreatedSessionInGlobalEnvelope() throws Exception {
        when(sessionService.createSession()).thenReturn(new SessionDto("SESSION_example",
                Instant.parse("2026-10-20T12:00:00Z")));
        mockMvc.perform(post("/sessions"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionId").value("SESSION_example"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-10-20T12:00:00Z"))
                .andExpect(jsonPath("$.error").isEmpty());
    }

    @Test
    void endingSessionKeeps204BodyEmpty() throws Exception {
        mockMvc.perform(delete("/sessions/current").header("X-Session-Id", "SESSION_example"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(sessionService).endSession("SESSION_example");
    }
}
