package com.rutikrokade.projects.aicartapp.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.service.RetrievalService;
import com.rutikrokade.projects.aicartapp.util.RetrievalValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = RetrievalController.class, properties = "FRONTEND_ORIGIN=https://example.test")
@ActiveProfiles("dev")
class RetrievalControllerTests {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RetrievalService service;

    @Test
    void returnsGlobalEnvelopeAtContextPath() throws Exception {
        var response = new RetrievalResponseDto();
        response.setStatus(RetrievalResponseDto.Status.NO_EVIDENCE);
        when(service.retrieve(any())).thenReturn(response);
        mvc.perform(post("/api/v1/dev/retrieval").contextPath("/api/v1").contentType("application/json")
                        .content("{\"target\":\"MENU\",\"query\":\"rice\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NO_EVIDENCE"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"target\":\"UNKNOWN\",\"query\":\"rice\"}",
            "{\"target\":1,\"query\":\"rice\"}",
            "{\"target\":\"MENU\",\"query\":\"rice\",\"filters\":{\"maxSpiceLevel\":1.9}}",
            "{\"target\":\"MENU\",\"query\":\"rice\",\"filters\":{\"maxSpiceLevel\":\"1\"}}",
            "{\"target\":\"MENU\",\"query\":\"rice\",\"filters\":{\"availableOnly\":\"false\"}}",
            "{\"target\":\"MENU\",\"query\":\"rice\",\"filters\":{\"maxPric\":50}}",
            "{\"target\":\"MENU\",\"query\":\"rice\",\"query\":\"bread\"}"
    })
    void rejectsMalformedOrCoercedFilters(String body) throws Exception {
        mvc.perform(post("/api/v1/dev/retrieval").contextPath("/api/v1").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void reportsSemanticValidationThroughGlobalHandler() throws Exception {
        when(service.retrieve(any())).thenAnswer(invocation -> {
            RetrievalValidation.normalize(invocation.getArgument(0));
            return null;
        });
        mvc.perform(post("/api/v1/dev/retrieval").contextPath("/api/v1").contentType("application/json")
                        .content("{\"target\":\"MENU\",\"query\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_RETRIEVAL_REQUEST"));
    }

    @Test
    void controllerIsAbsentInProdEvenWhenDevIsAlsoActive() {
        for (String[] profiles : new String[][]{{"prod"}, {"dev", "prod"}}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().setActiveProfiles(profiles);
                context.register(RetrievalController.class);
                context.refresh();
                assertThat(context.getBeansOfType(RetrievalController.class)).isEmpty();
            }
        }
    }
}
