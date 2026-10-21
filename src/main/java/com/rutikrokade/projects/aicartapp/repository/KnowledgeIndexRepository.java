package com.rutikrokade.projects.aicartapp.repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.util.KnowledgeDocuments;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class KnowledgeIndexRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public KnowledgeSnapshotDto snapshot() {
        Map<String, Object> state = jdbc.queryForMap(
                "SELECT * FROM knowledge_index_state WHERE corpus_id = ?", KnowledgeDocuments.CORPUS);
        Map<UUID, StoredKnowledgeDocumentDto> documents = new HashMap<>();
        jdbc.query("SELECT id, content, metadata::text FROM vector_store WHERE metadata->>'corpusId' = ?",
                rs -> {
                    UUID id = rs.getObject(1, UUID.class);
                    documents.put(id, new StoredKnowledgeDocumentDto(id, rs.getString(2),
                            json.readValue(rs.getString(3), new TypeReference<Map<String, Object>>() { })));
                }, KnowledgeDocuments.CORPUS);
        List<Map<String, Object>> menu = jdbc.query("SELECT * FROM menu_item", (rs, row) -> {
            Map<String, Object> values = new HashMap<>();
            for (String key : List.of("id", "source_key", "source_active", "name", "description", "category",
                    "spice_level", "price", "currency", "available")) {
                values.put(key, rs.getObject(key));
            }
            for (String key : List.of("ingredients", "dietary_tags")) {
                Array array = rs.getArray(key);
                try {
                    values.put(key, List.of((String[]) array.getArray()));
                } finally {
                    array.free();
                }
            }
            return values;
        });
        return new KnowledgeSnapshotDto(((Number) state.get("revision")).longValue(),
                (String) state.get("embedding_fingerprint"), (String) state.get("source_hash"), documents, menu);
    }

    public long lockRevision() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT revision FROM knowledge_index_state WHERE corpus_id = ? FOR UPDATE",
                Long.class, KnowledgeDocuments.CORPUS));
    }

    public Long upsertMenu(SourceMenuItemDto item) {
        return jdbc.execute((java.sql.Connection connection) -> {
            Array ingredients = connection.createArrayOf("text", KnowledgeDocuments.sorted(item.getIngredients()).toArray());
            Array tags = connection.createArrayOf("text", KnowledgeDocuments.sorted(item.getDietaryTags()).toArray());
            try (var statement = connection.prepareStatement("""
                    INSERT INTO menu_item (source_key, name, description, category, ingredients,
                        dietary_tags, spice_level, price, currency, available, source_active)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true)
                    ON CONFLICT (source_key) DO UPDATE SET name = EXCLUDED.name,
                        description = EXCLUDED.description, category = EXCLUDED.category,
                        ingredients = EXCLUDED.ingredients, dietary_tags = EXCLUDED.dietary_tags,
                        spice_level = EXCLUDED.spice_level, price = EXCLUDED.price,
                        currency = EXCLUDED.currency, available = EXCLUDED.available, source_active = true
                    RETURNING id
                    """)) {
                statement.setString(1, item.getSourceKey());
                statement.setString(2, item.getName().strip());
                statement.setString(3, item.getDescription().strip());
                statement.setString(4, item.getCategory().strip());
                statement.setArray(5, ingredients);
                statement.setArray(6, tags);
                statement.setInt(7, item.getSpiceLevel());
                statement.setBigDecimal(8, item.getPrice());
                statement.setString(9, item.getCurrency());
                statement.setBoolean(10, item.getAvailable());
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            } finally {
                ingredients.free();
                tags.free();
            }
        });
    }

    public void upsertVector(KnowledgeDocumentDto doc, Map<String, Object> metadata, float[] vector) {
        int count = jdbc.update("""
                INSERT INTO vector_store (id, content, metadata, embedding)
                VALUES (?, ?, ?::jsonb, ?::public.vector)
                ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content,
                    metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding
                WHERE vector_store.metadata->>'corpusId' = ?
                """, doc.getId(), doc.getContent(), json.writeValueAsString(metadata),
                Arrays.toString(vector), KnowledgeDocuments.CORPUS);
        if (count != 1) {
            throw new IllegalStateException("Vector ID belongs to another corpus: " + doc.getSourceId());
        }
    }

    public void updateMetadata(KnowledgeDocumentDto doc, Map<String, Object> metadata) {
        int count = jdbc.update("UPDATE vector_store SET metadata = ?::jsonb WHERE id = ? AND metadata->>'corpusId' = ?",
                json.writeValueAsString(metadata), doc.getId(), KnowledgeDocuments.CORPUS);
        if (count != 1) {
            throw new IllegalStateException("Vector changed during ingestion: " + doc.getSourceId());
        }
    }

    public void retire(String key) {
        jdbc.update("UPDATE menu_item SET source_active = false, available = false WHERE source_key = ?", key);
    }

    public void deleteVector(UUID id) {
        jdbc.update("DELETE FROM vector_store WHERE id = ? AND metadata->>'corpusId' = ?", id, KnowledgeDocuments.CORPUS);
    }

    public void publishState(String fingerprint, String sourceHash) {
        jdbc.update("""
                UPDATE knowledge_index_state SET revision = revision + 1,
                    embedding_fingerprint = ?, source_hash = ?, published_at = now() WHERE corpus_id = ?
                """, fingerprint, sourceHash, KnowledgeDocuments.CORPUS);
    }

    @SuppressWarnings("unchecked")
    public static boolean matchesMenu(SourceMenuItemDto item, Map<String, Object> row) {
        return Boolean.TRUE.equals(row.get("source_active"))
                && item.getName().strip().equals(row.get("name"))
                && item.getDescription().strip().equals(row.get("description"))
                && item.getCategory().strip().equals(row.get("category"))
                && KnowledgeDocuments.sorted(item.getIngredients()).equals(
                        KnowledgeDocuments.sorted((List<String>) row.get("ingredients")))
                && KnowledgeDocuments.sorted(item.getDietaryTags()).equals(
                        KnowledgeDocuments.sorted((List<String>) row.get("dietary_tags")))
                && item.getSpiceLevel().intValue() == ((Number) row.get("spice_level")).intValue()
                && item.getPrice().compareTo((BigDecimal) row.get("price")) == 0
                && item.getCurrency().equals(row.get("currency"))
                && item.getAvailable().equals(row.get("available"));
    }
}
