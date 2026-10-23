package com.rutikrokade.projects.aicartapp.controller;

import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.service.*;
import com.rutikrokade.projects.aicartapp.util.StructuredChatClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"dev", "prod"})
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chat;
    private final ChatPersistenceService persistence;

    @PostMapping(consumes = "application/json")
    public ChatResponseDto chat(@RequestHeader(value = "X-Session-Id", required = false) String session,
                                @RequestBody String body) {
        ChatRequestDto request;
        try { request = StructuredChatClient.JSON.readValue(body, ChatRequestDto.class); }
        catch (tools.jackson.core.JacksonException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Expected requestId and message in valid JSON");
        }
        return chat.chat(session, request);
    }

    @GetMapping("/messages")
    public ChatHistoryDto messages(@RequestHeader(value = "X-Session-Id", required = false) String session,
                                   @RequestParam(required = false) Long before,
                                   @RequestParam(defaultValue = "50") int limit) {
        return persistence.history(session, before, limit);
    }
}
