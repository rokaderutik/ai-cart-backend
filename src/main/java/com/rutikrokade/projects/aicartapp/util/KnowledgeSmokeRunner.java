package com.rutikrokade.projects.aicartapp.util;

import java.util.List;
import java.util.Objects;
import com.rutikrokade.projects.aicartapp.repository.KnowledgeIndexRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("knowledge-smoke")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeSmokeRunner implements ApplicationRunner {

    private final KnowledgeIndexRepository repository;
    private final RetrievalEmbeddingModel embedding;
    private final VectorStore vectorStore;

    @Override
    public void run(ApplicationArguments args) {
        var snapshot = repository.snapshot();
        if (!Objects.equals(snapshot.getEmbeddingFingerprint(), embedding.fingerprint())) {
            throw new IllegalStateException("Publish knowledge with the current embedding configuration before the smoke check");
        }
        for (String type : List.of("MENU", "GUIDE")) {
            String query = type.equals("MENU") ? "A creamy vegetarian dish with mild spice"
                    : "What are the takeaway policies?";
            var results = vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(5)
                    .similarityThreshold(0.0)
                    .filterExpression("corpusId == 'restaurant-v1' && sourceType == '" + type + "'").build());
            if (results == null || results.isEmpty()) {
                throw new IllegalStateException("No " + type + " documents returned by retrieval smoke check");
            }
            for (var result : results) {
                var metadata = result.getMetadata();
                if (!KnowledgeDocuments.CORPUS.equals(metadata.get("corpusId"))
                        || !type.equals(metadata.get("sourceType"))) {
                    throw new IllegalStateException("Retrieved document has unexpected corpus/type");
                }
                if (type.equals("MENU")) {
                    if (!(metadata.get("itemId") instanceof Number id)
                            || snapshot.getMenu().stream().noneMatch(row -> ((Number) row.get("id")).longValue() == id.longValue())) {
                        throw new IllegalStateException("Retrieved menu document has no valid numeric menu link");
                    }
                }
                log.info("Knowledge smoke {}: source={}, itemId={}, score={}", type,
                        metadata.get("sourceId"), metadata.get("itemId"), result.getScore());
            }
        }
        log.info("Knowledge retrieval smoke passed. Review relevance in the results; full retrieval evaluation is Phase 4.");
    }
}
