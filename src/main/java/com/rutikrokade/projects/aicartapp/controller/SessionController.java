package com.rutikrokade.projects.aicartapp.controller;

import com.rutikrokade.projects.aicartapp.dto.SessionDto;
import com.rutikrokade.projects.aicartapp.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @PostMapping
    public ResponseEntity<SessionDto> createSession() {
        return ResponseEntity.status(HttpStatus.CREATED).body(sessionService.createSession());
    }

    @DeleteMapping("/current")
    public ResponseEntity<Void> endSession(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        sessionService.endSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}
