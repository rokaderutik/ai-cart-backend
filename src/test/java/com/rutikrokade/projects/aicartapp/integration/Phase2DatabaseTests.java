package com.rutikrokade.projects.aicartapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.rutikrokade.projects.aicartapp.AicartappApplication;
import com.rutikrokade.projects.aicartapp.dto.CartDto;
import com.rutikrokade.projects.aicartapp.dto.SessionDto;
import com.rutikrokade.projects.aicartapp.entity.MenuItem;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.repository.MenuItemRepository;
import com.rutikrokade.projects.aicartapp.service.CartService;
import com.rutikrokade.projects.aicartapp.service.SessionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opt-in tests: the same Neon database, but no application-schema reads or writes. */
@EnabledIfSystemProperty(named = "phase2.database-tests", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase2DatabaseTests {

    private static final Long PANEER = 1L;
    private static final Long DAL = 2L;
    private static final Long UNAVAILABLE = 6L;
    private static final String LEGACY_SESSION = "SESSION_ffffffff-ffff-4fff-8fff-ffffffffffff";
    private static final String LEGACY_CART = "CART_ffffffff-ffff-4fff-8fff-ffffffffffff";
    private static final String SCHEMA = "phase2_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final Map<String, Object> SETTINGS = new HashMap<>();
    private static ConfigurableApplicationContext context;
    private static Connection adminConnection;
    private static boolean schemaCreated;
    private static SessionService sessions;
    private static CartService carts;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startInIsolatedSchema() throws Exception {
        Properties values = new Properties();
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            try (Reader reader = Files.newBufferedReader(envFile)) {
                values.load(reader);
            }
        }
        String url = requiredValue("DB_JDBC_URL", values);
        String username = requiredValue("DB_USERNAME", values);
        String password = requiredValue("DB_PASSWORD", values);
        adminConnection = DriverManager.getConnection(url, username, password);
        try (Statement statement = adminConnection.createStatement()) {
            try (var result = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')")) {
                result.next();
                assertThat(result.getBoolean(1)).as("Phase 1 must already have enabled pgvector").isTrue();
            }
            // The identifier is generated here, never supplied by a user or an environment variable.
            if (!SCHEMA.matches("phase2_test_[0-9a-f]{32}")) {
                throw new IllegalStateException("Invalid test schema");
            }
            statement.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
        }
        seedLegacyCartBeforeUpgrade(url, username, password);
        SETTINGS.put("spring.datasource.url", url);
        SETTINGS.put("spring.datasource.username", username);
        SETTINGS.put("spring.datasource.password", password);
        SETTINGS.put("spring.datasource.hikari.schema", SCHEMA);
        SETTINGS.put("spring.datasource.hikari.maximum-pool-size", "4");
        SETTINGS.put("spring.flyway.schemas", SCHEMA);
        SETTINGS.put("spring.flyway.default-schema", SCHEMA);
        SETTINGS.put("spring.flyway.create-schemas", "false");
        SETTINGS.put("spring.jpa.properties.hibernate.default_schema", SCHEMA);
        SETTINGS.put("spring.jpa.hibernate.ddl-auto", "validate");
        SETTINGS.put("spring.jpa.open-in-view", "false");
        SETTINGS.put("app.cors.allowed-origins", "http://localhost:5173");
        SETTINGS.put("logging.level.org.hibernate.orm.connections.pooling", "OFF");
        SETTINGS.put("logging.level.org.flywaydb", "WARN");
        SETTINGS.put("spring.main.banner-mode", "off");
        String base = "org.springframework.ai.model.google.genai.autoconfigure.";
        SETTINGS.put("spring.autoconfigure.exclude", String.join(",",
                base + "chat.GoogleGenAiChatAutoConfiguration",
                base + "embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration",
                base + "embedding.GoogleGenAiTextEmbeddingAutoConfiguration",
                base + "image.GoogleGenAiImageConnectionAutoConfiguration",
                base + "image.GoogleGenAiImageAutoConfiguration",
                "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration"));
        startContext();
    }

    private static void seedLegacyCartBeforeUpgrade(String url, String username, String password) {
        // Reproduce an already-used Phase 2 database before starting the current application.
        Flyway.configure().dataSource(url, username, password).schemas(SCHEMA).defaultSchema(SCHEMA)
                .createSchemas(false).target("3").load().migrate();
        JdbcTemplate legacy = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        UUID originalPaneerId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID customItemId = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
        legacy.update("INSERT INTO " + SCHEMA + ".menu_item "
                        + "(id, name, description, category, ingredients, dietary_tags, spice_level, price, currency, available) "
                        + "SELECT ?, 'Migration fixture dish', description, category, ingredients, dietary_tags, "
                        + "spice_level, 12.34, currency, available FROM " + SCHEMA + ".menu_item WHERE id = ?",
                customItemId, originalPaneerId);
        legacy.update("INSERT INTO " + SCHEMA + ".guest_session (id, created_at, expires_at, context_json) "
                + "VALUES (?, now(), now() + interval '24 hours', '{\"preference\":\"vegetarian\"}'::jsonb)", LEGACY_SESSION);
        legacy.update("INSERT INTO " + SCHEMA + ".cart (id, session_id) VALUES (?, ?)", LEGACY_CART, LEGACY_SESSION);
        legacy.update("INSERT INTO " + SCHEMA + ".cart_item (id, cart_id, menu_item_id, quantity) VALUES (?, ?, ?, 2)",
                UUID.randomUUID(), LEGACY_CART, originalPaneerId);
        legacy.update("INSERT INTO " + SCHEMA + ".cart_item (id, cart_id, menu_item_id, quantity) VALUES (?, ?, ?, 3)",
                UUID.randomUUID(), LEGACY_CART, customItemId);
    }

    private static void startContext() {
        SpringApplication application = new SpringApplication(AicartappApplication.class);
        application.setAdditionalProfiles("phase2-test");
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(app -> app.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("isolated-database-test", SETTINGS)));
        context = application.run();
        sessions = context.getBean(SessionService.class);
        carts = context.getBean(CartService.class);
        jdbc = context.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
    }

    @AfterAll
    static void closeAndRemoveOnlyTestSchema() throws Exception {
        try {
            if (context != null) {
                context.close();
            }
        } finally {
            if (adminConnection != null) {
                try (Connection connection = adminConnection) {
                    if (schemaCreated && SCHEMA.matches("phase2_test_[0-9a-f]{32}")) {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
                        }
                    }
                }
            }
        }
    }

    @Test
    @Order(1)
    void upgradePreservesExistingCartAndCustomMenuData() {
        try {
            CartDto migrated = carts.getCart(LEGACY_SESSION);
            assertThat(migrated.getCartId()).isEqualTo(LEGACY_CART);
            assertThat(migrated.getTotal()).isEqualByComparingTo("537.02");
            assertThat(migrated.getItems()).hasSize(2);
            assertThat(migrated.getItems().getFirst().getItemId()).isEqualTo(1L);
            assertThat(migrated.getItems().getFirst().getQuantity()).isEqualTo(2);
            assertThat(migrated.getItems().getLast().getItemId()).isEqualTo(7L);
            assertThat(migrated.getItems().getLast().getName()).isEqualTo("Migration fixture dish");
            assertThat(migrated.getItems().getLast().getQuantity()).isEqualTo(3);
            assertThat(sessions.requireActiveSession(LEGACY_SESSION).getContextJson())
                    .containsEntry("preference", "vegetarian");
        } finally {
            jdbc.update("DELETE FROM guest_session WHERE id = ?", LEGACY_SESSION);
            jdbc.update("DELETE FROM menu_item WHERE name = 'Migration fixture dish'");
        }
    }

    @Test
    void newMenuItemsReceiveDatabaseGeneratedLongIds() {
        MenuItemRepository menu = context.getBean(MenuItemRepository.class);
        MenuItem item = menu.saveAndFlush(new MenuItem(null, "Identity generation test", "Temporary test dish",
                "MAIN", List.of("rice"), List.of("VEGAN"), 0, new BigDecimal("12.34"), "INR", true, null, true));
        try {
            assertThat(item.getId()).isGreaterThan(7L);
            assertThat(menu.findById(item.getId()).orElseThrow().getName()).isEqualTo("Identity generation test");
        } finally {
            menu.deleteById(item.getId());
        }
    }

    @Test
    void migrationsSeedMenuAndMapArraysAndJson() {
        MenuItemRepository menu = context.getBean(MenuItemRepository.class);
        assertThat(menu.count()).isEqualTo(6);
        MenuItem paneer = menu.findById(PANEER).orElseThrow();
        assertThat(paneer.getIngredients()).contains("paneer", "cream");
        assertThat(paneer.getDietaryTags()).containsExactly("VEGETARIAN");
        SessionDto session = sessions.createSession();
        assertThat(sessions.requireActiveSession(session.getSessionId()).getContextJson()).isEmpty();
        assertThat(Duration.between(sessions.requireActiveSession(session.getSessionId()).getCreatedAt(),
                session.getExpiresAt()).toHours()).isEqualTo(24);
    }

    @Test
    void sessionsHaveSeparatePersistentCartsAndExactTotals() {
        SessionDto a = sessions.createSession();
        SessionDto b = sessions.createSession();
        CartDto empty = carts.getCart(a.getSessionId());
        assertThat(empty.getCartId()).startsWith("CART_");
        assertThat(empty.getItems()).isEmpty();
        assertThat(empty.getTotal()).isEqualByComparingTo("0.00");
        assertThat(carts.getCart(b.getSessionId()).getCartId()).isNotEqualTo(empty.getCartId());
        carts.setItemQuantity(a.getSessionId(), PANEER, 2);
        CartDto result = carts.setItemQuantity(a.getSessionId(), DAL, 3);
        assertThat(result.getCartId()).isEqualTo(empty.getCartId());
        assertThat(result.getTotal()).isEqualByComparingTo("1041.50");
        assertThat(carts.getCart(b.getSessionId()).getItems()).isEmpty();
        assertThat(carts.setItemQuantity(a.getSessionId(), DAL, 3).getTotal()).isEqualByComparingTo("1041.50");
        assertThat(carts.getCart(a.getSessionId()).getItems()).hasSize(2);
        assertThat(carts.setItemQuantity(a.getSessionId(), PANEER, 0).getTotal()).isEqualByComparingTo("541.50");
        assertThat(carts.setItemQuantity(a.getSessionId(), PANEER, 0).getItems()).hasSize(1);
        assertThat(carts.setItemQuantity(a.getSessionId(), DAL, 20).getTotal()).isEqualByComparingTo("3610.00");
        assertThat(carts.setItemQuantity(a.getSessionId(), DAL, 0).getItems()).isEmpty();
    }

    @Test
    void rejectsInvalidItemsAndQuantitiesWithoutChangingCart() {
        String session = sessions.createSession().getSessionId();
        for (Integer quantity : new Integer[]{null, -1, 21}) {
            assertCode(() -> carts.setItemQuantity(session, PANEER, quantity), "INVALID_QUANTITY");
        }
        for (Long itemId : new Long[]{null, 0L, -1L}) {
            assertCode(() -> carts.setItemQuantity(session, itemId, 1), "INVALID_ITEM_ID");
        }
        assertCode(() -> carts.setItemQuantity(session, Long.MAX_VALUE, 1), "ITEM_NOT_FOUND");
        assertCode(() -> carts.setItemQuantity(session, UNAVAILABLE, 1), "ITEM_UNAVAILABLE");
        assertThat(carts.setItemQuantity(session, UNAVAILABLE, 0).getItems()).isEmpty();
        assertThat(carts.getCart(session).getItems()).isEmpty();
    }

    @Test
    void currentDatabasePriceAndAvailabilityRemainAuthoritative() {
        String session = sessions.createSession().getSessionId();
        carts.setItemQuantity(session, PANEER, 2);
        try {
            jdbc.update("UPDATE menu_item SET price = 255.25, available = false WHERE id = ?", PANEER);
            CartDto result = carts.getCart(session);
            assertThat(result.getTotal()).isEqualByComparingTo("510.50");
            assertThat(result.getItems().getFirst().isAvailable()).isFalse();
            assertCode(() -> carts.setItemQuantity(session, PANEER, 3), "ITEM_UNAVAILABLE");
            assertThat(carts.setItemQuantity(session, PANEER, 0).getItems()).isEmpty();
        } finally {
            jdbc.update("UPDATE menu_item SET price = 250.00, available = true WHERE id = ?", PANEER);
        }
    }

    @Test
    void expiredSessionCannotReadMutateOrEndSession() {
        String session = sessions.createSession().getSessionId();
        carts.setItemQuantity(session, PANEER, 1);
        jdbc.update("UPDATE guest_session SET created_at = now() - interval '25 hours', "
                + "expires_at = now() - interval '1 hour' WHERE id = ?", session);
        assertCode(() -> carts.getCart(session), "SESSION_EXPIRED");
        assertCode(() -> carts.setItemQuantity(session, PANEER, 2), "SESSION_EXPIRED");
        assertCode(() -> sessions.endSession(session), "SESSION_EXPIRED");
    }

    @Test
    void endingSessionCascadesOnlyItsOwnCart() {
        String a = sessions.createSession().getSessionId();
        String b = sessions.createSession().getSessionId();
        CartDto cart = carts.setItemQuantity(a, PANEER, 1);
        carts.setItemQuantity(b, DAL, 2);
        sessions.endSession(a);
        assertCode(() -> carts.getCart(a), "INVALID_SESSION");
        assertCode(() -> carts.setItemQuantity(a, PANEER, 1), "INVALID_SESSION");
        assertCode(() -> sessions.endSession(a), "INVALID_SESSION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart WHERE id = ?", Long.class, cart.getCartId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_item WHERE cart_id = ?", Long.class, cart.getCartId())).isZero();
        assertThat(carts.getCart(b).getTotal()).isEqualByComparingTo("361.00");
    }

    @Test
    void concurrentUpdatesDoNotCreateDuplicateCartRows() throws Exception {
        String session = sessions.createSession().getSessionId();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = List.of(1, 2, 3, 4).stream().map(quantity -> executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent test did not start");
                }
                return carts.setItemQuantity(session, PANEER, quantity);
            })).toList();
            start.countDown();
            for (var task : tasks) {
                assertThat(task.get(45, TimeUnit.SECONDS).getItems()).hasSize(1);
            }
        }
        CartDto result = carts.getCart(session);
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getQuantity()).isBetween(1, 4);
    }

    @Test
    void databaseEnforcesUniquenessQuantityAndForeignKeys() {
        String session = sessions.createSession().getSessionId();
        String cart = carts.setItemQuantity(session, PANEER, 1).getCartId();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cart_item (id, cart_id, menu_item_id, quantity) VALUES (?, ?, ?, 1)",
                UUID.randomUUID(), cart, PANEER)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE cart_item SET quantity = 21 WHERE cart_id = ?", cart))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cart_item (id, cart_id, menu_item_id, quantity) VALUES (?, ?, ?, 1)",
                UUID.randomUUID(), cart, Long.MAX_VALUE)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cartSurvivesApplicationRestartAndMigrationsAreRepeatable() {
        String session = sessions.createSession().getSessionId();
        CartDto before = carts.setItemQuantity(session, DAL, 2);
        context.close();
        startContext();
        assertThat(carts.getCart(session)).isEqualTo(before);
        assertThat(context.getBean(MenuItemRepository.class).count()).isEqualTo(6);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);
    }

    private static String requiredValue(String name, Properties fileValues) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = fileValues.getProperty(name);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Database tests require " + name + " in the environment or root .env");
        }
        return value;
    }
}
