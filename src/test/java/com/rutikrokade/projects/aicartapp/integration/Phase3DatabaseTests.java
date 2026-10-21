package com.rutikrokade.projects.aicartapp.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.Reader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import com.rutikrokade.projects.aicartapp.config.IngestionProperties;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.repository.KnowledgeIndexRepository;
import com.rutikrokade.projects.aicartapp.service.*;
import com.rutikrokade.projects.aicartapp.util.*;
import jakarta.validation.Validation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Never ingests into public: every table and transaction is confined to a generated test schema. */
@EnabledIfSystemProperty(named = "phase2.database-tests", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase3DatabaseTests {

    private static final String SCHEMA = "phase3_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String SESSION = "SESSION_eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
    private static final String CART = "CART_eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
    private static AnnotationConfigApplicationContext context;
    private static Connection admin;
    private static JdbcTemplate jdbc;
    private static KnowledgeIndexRepository repository;
    private static KnowledgePublicationService publication;
    private static boolean schemaCreated;
    private static final Map<String, Object> BOOT_SETTINGS = new HashMap<>();
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path directory;

    private RetrievalEmbeddingModel embedding;
    private KnowledgeIngestionService service;
    private KnowledgeSourceReader reader;
    private jakarta.validation.ValidatorFactory validatorFactory;
    private Path menuPath;
    private Path guidePath;

    @org.springframework.boot.test.context.TestConfiguration
    @EnableTransactionManagement
    static class Transactions {

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @BeforeAll
    static void setupDatabase() throws Exception {
        Properties env = new Properties();
        if (Files.exists(Path.of(".env"))) {
            try (Reader input = Files.newBufferedReader(Path.of(".env"))) {
                env.load(input);
            }
        }
        String url = required("DB_JDBC_URL", env);
        String username = required("DB_USERNAME", env);
        String password = required("DB_PASSWORD", env);
        BOOT_SETTINGS.put("spring.datasource.url", url);
        BOOT_SETTINGS.put("spring.datasource.username", username);
        BOOT_SETTINGS.put("spring.datasource.password", password);
        BOOT_SETTINGS.put("spring.datasource.hikari.schema", SCHEMA);
        BOOT_SETTINGS.put("spring.flyway.schemas", SCHEMA);
        BOOT_SETTINGS.put("spring.flyway.default-schema", SCHEMA);
        BOOT_SETTINGS.put("spring.flyway.create-schemas", "false");
        BOOT_SETTINGS.put("spring.jpa.properties.hibernate.default_schema", SCHEMA);
        BOOT_SETTINGS.put("spring.ai.vectorstore.pgvector.schema-name", SCHEMA);
        BOOT_SETTINGS.put("GOOGLE_API_KEY", "test-key-no-live-model-calls");
        BOOT_SETTINGS.put("logging.level.org.hibernate.orm.connections.pooling", "OFF");
        BOOT_SETTINGS.put("logging.level.org.flywaydb", "WARN");
        BOOT_SETTINGS.put("spring.main.banner-mode", "off");
        admin = DriverManager.getConnection(url, username, password);
        if (!SCHEMA.matches("phase3_test_[0-9a-f]{32}")) {
            throw new IllegalStateException("Invalid test schema");
        }
        try (Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
        }
        // Reproduce a used Phase 2 schema before applying V5.
        Flyway.configure().dataSource(url, username, password).schemas(SCHEMA).defaultSchema(SCHEMA)
                .createSchemas(false).target("4").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
        // Set the schema after connecting: URL query parameters override connection Properties.
        dataSource.setSchema(SCHEMA);
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        jdbc.update("INSERT INTO guest_session VALUES (?, now(), now() + interval '24 hours', '{}'::jsonb)", SESSION);
        jdbc.update("INSERT INTO cart VALUES (?, ?)", CART, SESSION);
        jdbc.update("INSERT INTO cart_item (id, cart_id, menu_item_id, quantity) VALUES (?, ?, 1, 2)", UUID.randomUUID(), CART);
        Flyway.configure().dataSource(url, username, password).schemas(SCHEMA).defaultSchema(SCHEMA)
                .createSchemas(false).load().migrate();
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(ObjectMapper.class, () -> JSON);
        context.registerBean(KnowledgeIndexRepository.class);
        context.registerBean(KnowledgePublicationServiceImpl.class);
        context.refresh();
        repository = context.getBean(KnowledgeIndexRepository.class);
        publication = context.getBean(KnowledgePublicationService.class);
    }

    @BeforeEach
    void sources() throws Exception {
        menuPath = directory.resolve("menu.json");
        guidePath = directory.resolve("restaurant-guide.md");
        Files.copy(new ClassPathResource("knowledge/menu.json").getInputStream(), menuPath);
        Files.copy(new ClassPathResource("knowledge/restaurant-guide.md").getInputStream(), guidePath);
        validatorFactory = Validation.buildDefaultValidatorFactory();
        reader = new KnowledgeSourceReader(validatorFactory.getValidator());
        IngestionProperties properties = new IngestionProperties();
        properties.setMenu(menuPath.toUri().toString());
        properties.setGuide(guidePath.toUri().toString());
        embedding = mock(RetrievalEmbeddingModel.class);
        when(embedding.fingerprint()).thenReturn("integration-768");
        float[] vector = new float[768];
        vector[0] = 1;
        when(embedding.embed(any(org.springframework.ai.document.Document.class))).thenReturn(vector);
        service = new KnowledgeIngestionServiceImpl(properties, new DefaultResourceLoader(), reader, repository,
                embedding, publication);
    }

    @AfterEach
    void closeValidator() {
        validatorFactory.close();
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (context != null) {
            context.close();
        }
        if (admin != null) {
            try (Connection connection = admin) {
                if (schemaCreated && SCHEMA.matches("phase3_test_[0-9a-f]{32}")) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
                    }
                }
            }
        }
    }

    @Test
    @Order(1)
    void adoptsSeedsPreservesCartsAndPublishesIdempotently() throws Exception {
        assertThat(jdbc.queryForObject("SELECT source_key FROM menu_item WHERE id = 1", String.class))
                .isEqualTo("paneer-butter-masala");
        assertThat(service.ingest("dry-run").getDocumentsEmbedded()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store", Integer.class)).isZero();
        var first = service.ingest("apply");
        assertThat(first.getMenuInserted()).isEqualTo(34);
        assertThat(first.getDocumentsEmbedded()).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_item", Integer.class)).isEqualTo(40);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store", Integer.class)).isEqualTo(50);
        // Verify that Spring AI can search the vectors published by our precomputed-vector writer.
        try (Connection connection = context.getBean(DataSource.class).getConnection()) {
            connection.createStatement().execute("SET search_path TO " + SCHEMA + ",public");
            var searchJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var queryModel = mock(org.springframework.ai.embedding.EmbeddingModel.class);
            float[] queryVector = new float[768];
            queryVector[0] = 1;
            when(queryModel.embed(anyString())).thenReturn(queryVector);
            var store = org.springframework.ai.vectorstore.pgvector.PgVectorStore.builder(searchJdbc, queryModel)
                    .schemaName(SCHEMA).dimensions(768).initializeSchema(false)
                    .indexType(org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.NONE).build();
            var results = store.similaritySearch(org.springframework.ai.vectorstore.SearchRequest.builder()
                    .query("mild food").topK(5).similarityThreshold(0.0)
                    .filterExpression("corpusId == 'restaurant-v1' && sourceType == 'MENU'").build());
            assertThat(results).hasSize(5).allMatch(d -> d.getMetadata().get("itemId") instanceof Number);
        }
        assertThat(jdbc.queryForObject("SELECT menu_item_id FROM cart_item WHERE cart_id = ?", Long.class, CART)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT (metadata->>'itemId')::bigint FROM vector_store WHERE metadata->>'sourceId' = 'menu:paneer-butter-masala'",
                Long.class)).isEqualTo(1);
        clearInvocations(embedding);
        var second = service.ingest("apply");
        assertThat(second.getRevision()).isEqualTo(first.getRevision());
        assertThat(second.getDocumentsEmbedded()).isZero();
        verify(embedding, never()).embed(any(org.springframework.ai.document.Document.class));
    }

    @Test
    void descriptionPriceAndMetadataUpdatesOnlyTouchRequiredVectors() throws Exception {
        service.ingest("apply");
        var menu = sourceMenu();
        var item = menu.stream().filter(i -> i.getSourceKey().equals("paneer-butter-masala")).findFirst().orElseThrow();
        item.setPrice(new java.math.BigDecimal("251.25"));
        item.setAvailable(false);
        writeMenu(menu);
        assertThat(service.ingest("apply").getDocumentsEmbedded()).isZero();
        item.setDescription(item.getDescription() + " A buttery, mild accompaniment for bread.");
        writeMenu(menu);
        assertThat(service.ingest("apply").getDocumentsEmbedded()).isEqualTo(1);
        jdbc.update("UPDATE vector_store SET metadata = jsonb_set(metadata, '{sourcePath}', '\"legacy.json\"') "
                + "WHERE metadata->>'sourceId' = 'menu:paneer-butter-masala'");
        var metadata = service.ingest("apply");
        assertThat(metadata.getMetadataUpdated()).isEqualTo(1);
        assertThat(metadata.getDocumentsEmbedded()).isZero();
        jdbc.update("DELETE FROM vector_store WHERE metadata->>'sourceId' = 'menu:paneer-butter-masala'");
        assertThat(service.ingest("apply").getDocumentsEmbedded()).isEqualTo(1);
    }

    @Test
    void removalAndRestorationPreserveIdentityCartAndOtherCorpora() throws Exception {
        service.ingest("apply");
        UUID unrelated = UUID.randomUUID();
        jdbc.update("INSERT INTO vector_store SELECT ?, content, '{\"corpusId\":\"other\"}'::jsonb, embedding "
                + "FROM vector_store LIMIT 1", unrelated);
        var original = sourceMenu();
        writeMenu(original.stream().filter(i -> !i.getSourceKey().equals("paneer-butter-masala")).toList());
        var removed = service.ingest("apply");
        assertThat(removed.getMenuRetired()).isEqualTo(1);
        assertThat(removed.getDocumentsDeleted()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available OR source_active FROM menu_item WHERE id = 1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT menu_item_id FROM cart_item WHERE cart_id = ?", Long.class, CART)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE id = ?", Integer.class, unrelated)).isEqualTo(1);
        writeMenu(original);
        assertThat(service.ingest("apply").getDocumentsEmbedded()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM menu_item WHERE source_key = 'paneer-butter-masala'", Long.class)).isEqualTo(1);
        jdbc.update("DELETE FROM vector_store WHERE id = ?", unrelated);
    }

    @Test
    void providerFailureLeavesPreviousSnapshotAndChangedMenuUntouched() throws Exception {
        service.ingest("apply");
        var before = repository.snapshot();
        var menu = sourceMenu();
        menu.getFirst().setDescription("Changed description requiring an embedding.");
        menu.getFirst().setPrice(new java.math.BigDecimal("777.77"));
        writeMenu(menu);
        when(embedding.embed(any(org.springframework.ai.document.Document.class))).thenThrow(new IllegalStateException("failure"));
        assertThatThrownBy(() -> service.ingest("apply")).hasMessageContaining("nothing published");
        var after = repository.snapshot();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void databaseFailureRollsBackMenuVectorsAndState() throws Exception {
        service.ingest("apply");
        var before = repository.snapshot();
        var menu = sourceMenu();
        menu.getFirst().setDescription("Changed description for rollback test.");
        menu.getFirst().setPrice(new java.math.BigDecimal("888.88"));
        writeMenu(menu);
        // Force a database failure after menu writes, inside the publication transaction.
        jdbc.execute("ALTER TABLE vector_store ADD CONSTRAINT reject_changed_document "
                + "CHECK (content NOT LIKE '%Changed description for rollback test.%')");
        try {
            assertThatThrownBy(() -> service.ingest("apply")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(repository.snapshot()).isEqualTo(before);
        } finally {
            jdbc.execute("ALTER TABLE vector_store DROP CONSTRAINT reject_changed_document");
        }
    }

    @Test
    void staleRevisionCannotPublishAfterAnotherRun() throws Exception {
        service.ingest("apply");
        var menu = sourceMenu();
        menu.getFirst().setPrice(new java.math.BigDecimal("222.22"));
        writeMenu(menu);
        var stale = service.plan("apply");
        service.ingest("apply");
        assertThatThrownBy(() -> publication.publish(stale, Map.of())).hasMessageContaining("Another ingestion");
    }

    @Test
    void newDishGetsIdentityAndRemovedGuideIsPruned() throws Exception {
        service.ingest("apply");
        var menu = new ArrayList<>(sourceMenu());
        var extra = JSON.readValue(JSON.writeValueAsString(menu.getFirst()), SourceMenuItemDto.class);
        extra.setSourceKey("test-new-dish");
        extra.setName("Test new dish");
        menu.add(extra);
        writeMenu(menu);
        assertThat(service.ingest("apply").getDocumentsEmbedded()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM menu_item WHERE source_key = 'test-new-dish'", Long.class)).isGreaterThan(6);
        String guide = Files.readString(guidePath);
        Files.writeString(guidePath, guide.substring(0, guide.indexOf("## Ordering and cart")));
        assertThat(service.ingest("apply").getDocumentsDeleted()).isEqualTo(1);
    }

    private List<SourceMenuItemDto> sourceMenu() {
        return reader.read(new FileSystemResource(menuPath), new FileSystemResource(guidePath)).getMenu();
    }

    @Test
    @Order(99)
    void fullBootWiringSupportsNormalStartupAndIngestDryRunWithoutModelCalls() throws Exception {
        jdbc.update("UPDATE knowledge_index_state SET embedding_fingerprint = NULL");
        var before = repository.snapshot();
        BOOT_SETTINGS.put("app.ingestion.preview", directory.resolve("preview.json").toString());
        for (boolean ingest : List.of(false, true)) {
            var application = new org.springframework.boot.SpringApplication(
                    com.rutikrokade.projects.aicartapp.AicartappApplication.class);
            application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
            application.setAdditionalProfiles(ingest ? new String[]{"dev", "ingest"} : new String[]{"dev"});
            application.addInitializers(app -> app.getEnvironment().getPropertySources()
                    .addFirst(new org.springframework.core.env.MapPropertySource("phase3-isolated", BOOT_SETTINGS)));
            try (var boot = application.run()) {
                assertThat(boot.getBean(org.springframework.ai.embedding.EmbeddingModel.class))
                        .isInstanceOf(RetrievalEmbeddingModel.class);
                assertThat(boot.getBeansOfType(KnowledgeIngestionRunner.class)).hasSize(ingest ? 1 : 0);
            }
            assertThat(repository.snapshot()).isEqualTo(before);
        }
        var preview = JSON.readTree(Files.readString(directory.resolve("preview.json")));
        assertThat(preview.get("documents").size()).isEqualTo(50);
    }

    private void writeMenu(List<SourceMenuItemDto> items) throws Exception {
        Files.writeString(menuPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(items));
    }

    private static String required(String name, Properties values) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = values.getProperty(name);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing database test setting " + name);
        }
        return value;
    }
}
