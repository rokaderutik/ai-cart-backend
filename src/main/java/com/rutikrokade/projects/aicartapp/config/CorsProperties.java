package com.rutikrokade.projects.aicartapp.config;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    @NotEmpty
    private List<@NotBlank String> allowedOrigins;
}
