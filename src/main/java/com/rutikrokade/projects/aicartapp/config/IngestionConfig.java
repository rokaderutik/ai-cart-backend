package com.rutikrokade.projects.aicartapp.config;

import com.rutikrokade.projects.aicartapp.util.KnowledgeSourceReader;
import jakarta.validation.Validator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@Profile("ingest")
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfig {

    @Bean
    public KnowledgeSourceReader knowledgeSourceReader(Validator validator) {
        return new KnowledgeSourceReader(validator);
    }
}
