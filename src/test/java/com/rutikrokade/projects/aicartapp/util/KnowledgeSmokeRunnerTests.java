package com.rutikrokade.projects.aicartapp.util;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.KnowledgeSnapshotDto;
import com.rutikrokade.projects.aicartapp.repository.KnowledgeIndexRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class KnowledgeSmokeRunnerTests {

    private final KnowledgeIndexRepository repository = mock(KnowledgeIndexRepository.class);
    private final RetrievalEmbeddingModel embedding = mock(RetrievalEmbeddingModel.class);
    private final VectorStore store = mock(VectorStore.class);
    private final KnowledgeSmokeRunner runner = new KnowledgeSmokeRunner(repository, embedding, store);

    @Test
    void requiresMatchingPublishedModelBeforeAnySearch() {
        when(embedding.fingerprint()).thenReturn("new-model");
        when(repository.snapshot()).thenReturn(new KnowledgeSnapshotDto(1, "old-model", "", Map.of(), List.of()));
        assertThatThrownBy(() -> runner.run(null)).hasMessageContaining("current embedding configuration");
        verifyNoInteractions(store);
    }

    @Test
    void checksBothSourceTypesAndNumericMenuLinks() {
        when(embedding.fingerprint()).thenReturn("model");
        when(repository.snapshot()).thenReturn(new KnowledgeSnapshotDto(1, "model", "", Map.of(),
                List.of(Map.of("id", 1L))));
        when(store.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("menu", Map.of("corpusId", "restaurant-v1", "sourceType", "MENU",
                        "sourceId", "menu:paneer", "itemId", 1))))
                .thenReturn(List.of(new Document("guide", Map.of("corpusId", "restaurant-v1", "sourceType", "GUIDE",
                        "sourceId", "guide:takeaway"))));
        runner.run(null);
        verify(store, times(2)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void rejectsBrokenMenuLinks() {
        when(embedding.fingerprint()).thenReturn("model");
        when(repository.snapshot()).thenReturn(new KnowledgeSnapshotDto(1, "model", "", Map.of(), List.of()));
        when(store.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("menu", Map.of("corpusId", "restaurant-v1", "sourceType", "MENU",
                        "sourceId", "menu:missing", "itemId", 999))));
        assertThatThrownBy(() -> runner.run(null)).hasMessageContaining("numeric menu link");
    }
}
