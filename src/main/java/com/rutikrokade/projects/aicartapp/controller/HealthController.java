package com.rutikrokade.projects.aicartapp.controller;

import com.rutikrokade.projects.aicartapp.dto.HealthStatus;
import com.rutikrokade.projects.aicartapp.service.HealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;

    @GetMapping("/health")
    public ResponseEntity<HealthStatus> health() {
        if (healthService.isDatabaseAvailable()) {
            return ResponseEntity.ok(new HealthStatus("UP"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthStatus("DOWN"));
    }
}
