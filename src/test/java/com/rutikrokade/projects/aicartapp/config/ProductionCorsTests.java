package com.rutikrokade.projects.aicartapp.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rutikrokade.projects.aicartapp.controller.HealthController;
import com.rutikrokade.projects.aicartapp.service.HealthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = HealthController.class,
        properties = "FRONTEND_ORIGIN=https://ai-cart.example")
@ActiveProfiles("prod")
@Import(CorsConfig.class)
class ProductionCorsTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @Test
    void allowsOnlyConfiguredFrontend() throws Exception {
        mockMvc.perform(options("/api/v1/health").contextPath("/api/v1")
                        .header("Origin", "https://ai-cart.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://ai-cart.example"));
    }

    @Test
    void rejectsLocalhostWhenItIsNotConfigured() throws Exception {
        mockMvc.perform(options("/api/v1/health").contextPath("/api/v1")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
