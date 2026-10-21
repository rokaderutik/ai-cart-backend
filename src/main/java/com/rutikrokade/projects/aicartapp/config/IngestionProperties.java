package com.rutikrokade.projects.aicartapp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("app.ingestion")
public class IngestionProperties {

    private String mode = "dry-run";

    private String menu = "classpath:knowledge/menu.json";

    private String guide = "classpath:knowledge/restaurant-guide.md";

    private String preview = "target/ingestion-preview.json";
}
