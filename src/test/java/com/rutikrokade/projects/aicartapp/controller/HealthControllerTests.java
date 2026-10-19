package com.rutikrokade.projects.aicartapp.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

import com.rutikrokade.projects.aicartapp.config.CorsConfig;
import com.rutikrokade.projects.aicartapp.service.HealthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = HealthController.class,
        properties = "FRONTEND_ORIGIN=https://ai-cart.example")
@ActiveProfiles("dev")
@Import({CorsConfig.class, HealthServiceImpl.class})
class HealthControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataSource dataSource;

    @Test
    void reportsUpAtVersionedUrlWhenDatabaseIsValid() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        mockMvc.perform(get("/api/v1/health").contextPath("/api/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    void reportsUnavailableWhenDatabaseConnectionFails() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("unavailable"));

        mockMvc.perform(get("/api/v1/health").contextPath("/api/v1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.status").value("DOWN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "https://ai-cart.example"})
    void allowsLocalAndConfiguredFrontendPreflightUnderContextPath(String origin) throws Exception {
        mockMvc.perform(options("/api/v1/health").contextPath("/api/v1")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
    }

    @Test
    void rejectsUnconfiguredFrontendPreflight() throws Exception {
        mockMvc.perform(options("/api/v1/health").contextPath("/api/v1")
                        .header("Origin", "https://unconfigured.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
