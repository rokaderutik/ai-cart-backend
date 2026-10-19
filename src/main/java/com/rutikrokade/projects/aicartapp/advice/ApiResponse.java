package com.rutikrokade.projects.aicartapp.advice;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private Instant timestamp;

    private T data;

    private ApiError error;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(Instant.now(), data, null);
    }

    public static ApiResponse<Void> failure(ApiError error) {
        return new ApiResponse<>(Instant.now(), null, error);
    }
}
