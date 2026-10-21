package com.rutikrokade.projects.aicartapp.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import com.rutikrokade.projects.aicartapp.config.IngestionProperties;
import com.rutikrokade.projects.aicartapp.service.KnowledgeIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("ingest")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeIngestionRunner implements ApplicationRunner {

    private final KnowledgeIngestionService ingestion;
    private final IngestionProperties properties;
    private final ObjectMapper json;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (properties.getMode().equals("dry-run")) {
            var plan = ingestion.plan("dry-run");
            Path preview = Path.of(properties.getPreview()).toAbsolutePath().normalize();
            // Preview contains only source content and planned operations, not arbitrary DB rows.
            if (preview.getParent() != null) {
                Files.createDirectories(preview.getParent());
            }
            json.writerWithDefaultPrettyPrinter().writeValue(preview.toFile(), Map.of(
                    "revision", plan.getSnapshot().getRevision(),
                    "fingerprint", plan.getEmbeddingFingerprint(),
                    "documents", plan.getSource().getDocuments(),
                    "embed", plan.getToEmbed().stream().map(d -> d.getSourceId()).toList(),
                    "delete", plan.getToDelete(),
                    "retire", plan.getToRetire(),
                    "menuInserted", plan.getMenuInserted(),
                    "menuUpdated", plan.getMenuUpdated(),
                    "metadataUpdates", plan.getMetadataOnly().size(),
                    "skipped", plan.getSkipped()));
            log.info("Dry-run preview written to {}; no embeddings requested or application data published", preview);
        } else {
            ingestion.ingest(properties.getMode());
        }
    }
}
