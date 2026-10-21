package com.rutikrokade.projects.aicartapp.service;

import java.util.*;
import com.rutikrokade.projects.aicartapp.config.RetrievalProperties;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.repository.RetrievalRepository;
import com.rutikrokade.projects.aicartapp.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.context.annotation.Profile;

@Service
@Profile({"dev", "prod", "ingest", "model-smoke"})
@RequiredArgsConstructor
@Slf4j
public class RetrievalServiceImpl implements RetrievalService {

    private final RetrievalRepository repository;
    private final RetrievalEmbeddingModel embedding;
    private final RetrievalProperties properties;

    @Override
    public RetrievalResponseDto retrieve(RetrievalRequestDto input) {
        long start = System.nanoTime();
        String id = UUID.randomUUID().toString();
        RetrievalRequestDto request = RetrievalValidation.normalize(input);
        properties.validate();
        log.info("Retrieval {} starting: target={}, filters={}", id, request.getTarget(),
                request.getTarget() == RetrievalRequestDto.Target.GUIDE ? null : request.getFilters());
        try {
            long dbStart = System.nanoTime();
            repository.checkReady(embedding.fingerprint());
            long databaseMillis = millis(dbStart);
            Set<String> queries = new LinkedHashSet<>();
            if (request.getTarget() != RetrievalRequestDto.Target.GUIDE) queries.add(request.getMenuQuery());
            if (request.getTarget() != RetrievalRequestDto.Target.MENU) queries.add(request.getGuideQuery());
            Map<String, float[]> vectors = new LinkedHashMap<>();
            long embeddingStart = System.nanoTime();
            try {
                for (String query : queries) vectors.put(query, embedding.embed(query));
            } catch (RuntimeException failure) {
                log.warn("Retrieval {} embedding failed: {}", id, failure.getClass().getSimpleName());
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMBEDDING_UNAVAILABLE", "Query embedding is unavailable; retry later");
            }
            long embeddingMillis = millis(embeddingStart);
            dbStart = System.nanoTime();
            RetrievalResponseDto result = repository.search(request, vectors, embedding.fingerprint(),
                    properties.getMenuThreshold(), properties.getGuideThreshold());
            result.setDatabaseMillis(databaseMillis + millis(dbStart));
            result.setEmbeddingMillis(embeddingMillis);
            result.setEmbeddingCalls(vectors.size());
            result.setRequestId(id);
            result.setTotalMillis(millis(start));
            log.info("Retrieval {} completed: status={}, revision={}, menu={}, guide={}, embeddingMs={}, databaseMs={}, totalMs={}",
                    id, result.getStatus(), result.getCorpusRevision(), summary(result.getMenu()), summary(result.getGuide()),
                    result.getEmbeddingMillis(), result.getDatabaseMillis(), result.getTotalMillis());
            if (log.isDebugEnabled()) {
                java.util.stream.Stream.of(result.getMenu(), result.getGuide()).filter(Objects::nonNull)
                        .flatMap(branch -> branch.getDocuments().stream())
                        .forEach(doc -> log.debug("Retrieval {} source={} score={}", id, doc.getSourceId(), doc.getScore()));
            }
            return result;
        } catch (DataAccessException | TransactionException failure) {
            log.warn("Retrieval {} database failed: {}", id, failure.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RETRIEVAL_DATABASE_UNAVAILABLE", "Knowledge retrieval is temporarily unavailable");
        } catch (ApiException failure) {
            log.warn("Retrieval {} failed: code={}", id, failure.getCode());
            throw failure;
        }
    }

    private static String summary(RetrievalBranchDto branch) {
        return branch == null ? "not-requested" : branch.getOutcome() + "/eligible=" + branch.getEligibleCount()
                + "/returned=" + branch.getDocuments().size();
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
