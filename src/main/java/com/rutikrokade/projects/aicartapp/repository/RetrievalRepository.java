package com.rutikrokade.projects.aicartapp.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.util.KnowledgeDocuments;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class RetrievalRepository {

    private final JdbcTemplate jdbc;

    private static final String MENU_JOIN = """
            FROM menu_item m LEFT JOIN vector_store v ON v.metadata->>'itemId' = m.id::text
              AND v.metadata->>'sourceId' = 'menu:' || m.source_key
              AND v.metadata->>'corpusId' = ? AND v.metadata->>'sourceType' = 'MENU'
            WHERE m.source_active AND m.source_key IS NOT NULL
            """;

    public long checkReady(String fingerprint) {
        var states = jdbc.queryForList("SELECT revision, embedding_fingerprint FROM knowledge_index_state WHERE corpus_id = ?",
                KnowledgeDocuments.CORPUS);
        if (states.size() != 1 || ((Number) states.getFirst().get("revision")).longValue() < 1) {
            throw unavailable("KNOWLEDGE_NOT_PUBLISHED", "Publish the knowledge index before retrieval");
        }
        var state = states.getFirst();
        if (!fingerprint.equals(state.get("embedding_fingerprint"))) {
            throw unavailable("KNOWLEDGE_MODEL_MISMATCH", "Published knowledge uses a different embedding configuration");
        }
        return ((Number) state.get("revision")).longValue();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RetrievalResponseDto search(RetrievalRequestDto request, Map<String, float[]> vectors,
                                       String fingerprint, double menuThreshold, double guideThreshold) {
        RetrievalResponseDto result = new RetrievalResponseDto();
        result.setCorpusRevision(checkReady(fingerprint));
        result.setEmbeddingFingerprint(fingerprint);
        if (request.getTarget() != RetrievalRequestDto.Target.GUIDE) {
            result.setAppliedFilters(request.getFilters());
            result.setMenu(menu(request.getFilters(), vectors.get(request.getMenuQuery()), fingerprint, menuThreshold));
        }
        if (request.getTarget() != RetrievalRequestDto.Target.MENU) {
            result.setGuide(guide(vectors.get(request.getGuideQuery()), fingerprint, guideThreshold));
        }
        int requested = request.getTarget() == RetrievalRequestDto.Target.BOTH ? 2 : 1;
        long found = java.util.stream.Stream.of(result.getMenu(), result.getGuide()).filter(Objects::nonNull)
                .filter(branch -> branch.getOutcome() == RetrievalBranchDto.Outcome.EVIDENCE_FOUND).count();
        result.setStatus(found == 0 ? RetrievalResponseDto.Status.NO_EVIDENCE
                : found == requested ? RetrievalResponseDto.Status.EVIDENCE_FOUND : RetrievalResponseDto.Status.PARTIAL_EVIDENCE);
        return result;
    }

    private RetrievalBranchDto menu(RetrievalFiltersDto filters, float[] vector, String fingerprint, double threshold) {
        List<Object> args = new ArrayList<>();
        args.add(KnowledgeDocuments.CORPUS);
        StringBuilder sql = new StringBuilder(MENU_JOIN);
        if (filters.getAvailableOnly()) sql.append(" AND m.available");
        if (filters.getMaxPrice() != null) {
            sql.append(" AND m.currency = 'INR' AND m.price <= ?");
            args.add(filters.getMaxPrice());
        }
        if (filters.getMinSpiceLevel() != null) {
            sql.append(" AND m.spice_level >= ?");
            args.add(filters.getMinSpiceLevel());
        }
        if (filters.getMaxSpiceLevel() != null) {
            sql.append(" AND m.spice_level <= ?");
            args.add(filters.getMaxSpiceLevel());
        }
        for (String tag : filters.getDietaryTags()) {
            sql.append(" AND ? = ANY(m.dietary_tags)");
            args.add(tag);
        }
        if (!filters.getCategories().isEmpty()) {
            sql.append(" AND m.category IN (").append(String.join(",", Collections.nCopies(filters.getCategories().size(), "?"))).append(')');
            args.addAll(filters.getCategories());
        }
        int eligible = Objects.requireNonNull(jdbc.queryForObject("SELECT count(DISTINCT m.id) " + sql, Integer.class, args.toArray()));
        if (eligible == 0) {
            return new RetrievalBranchDto(RetrievalBranchDto.Outcome.NO_ELIGIBLE_ITEMS, 0, threshold, List.of());
        }
        List<Object> integrityArgs = new ArrayList<>();
        integrityArgs.add(fingerprint);
        integrityArgs.addAll(args);
        var integrity = jdbc.queryForMap("SELECT count(*) AS total, count(v.id) AS indexed, "
                + "count(*) FILTER (WHERE v.metadata->>'embeddingFingerprint' = ?) AS compatible " + sql, integrityArgs.toArray());
        if (((Number) integrity.get("total")).intValue() != eligible
                || ((Number) integrity.get("indexed")).intValue() != eligible
                || ((Number) integrity.get("compatible")).intValue() != eligible) {
            throw unavailable("KNOWLEDGE_INDEX_INCOMPLETE", "Eligible menu items have missing, duplicate or incompatible vectors; re-run ingestion");
        }
        List<Object> searchArgs = new ArrayList<>();
        searchArgs.add(Arrays.toString(vector));
        searchArgs.addAll(args);
        searchArgs.add(threshold);
        List<RetrievedDocumentDto> documents = jdbc.query("""
                SELECT * FROM (SELECT v.id AS document_id, v.content, v.metadata->>'sourceId' AS source_id,
                  v.metadata->>'title' AS title, v.metadata->>'sourcePath' AS source_path,
                  v.metadata->>'contentHash' AS content_hash,
                  1 - (v.embedding OPERATOR(public.<=>) ?::public.vector) AS score, m.*
                """ + sql + ") ranked WHERE score >= ? ORDER BY score DESC, source_id ASC LIMIT 5",
                (rs, row) -> document(rs, "MENU", new RetrievalMenuItemDto(rs.getLong("id"), rs.getString("name"),
                        rs.getString("description"), rs.getString("category"), strings(rs, "ingredients"),
                        strings(rs, "dietary_tags"), rs.getInt("spice_level"), rs.getBigDecimal("price"),
                        rs.getString("currency"), rs.getBoolean("available"))), searchArgs.toArray());
        return branch(eligible, threshold, documents);
    }

    private RetrievalBranchDto guide(float[] vector, String fingerprint, double threshold) {
        var counts = jdbc.queryForMap("""
                SELECT count(*) AS total, count(DISTINCT metadata->>'sourceId') AS unique_sources,
                  count(*) FILTER (WHERE metadata->>'embeddingFingerprint' = ?) AS compatible
                FROM vector_store WHERE metadata->>'corpusId' = ? AND metadata->>'sourceType' = 'GUIDE'
                """, fingerprint, KnowledgeDocuments.CORPUS);
        int total = ((Number) counts.get("total")).intValue();
        if (total == 0 || total != ((Number) counts.get("compatible")).intValue()
                || total != ((Number) counts.get("unique_sources")).intValue()) {
            throw unavailable("KNOWLEDGE_INDEX_INCOMPLETE", "Guide vectors are absent, duplicated or incompatible; re-run ingestion");
        }
        var documents = jdbc.query("""
                SELECT * FROM (SELECT id AS document_id, content, metadata->>'sourceId' AS source_id,
                  metadata->>'title' AS title, metadata->>'sourcePath' AS source_path,
                  metadata->>'contentHash' AS content_hash,
                  1 - (embedding OPERATOR(public.<=>) ?::public.vector) AS score
                FROM vector_store WHERE metadata->>'corpusId' = ? AND metadata->>'sourceType' = 'GUIDE') ranked
                WHERE score >= ? ORDER BY score DESC, source_id ASC LIMIT 5
                """, (rs, row) -> document(rs, "GUIDE", null), Arrays.toString(vector), KnowledgeDocuments.CORPUS, threshold);
        return branch(total, threshold, documents);
    }

    private RetrievalBranchDto branch(int count, double threshold, List<RetrievedDocumentDto> documents) {
        return new RetrievalBranchDto(documents.isEmpty() ? RetrievalBranchDto.Outcome.NO_RELEVANT_EVIDENCE
                : RetrievalBranchDto.Outcome.EVIDENCE_FOUND, count, threshold, List.copyOf(documents));
    }

    private RetrievedDocumentDto document(ResultSet rs, String type, RetrievalMenuItemDto menu) throws SQLException {
        String content = rs.getString("content");
        String snippet = content.length() <= 240 ? content : content.substring(0, 240) + "…";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("corpusId", KnowledgeDocuments.CORPUS);
        metadata.put("sourcePath", rs.getString("source_path"));
        metadata.put("contentHash", rs.getString("content_hash"));
        return new RetrievedDocumentDto(rs.getObject("document_id", UUID.class), rs.getString("source_id"), type,
                rs.getString("title"), rs.getDouble("score"), content, snippet, Collections.unmodifiableMap(metadata), menu);
    }

    private static List<String> strings(ResultSet rs, String name) throws SQLException {
        var value = rs.getArray(name);
        try {
            return List.of((String[]) value.getArray());
        } finally {
            value.free();
        }
    }

    private static ApiException unavailable(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
