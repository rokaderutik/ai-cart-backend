package com.rutikrokade.projects.aicartapp.integration;

import static org.assertj.core.api.Assertions.*;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.repository.*;
import com.rutikrokade.projects.aicartapp.util.*;
import jakarta.validation.Validation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfSystemProperty(named = "phase2.database-tests", matches = "true")
class Phase4DatabaseTests {

    private static final String SCHEMA = "phase4_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String FINGERPRINT = "test-768";
    private static AnnotationConfigApplicationContext context;
    private static Connection admin;
    private static boolean created;
    private static JdbcTemplate jdbc;
    private static JdbcTemplate independent;
    private static RetrievalRepository repository;
    private static TransactionTemplate transaction;
    private static final float[] QUERY = vector(1, 0);

    @org.springframework.boot.test.context.TestConfiguration
    @EnableTransactionManagement
    static class Transactions {

        @Bean
        DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
    }

    @BeforeAll
    static void setup() throws Exception {
        Properties env = new Properties();
        if (Files.exists(Path.of(".env"))) {
            try (Reader reader = Files.newBufferedReader(Path.of(".env"))) { env.load(reader); }
        }
        String url = required("DB_JDBC_URL", env), username = required("DB_USERNAME", env), password = required("DB_PASSWORD", env);
        if (!SCHEMA.matches("phase4_test_[0-9a-f]{32}")) throw new IllegalStateException("Invalid test schema");
        admin = DriverManager.getConnection(url, username, password);
        try (Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            created = true;
        }
        Flyway.configure().dataSource(url, username, password).schemas(SCHEMA).defaultSchema(SCHEMA)
                .createSchemas(false).load().migrate();
        var source = new DriverManagerDataSource(url, username, password);
        source.setSchema(SCHEMA);
        var otherSource = new DriverManagerDataSource(url, username, password);
        otherSource.setSchema(SCHEMA);
        independent = new JdbcTemplate(otherSource);
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(ObjectMapper.class, () -> JsonMapper.builder().build());
        context.registerBean(KnowledgeIndexRepository.class);
        context.registerBean(RetrievalRepository.class);
        context.refresh();
        repository = context.getBean(RetrievalRepository.class);
        transaction = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        transaction.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var sources = new KnowledgeSourceReader(validation.getValidator()).read(new ClassPathResource("knowledge/menu.json"),
                    new ClassPathResource("knowledge/restaurant-guide.md"));
            var writer = context.getBean(KnowledgeIndexRepository.class);
            transaction.executeWithoutResult(status -> {
                Map<String, Long> ids = new HashMap<>();
                sources.getMenu().forEach(item -> ids.put(item.getSourceKey(), writer.upsertMenu(item)));
                sources.getDocuments().forEach(doc -> writer.upsertVector(doc,
                        KnowledgeDocuments.metadata(doc, FINGERPRINT, ids.get(doc.getMenuSourceKey())), QUERY));
                writer.publishState(FINGERPRINT, sources.getSourceHash());
            });
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (context != null) context.close();
        if (admin != null) {
            try (Connection connection = admin) {
                if (created && SCHEMA.matches("phase4_test_[0-9a-f]{32}")) {
                    try (Statement statement = connection.createStatement()) { statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE"); }
                }
            }
        }
    }

    @Test
    void filtersBeforeTopFiveAndUsesInclusivePriceSpiceBoundaries() {
        rollback(() -> {
            jdbc.update("UPDATE vector_store SET embedding = ?::public.vector WHERE metadata->>'sourceId' = 'menu:dal-tadka'",
                    Arrays.toString(vector(.8f, .6f)));
            var request = request(RetrievalRequestDto.Target.MENU);
            request.getFilters().setMaxPrice(new BigDecimal("180.50"));
            request.getFilters().setCategories(List.of("MAIN"));
            request.getFilters().setDietaryTags(List.of("VEGETARIAN"));
            request.getFilters().setMinSpiceLevel(2);
            request.getFilters().setMaxSpiceLevel(2);
            var response = search(request, .7, .7);
            assertThat(response.getMenu().getDocuments()).extracting(RetrievedDocumentDto::getSourceId).contains("menu:dal-tadka");
            assertThat(response.getMenu().getDocuments()).allMatch(doc -> RetrievalEvaluation.complies(doc.getMenuItem(), request.getFilters()));
            assertThat(response.getMenu().getDocuments()).filteredOn(doc -> doc.getSourceId().equals("menu:dal-tadka"))
                    .first().satisfies(doc -> assertThat(doc.getScore()).isCloseTo(.8, within(.00001)));
            request.getFilters().setMaxPrice(new BigDecimal("180.49"));
            assertThat(search(request, .7, .7).getMenu().getDocuments()).extracting(RetrievedDocumentDto::getSourceId).doesNotContain("menu:dal-tadka");
        });
    }

    @Test
    void dietaryTagsAreAllRequiredAndAbsentTagIsNotInferred() {
        rollback(() -> {
            var request = request(RetrievalRequestDto.Target.MENU);
            request.getFilters().setDietaryTags(List.of("VEGAN", "VEGETARIAN"));
            assertThat(search(request, .7, .7).getMenu().getDocuments())
                    .allMatch(doc -> doc.getMenuItem().getDietaryTags().containsAll(List.of("VEGAN", "VEGETARIAN")));
            request.getFilters().setDietaryTags(List.of("NON_VEGETARIAN"));
            assertThat(search(request, .7, .7).getMenu().getDocuments()).extracting(RetrievedDocumentDto::getSourceId).doesNotContain("menu:chicken-biryani");
        });
    }

    @Test
    void availabilityRetirementAndCategoryOrSemantics() {
        rollback(() -> {
            var request = request(RetrievalRequestDto.Target.MENU);
            request.getFilters().setCategories(List.of("DESSERT"));
            request.getFilters().setMaxPrice(new BigDecimal("90"));
            assertThat(search(request, .7, .7).getMenu().getOutcome()).isEqualTo(RetrievalBranchDto.Outcome.NO_ELIGIBLE_ITEMS);
            request.getFilters().setAvailableOnly(false);
            assertThat(search(request, .7, .7).getMenu().getDocuments()).extracting(RetrievedDocumentDto::getSourceId).containsExactly("menu:gulab-jamun");
            jdbc.update("UPDATE menu_item SET source_active = false WHERE source_key = 'gulab-jamun'");
            assertThat(search(request, .7, .7).getMenu().getOutcome()).isEqualTo(RetrievalBranchDto.Outcome.NO_ELIGIBLE_ITEMS);
            request.getFilters().setCategories(List.of("BREAD", "SIDE"));
            assertThat(search(request, .7, .7).getMenu().getDocuments()).extracting(doc -> doc.getMenuItem().getCategory()).contains("BREAD", "SIDE");
        });
    }

    @Test
    void mixedResultsAndNoEvidenceOutcomesRemainDistinct() {
        rollback(() -> {
            var request = request(RetrievalRequestDto.Target.BOTH);
            var result = search(request, .7, .7);
            assertThat(result.getStatus()).isEqualTo(RetrievalResponseDto.Status.EVIDENCE_FOUND);
            assertThat(result.getMenu().getDocuments()).hasSize(5).allMatch(doc -> doc.getSourceType().equals("MENU"));
            assertThat(result.getGuide().getDocuments()).hasSize(5).allMatch(doc -> doc.getSourceType().equals("GUIDE"));
            assertThat(result.getGuide().getDocuments()).extracting(RetrievedDocumentDto::getSourceId).isSorted();
            request.getFilters().setMaxPrice(BigDecimal.ZERO);
            result = search(request, .7, .7);
            assertThat(result.getStatus()).isEqualTo(RetrievalResponseDto.Status.PARTIAL_EVIDENCE);
            assertThat(result.getMenu().getOutcome()).isEqualTo(RetrievalBranchDto.Outcome.NO_ELIGIBLE_ITEMS);
            jdbc.update("UPDATE vector_store SET embedding = ?::public.vector", Arrays.toString(vector(0, 1)));
            result = search(request, .7, .7);
            assertThat(result.getStatus()).isEqualTo(RetrievalResponseDto.Status.NO_EVIDENCE);
            assertThat(result.getGuide().getOutcome()).isEqualTo(RetrievalBranchDto.Outcome.NO_RELEVANT_EVIDENCE);
        });
    }

    @Test
    void missingOrMismatchedVectorsFailInsteadOfReturningFalseEmptyResults() {
        rollback(() -> {
            jdbc.update("DELETE FROM vector_store WHERE metadata->>'sourceId' = 'menu:paneer-butter-masala'");
            assertThatThrownBy(() -> search(request(RetrievalRequestDto.Target.MENU), .7, .7))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("KNOWLEDGE_INDEX_INCOMPLETE");
        });
        rollback(() -> {
            jdbc.update("UPDATE vector_store SET metadata = jsonb_set(metadata, '{embeddingFingerprint}', '\"wrong\"') WHERE metadata->>'sourceType' = 'GUIDE'");
            assertThatThrownBy(() -> search(request(RetrievalRequestDto.Target.GUIDE), .7, .7))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("KNOWLEDGE_INDEX_INCOMPLETE");
        });
        rollback(() -> {
            jdbc.update("UPDATE knowledge_index_state SET embedding_fingerprint = 'wrong'");
            assertThatThrownBy(() -> search(request(RetrievalRequestDto.Target.MENU), .7, .7))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("KNOWLEDGE_MODEL_MISMATCH");
        });
    }

    @Test
    void ignoresOtherCorporaAndPreservesDatabaseRows() {
        rollback(() -> {
            jdbc.update("INSERT INTO vector_store SELECT ?, content, jsonb_set(metadata, '{corpusId}', '\"other\"'), embedding FROM vector_store LIMIT 1", UUID.randomUUID());
            var before = jdbc.queryForList("SELECT id, content, metadata::text, embedding::text FROM vector_store ORDER BY id");
            var result = search(request(RetrievalRequestDto.Target.BOTH), .7, .7);
            assertThat(result.getMenu().getEligibleCount()).isEqualTo(39);
            assertThat(result.getGuide().getEligibleCount()).isEqualTo(10);
            assertThat(jdbc.queryForList("SELECT id, content, metadata::text, embedding::text FROM vector_store ORDER BY id")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_session", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cart", Integer.class)).isZero();
        });
    }

    @Test
    void snapshotRemainsConsistentAcrossConcurrentPublication() {
        try {
            transaction.executeWithoutResult(status -> {
                assertThat(repository.checkReady(FINGERPRINT)).isEqualTo(1);
                // A distinct datasource uses another connection, simulating a committed publication between reads.
                var otherTransaction = new TransactionTemplate(new DataSourceTransactionManager(independent.getDataSource()));
                otherTransaction.executeWithoutResult(other -> {
                    independent.update("UPDATE menu_item SET price = 999 WHERE source_key = 'paneer-butter-masala'");
                    independent.update("UPDATE knowledge_index_state SET revision = 2");
                });
                var request = request(RetrievalRequestDto.Target.MENU);
                request.getFilters().setCategories(List.of("MAIN"));
                request.getFilters().setMaxSpiceLevel(1);
                request.getFilters().setMaxPrice(new BigDecimal("250"));
                var response = search(request, .7, .7);
                assertThat(response.getCorpusRevision()).isEqualTo(1);
                assertThat(response.getMenu().getDocuments()).extracting(RetrievedDocumentDto::getSourceId).contains("menu:paneer-butter-masala");
            });
            assertThat(repository.checkReady(FINGERPRINT)).isEqualTo(2);
        } finally {
            independent.update("UPDATE menu_item SET price = 250 WHERE source_key = 'paneer-butter-masala'");
            independent.update("UPDATE knowledge_index_state SET revision = 1");
        }
    }

    private static RetrievalResponseDto search(RetrievalRequestDto request, double menu, double guide) {
        return repository.search(RetrievalValidation.normalize(request), Map.of("query", QUERY), FINGERPRINT, menu, guide);
    }

    private static RetrievalRequestDto request(RetrievalRequestDto.Target target) {
        var request = new RetrievalRequestDto();
        request.setQuery("query");
        request.setTarget(target);
        if (target != RetrievalRequestDto.Target.GUIDE) request.setFilters(new RetrievalFiltersDto());
        return request;
    }

    private void rollback(Runnable work) {
        transaction.executeWithoutResult(status -> {
            try { work.run(); } finally { status.setRollbackOnly(); }
        });
    }

    private static float[] vector(float a, float b) {
        float[] values = new float[768];
        values[0] = a;
        values[1] = b;
        return values;
    }

    private static String required(String key, Properties env) {
        String value = System.getenv().getOrDefault(key, env.getProperty(key));
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }
}
