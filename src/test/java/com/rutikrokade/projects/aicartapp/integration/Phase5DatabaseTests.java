package com.rutikrokade.projects.aicartapp.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import com.rutikrokade.projects.aicartapp.AicartappApplication;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.repository.*;
import com.rutikrokade.projects.aicartapp.service.*;
import com.rutikrokade.projects.aicartapp.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfSystemProperty(named = "phase2.database-tests", matches = "true")
class Phase5DatabaseTests {

    private static final String SCHEMA = "phase5_test_" + UUID.randomUUID().toString().replace("-", "");
    private static ConfigurableApplicationContext app;
    private static Connection admin;
    private static boolean created;
    private static SessionService sessions;
    private static CartService carts;
    private static ChatPersistenceService persistence;
    private static JdbcTemplate jdbc;

    @BeforeAll static void start() throws Exception {
        Properties env = new Properties();
        if (Files.exists(Path.of(".env"))) {
            try (Reader reader = Files.newBufferedReader(Path.of(".env"))) { env.load(reader); }
        }
        String url = required("DB_JDBC_URL", env), username = required("DB_USERNAME", env), password = required("DB_PASSWORD", env);
        if (!SCHEMA.matches("phase5_test_[0-9a-f]{32}")) throw new IllegalStateException();
        admin = DriverManager.getConnection(url, username, password);
        try (Statement statement = admin.createStatement()) { statement.execute("CREATE SCHEMA " + SCHEMA); created = true; }
        Map<String,Object> settings = new HashMap<>();
        settings.put("spring.datasource.url", url);
        settings.put("spring.datasource.username", username);
        settings.put("spring.datasource.password", password);
        settings.put("spring.datasource.hikari.schema", SCHEMA);
        settings.put("spring.datasource.hikari.maximum-pool-size", "5");
        settings.put("spring.flyway.schemas", SCHEMA);
        settings.put("spring.flyway.default-schema", SCHEMA);
        settings.put("spring.flyway.create-schemas", "false");
        settings.put("spring.jpa.properties.hibernate.default_schema", SCHEMA);
        settings.put("spring.jpa.hibernate.ddl-auto", "validate");
        settings.put("app.cors.allowed-origins", "http://localhost:5173");
        settings.put("logging.level.org.hibernate.orm.connections.pooling", "OFF");
        settings.put("logging.level.org.flywaydb", "WARN");
        settings.put("spring.main.banner-mode", "off");
        String base = "org.springframework.ai.model.google.genai.autoconfigure.";
        settings.put("spring.autoconfigure.exclude", String.join(",",
                base+"chat.GoogleGenAiChatAutoConfiguration",
                base+"embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration",
                base+"embedding.GoogleGenAiTextEmbeddingAutoConfiguration",
                base+"image.GoogleGenAiImageConnectionAutoConfiguration",
                base+"image.GoogleGenAiImageAutoConfiguration",
                "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration"));
        var application = new SpringApplication(AicartappApplication.class);
        application.setAdditionalProfiles("phase2-test");
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-chat-test", settings)));
        app = application.run();
        sessions = app.getBean(SessionService.class);
        carts = app.getBean(CartService.class);
        persistence = app.getBean(ChatPersistenceService.class);
        jdbc = app.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
    }

    @AfterAll static void cleanup() throws Exception {
        if (app != null) app.close();
        if (admin != null) try (Connection connection = admin) {
            if (created && SCHEMA.matches("phase5_test_[0-9a-f]{32}")) {
                try (Statement statement = connection.createStatement()) { statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE"); }
            }
        }
    }

    @Test void replayPersistsCardsContextAndOriginalCartWithoutDuplicatingMessages() {
        String session = sessions.createSession().getSessionId();
        var request = request("show vegetarian food");
        var turn = persistence.begin(session, request);
        var response = response(request);
        response.setRecommendations(List.of(new RetrievalMenuItemDto(1L, "Dish", "Description", "MAIN",
                List.of("lentils"), List.of("VEGETARIAN"), 1, new BigDecimal("220.00"), "INR", true)));
        var context = turn.getContext();
        context.setRecommendationIds(List.of(1L));
        context.getPreferences().setMaxPrice(new BigDecimal("300.00"));
        persistence.complete(session, turn, response, context);
        var replay = persistence.begin(session, request).getReplay();
        assertThat(replay.getAssistantMessageId()).isEqualTo(response.getAssistantMessageId());
        assertThat(replay.getRecommendations().getFirst().getId()).isEqualTo(1L);
        assertThat(replay.getCart().getTotal()).isEqualByComparingTo("0");
        assertThat(persistence.history(session, null, 50).getMessages()).hasSize(2);
        var next = persistence.begin(session, request("what about that?"));
        assertThat(next.getContext().getRecommendationIds()).containsExactly(1L);
        assertThat(next.getContext().getRecommendationMessageId()).isEqualTo(response.getAssistantMessageId());
        assertThat(next.getHistory()).hasSize(2);
        assertThat(next.getContext().getPreferences().getMaxPrice()).isEqualByComparingTo("300");
    }

    @Test void duplicateConflictAndDifferentRequestBusyAreDistinct() {
        String session = sessions.createSession().getSessionId();
        var request = request("hello");
        persistence.begin(session, request);
        assertCode(() -> persistence.begin(session, request), "CHAT_REQUEST_IN_PROGRESS");
        assertCode(() -> persistence.begin(session, request("other")), "CHAT_BUSY");
        var changed = request("changed"); changed.setRequestId(request.getRequestId());
        assertCode(() -> persistence.begin(session, changed), "REQUEST_ID_CONFLICT");
    }

    @Test void failedRetryHasOneUserMessageAndOldRetryBecomesStale() {
        String session = sessions.createSession().getSessionId();
        var request = request("hello");
        var first = persistence.begin(session, request);
        persistence.fail(session, first, "CHAT_MODEL_UNAVAILABLE");
        var retry = persistence.begin(session, request);
        assertThat(retry.getAttemptToken()).isNotEqualTo(first.getAttemptToken());
        assertThat(persistence.history(session, null, 50).getMessages()).hasSize(1);
        persistence.fail(session, retry, "CHAT_MODEL_UNAVAILABLE");
        var newer = request("new turn");
        var newerTurn = persistence.begin(session, newer);
        assertThat(newerTurn.getHistory()).isEmpty();
        persistence.complete(session, newerTurn, response(newer), newerTurn.getContext());
        assertCode(() -> persistence.begin(session, request), "CHAT_RETRY_STALE");
    }

    @Test void expiredLeaseIsReclaimedAndLateWorkersAreFenced() {
        String session = sessions.createSession().getSessionId();
        var request = request("hello");
        var first = persistence.begin(session, request);
        jdbc.update("UPDATE chat_request SET lease_expires_at=now()-interval '1 minute' WHERE session_id=?", session);
        var retry = persistence.begin(session, request);
        persistence.fail(session, first, "LATE_FAILURE");
        assertCode(() -> persistence.complete(session, first, response(request), first.getContext()), "CHAT_ATTEMPT_EXPIRED");
        persistence.complete(session, retry, response(request), retry.getContext());
        assertThat(persistence.history(session, null, 50).getMessages()).hasSize(2);
        assertThat(persistence.begin(session, request).getReplay()).isNotNull();
    }

    @Test void completionRollsBackAndHistoryPaginatesChronologically() {
        String session = sessions.createSession().getSessionId();
        var request = request("one"); var turn = persistence.begin(session, request);
        var invalid = response(request); invalid.setReply(null);
        assertThatThrownBy(() -> persistence.complete(session, turn, invalid, turn.getContext())).isInstanceOf(RuntimeException.class);
        assertThat(persistence.history(session, null, 50).getMessages()).hasSize(1);
        persistence.complete(session, turn, response(request), turn.getContext());
        for (String message : List.of("two", "three")) {
            var req = request(message); var current = persistence.begin(session, req);
            persistence.complete(session, current, response(req), current.getContext());
        }
        var page = persistence.history(session, null, 3);
        assertThat(page.getMessages()).hasSize(3);
        assertThat(page.getMessages().stream().map(ChatMessageDto::getId)).isSorted();
        var older = persistence.history(session, page.getNextBefore(), 3);
        assertThat(older.getMessages()).hasSize(3);
        assertThat(older.getNextBefore()).isNull();
        assertThat(older.getMessages().getLast().getId()).isLessThan(page.getMessages().getFirst().getId());
    }

    @Test void sessionDeletionAndExpiryPreventLateCompletion() {
        String session = sessions.createSession().getSessionId();
        var request = request("hello"); var turn = persistence.begin(session, request);
        sessions.endSession(session);
        assertThatThrownBy(() -> persistence.complete(session, turn, response(request), turn.getContext())).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_request WHERE session_id=?", Integer.class, session)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE session_id=?", Integer.class, session)).isZero();
        String expired = sessions.createSession().getSessionId();
        var pending = persistence.begin(expired, request);
        jdbc.update("UPDATE guest_session SET created_at=now()-interval '2 hours', expires_at=now()-interval '1 hour' WHERE id=?", expired);
        assertThatThrownBy(() -> persistence.complete(expired, pending, response(request), pending.getContext())).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE session_id=? AND role='ASSISTANT'", Integer.class, expired)).isZero();
    }

    @Test void concurrentClaimsAllowOnlyOneRequest() throws Exception {
        String session = sessions.createSession().getSessionId();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> claim = () -> { start.await(); try { persistence.begin(session, request("hello")); return "CLAIMED"; }
                catch (ApiException failure) { return failure.getCode(); } };
            var first = pool.submit(claim); var second = pool.submit(claim); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CLAIMED", "CHAT_BUSY");
        }
    }

    @Test void modelWaitDoesNotHoldSessionLockAndChatNeverChangesCart() throws Exception {
        String session = sessions.createSession().getSessionId();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var planner = mock(QueryPlanningService.class);
        when(planner.plan(any(), any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            var plan = new ChatPlanDto(); plan.setIntent(ChatPlanDto.Intent.GREETING); return plan;
        });
        var service = new ChatServiceImpl(persistence, planner, mock(GroundedAnswerService.class), mock(RetrievalService.class),
                mock(RetrievalRepository.class), mock(RetrievalEmbeddingModel.class), carts, mock(CartActionService.class));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var chat = pool.submit(() -> service.chat(session, request("hello")));
            assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
            try {
                // This operation also needs the session row lock and must finish before the model is released.
                var changed = pool.submit(() -> carts.setItemQuantity(session, 1L, 2));
                assertThat(changed.get(10, TimeUnit.SECONDS).getItems()).hasSize(1);
            } finally { release.countDown(); }
            assertThat(chat.get(15, TimeUnit.SECONDS).getOutcome()).isEqualTo("GREETING");
            assertThat(carts.getCart(session).getItems().getFirst().getQuantity()).isEqualTo(2);
        }
    }

    private static ChatRequestDto request(String message) {
        var request = new ChatRequestDto(); request.setRequestId(UUID.randomUUID()); request.setMessage(message); return request;
    }

    private static ChatResponseDto response(ChatRequestDto request) {
        var response = new ChatResponseDto(); response.setRequestId(request.getRequestId());
        response.setOutcome("GREETING"); response.setReply("Hello!");
        response.setCart(new CartDto("cart-snapshot", List.of(), BigDecimal.ZERO, "INR"));
        return response;
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);
    }

    private static String required(String key, Properties env) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) value = env.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing test configuration " + key);
        return value;
    }
}
