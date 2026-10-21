package com.rutikrokade.projects.aicartapp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties("app.retrieval")
public class RetrievalProperties {

    private double menuThreshold = 0.69;

    private double guideThreshold = 0.61;

    private java.nio.file.Path evaluationReport = java.nio.file.Path.of("target", "retrieval-evaluation.json");

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (!Double.isFinite(menuThreshold) || !Double.isFinite(guideThreshold)
                || menuThreshold < 0 || menuThreshold > 1 || guideThreshold < 0 || guideThreshold > 1) {
            throw new IllegalArgumentException("Retrieval thresholds must be finite values between zero and one");
        }
    }
}
