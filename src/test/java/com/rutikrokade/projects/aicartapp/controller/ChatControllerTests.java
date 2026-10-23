package com.rutikrokade.projects.aicartapp.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ChatController.class, properties = "FRONTEND_ORIGIN=https://example.test")
@ActiveProfiles("dev")
class ChatControllerTests {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chat;

    @MockitoBean
    private ChatPersistenceService persistence;

    @Test void postUsesSessionHeaderAndGlobalEnvelope() throws Exception {
        var response = new ChatResponseDto(); response.setReply("Hello"); response.setOutcome("GREETING");
        when(chat.chat(eq("session"), any())).thenReturn(response);
        mvc.perform(post("/api/v1/chat").contextPath("/api/v1").header("X-Session-Id", "session")
                .contentType("application/json").content("{\"requestId\":\"00000000-0000-4000-8000-000000000001\",\"message\":\"hello\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reply").value("Hello")).andExpect(jsonPath("$.timestamp").exists());
    }

    @Test void historyUsesBoundedCursorParameters() throws Exception {
        var history = new ChatHistoryDto(); history.setMessages(java.util.List.of());
        when(persistence.history("session", 30L, 10)).thenReturn(history);
        mvc.perform(get("/api/v1/chat/messages?before=30&limit=10").contextPath("/api/v1").header("X-Session-Id", "session"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.messages").isArray());
        verify(persistence).history("session", 30L, 10);
    }

    @Test void routingFieldsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/chat").contextPath("/api/v1").contentType("application/json")
                .content("{\"message\":\"hi\",\"target\":\"MENU\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(chat);
    }

    @Test void sessionAndConflictErrorsUseGlobalHandler() throws Exception {
        when(chat.chat(isNull(), any())).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "Session required"));
        mvc.perform(post("/api/v1/chat").contextPath("/api/v1").contentType("application/json").content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("SESSION_REQUIRED"));
        doThrow(new ApiException(HttpStatus.CONFLICT, "CHAT_BUSY", "Wait for the previous message")).when(chat).chat(eq("session"), any());
        mvc.perform(post("/api/v1/chat").contextPath("/api/v1").header("X-Session-Id", "session")
                .contentType("application/json").content("{\"message\":\"hi\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CHAT_BUSY"));
    }
}
