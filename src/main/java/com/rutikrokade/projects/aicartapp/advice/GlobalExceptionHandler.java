package com.rutikrokade.projects.aicartapp.advice;

import java.util.Comparator;
import java.util.List;

import com.rutikrokade.projects.aicartapp.dto.FieldErrorDto;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception) {
        return error(exception.getStatus(), exception.getCode(), exception.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        List<FieldErrorDto> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(field -> new FieldErrorDto(field.getField(), field.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldErrorDto::getField).thenComparing(FieldErrorDto::getMessage))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidInput(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Request contains invalid JSON or an invalid value", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        // Preserve framework statuses (invalid JSON, IDs, content types, methods, and routes).
        if (exception instanceof ErrorResponse frameworkError) {
            HttpStatus status = HttpStatus.valueOf(frameworkError.getStatusCode().value());
            String code = exception instanceof NoResourceFoundException ? "NOT_FOUND" : "INVALID_REQUEST";
            String message = status.is4xxClientError() ? status.getReasonPhrase() : "Request failed";
            return error(status, code, message, List.of());
        }
        log.error("Unhandled API failure", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", List.of());
    }

    private ResponseEntity<ApiResponse<Void>> error(HttpStatus status, String code, String message,
                                                   List<FieldErrorDto> fields) {
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(new ApiError(status.value(), code, message, fields)));
    }
}
