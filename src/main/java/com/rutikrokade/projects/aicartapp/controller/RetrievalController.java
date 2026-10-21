package com.rutikrokade.projects.aicartapp.controller;

import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@Profile("dev & !prod")
@RequestMapping("/dev/retrieval")
@RequiredArgsConstructor
public class RetrievalController {

    private final RetrievalService service;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    @PostMapping(consumes = "application/json")
    public RetrievalResponseDto retrieve(@RequestBody String body) {
        RetrievalRequestDto request;
        try {
            request = JSON.readValue(body, RetrievalRequestDto.class);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid retrieval JSON or field value");
        }
        return service.retrieve(request);
    }
}
