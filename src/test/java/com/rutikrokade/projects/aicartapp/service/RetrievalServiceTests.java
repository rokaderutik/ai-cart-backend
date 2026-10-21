package com.rutikrokade.projects.aicartapp.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import com.rutikrokade.projects.aicartapp.config.RetrievalProperties;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.repository.RetrievalRepository;
import com.rutikrokade.projects.aicartapp.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

class RetrievalServiceTests {

    private final RetrievalRepository repository = mock(RetrievalRepository.class);
    private final RetrievalEmbeddingModel embedding = mock(RetrievalEmbeddingModel.class);
    private final RetrievalService service = new RetrievalServiceImpl(repository, embedding, new RetrievalProperties());

    @BeforeEach
    void setup() {
        when(embedding.fingerprint()).thenReturn("fingerprint");
        when(embedding.embed(anyString())).thenReturn(new float[]{1});
        when(repository.search(any(), anyMap(), anyString(), anyDouble(), anyDouble())).thenAnswer(invocation -> {
            var result = new RetrievalResponseDto();
            result.setStatus(RetrievalResponseDto.Status.NO_EVIDENCE);
            return result;
        });
    }

    @Test
    void embedsOnceForIdenticalBranchQueriesAndDoesNotMutateInput() {
        var request = request(RetrievalRequestDto.Target.BOTH);
        request.setQuery("  creamy dish  ");
        assertThat(service.retrieve(request).getEmbeddingCalls()).isEqualTo(1);
        verify(embedding).embed("creamy dish");
        assertThat(request.getFilters()).isNull();
        assertThat(request.getQuery()).isEqualTo("  creamy dish  ");
        verify(repository).search(argThat(r -> r.getFilters().getAvailableOnly()
                && r.getMenuQuery().equals("creamy dish")), anyMap(), eq("fingerprint"), eq(.69), eq(.61));
    }

    @Test
    void embedsDistinctBranchesSeparately() {
        var request = request(RetrievalRequestDto.Target.BOTH);
        request.setMenuQuery("rice");
        request.setGuideQuery("takeaway");
        assertThat(service.retrieve(request).getEmbeddingCalls()).isEqualTo(2);
        verify(embedding).embed("rice");
        verify(embedding).embed("takeaway");
    }

    @Test
    void missingPublicationDoesNotCallProvider() {
        when(repository.checkReady(anyString())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "KNOWLEDGE_NOT_PUBLISHED", "Not published"));
        assertThatThrownBy(() -> service.retrieve(request(RetrievalRequestDto.Target.MENU)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("KNOWLEDGE_NOT_PUBLISHED");
        verify(embedding, never()).embed(anyString());
    }

    @Test
    void providerFailureIsSanitizedAndNeverReportedAsNoEvidence() {
        when(embedding.embed(anyString())).thenThrow(new IllegalStateException("private provider content"));
        assertThatThrownBy(() -> service.retrieve(request(RetrievalRequestDto.Target.MENU)))
                .isInstanceOf(ApiException.class).hasMessageNotContaining("private")
                .extracting("code").isEqualTo("EMBEDDING_UNAVAILABLE");
        verify(repository, never()).search(any(), anyMap(), anyString(), anyDouble(), anyDouble());
    }

    @Test
    void databaseFailureIsSanitized() {
        when(repository.checkReady(anyString())).thenThrow(new DataAccessResourceFailureException("private connection"));
        assertThatThrownBy(() -> service.retrieve(request(RetrievalRequestDto.Target.GUIDE)))
                .isInstanceOf(ApiException.class).hasMessageNotContaining("private")
                .extracting("code").isEqualTo("RETRIEVAL_DATABASE_UNAVAILABLE");
    }

    @Test
    void rejectsInvalidRequestsBeforeAnyIo() {
        var request = request(RetrievalRequestDto.Target.MENU);
        var filters = new RetrievalFiltersDto();
        request.setFilters(filters);
        filters.setMaxPrice(new BigDecimal("10.001"));
        invalid(request);
        filters.setMaxPrice(new BigDecimal("-1"));
        invalid(request);
        filters.setMaxPrice(null);
        filters.setMinSpiceLevel(3);
        filters.setMaxSpiceLevel(1);
        invalid(request);
        filters.setMinSpiceLevel(0);
        filters.setMaxSpiceLevel(6);
        invalid(request);
        filters.setMaxSpiceLevel(5);
        filters.setDietaryTags(List.of("VEGAN", "NON_VEGETARIAN"));
        invalid(request);
        filters.setDietaryTags(List.of("GLUTEN_FREE"));
        invalid(request);
        filters.setDietaryTags(List.of());
        filters.setCategories(List.of("PIZZA"));
        invalid(request);
        filters.setCategories(List.of());
        filters.setAvailableOnly(null);
        invalid(request);
        request.setFilters(null);
        request.setQuery(" ");
        invalid(request);
        request.setQuery("a".repeat(1001));
        invalid(request);
        verifyNoInteractions(repository, embedding);
    }

    @Test
    void rejectsGuideFiltersAndSingleTargetBranchOverrides() {
        var request = request(RetrievalRequestDto.Target.GUIDE);
        request.setFilters(new RetrievalFiltersDto());
        invalid(request);
        request.setFilters(null);
        request.setGuideQuery("override");
        invalid(request);
    }

    private void invalid(RetrievalRequestDto request) {
        assertThatThrownBy(() -> service.retrieve(request)).isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private RetrievalRequestDto request(RetrievalRequestDto.Target target) {
        var request = new RetrievalRequestDto();
        request.setTarget(target);
        request.setQuery("creamy dish");
        return request;
    }
}
