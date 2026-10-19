package com.rutikrokade.projects.aicartapp.dto;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionDto {

    private String sessionId;

    private Instant expiresAt;
}
