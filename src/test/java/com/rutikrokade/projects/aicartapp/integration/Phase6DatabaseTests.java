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
class Phase6DatabaseTests {

    private static final String SCHEMA = "phase6_test_" + UUID.randomUUID().toString().replace("-", "");
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
        if (!SCHEMA.matches("phase6_test_[0-9a-f]{32}")) throw new IllegalStateException();
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
            if (created && SCHEMA.matches("phase6_test_[0-9a-f]{32}")) {
                try (Statement statement = connection.createStatement()) { statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE"); }
            }
        }
    }

    @Test void mutationOutcomeTranscriptAndReplayAreAtomicAndIsolated() {
        String session = sessions.createSession().getSessionId();
        String other = sessions.createSession().getSessionId();
        var request = request("Add the first dish");
        var turn = persistence.begin(session, request);
        var response = execute(session, turn, 1L, 0, 1);
        assertThat(response.getOutcome()).isEqualTo("CART_UPDATED");
        assertThat(response.getCart().getItems().getFirst().getQuantity()).isEqualTo(1);
        assertThat(response.getCart().getTotal()).isEqualByComparingTo(response.getCart().getItems().getFirst().getUnitPrice());
        assertThat(carts.getCart(other).getItems()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT action_result_json->>'quantity' FROM chat_request WHERE session_id=?", String.class, session)).isEqualTo("1");
        assertThat(persistence.history(session, null, 50).getMessages()).hasSize(2);
        assertThat(persistence.begin(session, request).getReplay().getAssistantMessageId()).isEqualTo(response.getAssistantMessageId());
        // A manual edit after completion must survive a replay of the old result.
        carts.setItemQuantity(session, 1L, 4);
        assertThat(persistence.begin(session, request).getReplay().getCart().getItems().getFirst().getQuantity()).isEqualTo(1);
        assertThat(carts.getCart(session).getItems().getFirst().getQuantity()).isEqualTo(4);
        var next = persistence.begin(session, request("Make that two"));
        assertThat(next.getContext().getLastCartItemId()).isEqualTo(1L);
        assertThat(execute(session, next, 1L, 4, 2).getCart().getItems().getFirst().getQuantity()).isEqualTo(2);
        var remove = persistence.begin(session, request("Remove it"));
        assertThat(execute(session, remove, 1L, 2, 0).getCart().getItems()).isEmpty();
    }

    @Test void concurrentManualChangeRejectsPreparedActionWithoutOverwriting() {
        String session = sessions.createSession().getSessionId();
        var turn = persistence.begin(session, request("Add one"));
        carts.setItemQuantity(session, 1L, 3);
        var result = execute(session, turn, 1L, 0, 1);
        assertThat(result.getOutcome()).isEqualTo("CART_ACTION_REJECTED");
        assertThat(result.getActionResult().getReason()).isEqualTo("CART_CHANGED");
        assertThat(result.getCart().getItems().getFirst().getQuantity()).isEqualTo(3);
        assertThat(carts.getCart(session).getItems().getFirst().getQuantity()).isEqualTo(3);
    }

    @Test void unavailableAndUnknownItemsAreCompletedRejectionsButRemovalIsAllowed() {
        String session = sessions.createSession().getSessionId();
        carts.setItemQuantity(session, 1L, 2);
        jdbc.update("UPDATE menu_item SET available=false WHERE id=1");
        try {
            var add = persistence.begin(session, request("Add one"));
            add.getContext().setLastCartItemId(2L);
            var rejected = execute(session, add, 1L, 2, 3);
            assertThat(rejected.getActionResult().getReason()).isEqualTo("ITEM_UNAVAILABLE");
            var remove = persistence.begin(session, request("Remove it"));
            assertThat(remove.getContext().getLastCartItemId()).isNull();
            assertThat(remove.getContext().getPendingCartAction().getItemId()).isEqualTo(1L);
            var plan = new ChatPlanDto(); plan.setIntent(ChatPlanDto.Intent.CART_ACTION);
            var instruction = new CartInstructionDto(); instruction.setOperation(CartInstructionDto.Operation.REMOVE);
            instruction.setReference(CartInstructionDto.Reference.PENDING); plan.setCartAction(instruction);
            var prepared = CartActionResolver.prepare("Remove it", plan, remove, carts.getCart(session),
                    remove.getContext(), new ChatResponseDto());
            assertThat(prepared.getItemId()).isEqualTo(1L);
            assertThat(execute(session, remove, prepared.getItemId(), 2, prepared.getQuantity()).getCart().getItems()).isEmpty();
        } finally { jdbc.update("UPDATE menu_item SET available=true WHERE id=1"); }
        assertThat(execute(session, persistence.begin(session, request("Unknown")), Long.MAX_VALUE, 0, 1)
                .getActionResult().getReason()).isEqualTo("ITEM_NOT_FOUND");
    }

    @Test void assistantPersistenceFailureRollsBackCartAndOutcome() {
        String session = sessions.createSession().getSessionId();
        var request = request("Add one");
        var turn = persistence.begin(session, request);
        jdbc.execute("CREATE FUNCTION reject_test_assistant() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'simulated completion failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_test_assistant BEFORE INSERT ON chat_message FOR EACH ROW WHEN (NEW.role='ASSISTANT') EXECUTE FUNCTION reject_test_assistant()");
        try {
            assertThatThrownBy(() -> execute(session, turn, 1L, 0, 1)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_assistant ON chat_message");
            jdbc.execute("DROP FUNCTION reject_test_assistant()");
        }
        assertThat(carts.getCart(session).getItems()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT action_result_json IS NULL FROM chat_request WHERE session_id=?", Boolean.class, session)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_request WHERE session_id=?", String.class, session)).isEqualTo("IN_PROGRESS");
        assertThat(persistence.history(session, null, 50).getMessages()).hasSize(1);
        persistence.fail(session, turn, "TEST_FAILURE");
        assertThat(execute(session, persistence.begin(session, request), 1L, 0, 1).getOutcome()).isEqualTo("CART_UPDATED");
    }

    @Test void staleExpiredAndForeignAttemptsCannotMutateAnyCart() {
        String session = sessions.createSession().getSessionId();
        String other = sessions.createSession().getSessionId();
        var request = request("Add one");
        var turn = persistence.begin(session, request);
        var foreign = persistence.begin(other, request);
        assertCode(() -> execute(other, turn, 1L, 0, 1), "CHAT_ATTEMPT_EXPIRED");
        jdbc.update("UPDATE chat_request SET lease_expires_at=now()-interval '1 minute' WHERE session_id=?", session);
        assertCode(() -> execute(session, turn, 1L, 0, 1), "CHAT_ATTEMPT_EXPIRED");
        var retry = persistence.begin(session, request);
        assertCode(() -> execute(session, turn, 1L, 0, 1), "CHAT_ATTEMPT_EXPIRED");
        assertThat(execute(session, retry, 1L, 0, 1).getOutcome()).isEqualTo("CART_UPDATED");
        jdbc.update("UPDATE guest_session SET created_at=now()-interval '2 hours', expires_at=now()-interval '1 hour' WHERE id=?", other);
        assertCode(() -> execute(other, foreign, 1L, 0, 1), "SESSION_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_item i JOIN cart c ON c.id=i.cart_id WHERE c.session_id=?", Integer.class, other)).isZero();
    }

    @Test void duplicateExecutionWithSameAttemptCannotRepeatTheMutation() throws Exception {
        String session = sessions.createSession().getSessionId();
        var request = request("Add one"); var turn = persistence.begin(session, request);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> invoke = () -> { start.await(); try { return execute(session, turn, 1L, 0, 1).getOutcome(); }
                catch (ApiException failure) { return failure.getCode(); } };
            var first = pool.submit(invoke); var second = pool.submit(invoke); start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CART_UPDATED", "CHAT_ATTEMPT_EXPIRED");
        }
        assertThat(carts.getCart(session).getItems().getFirst().getQuantity()).isEqualTo(1);
        assertThat(persistence.history(session, null, 50).getMessages()).hasSize(2);
    }

    @Test void toolProviderFailureLeavesCartUntouchedAndSameIdRetryCommitsOnce() {
        String session = sessions.createSession().getSessionId();
        String name = jdbc.queryForObject("SELECT name FROM menu_item WHERE id=1", String.class);
        var request = request("Add one " + name);
        var planner = mock(QueryPlanningService.class);
        var plan = new ChatPlanDto(); plan.setIntent(ChatPlanDto.Intent.CART_ACTION); plan.setItemIds(List.of(1L));
        var instruction = new CartInstructionDto(); instruction.setOperation(CartInstructionDto.Operation.ADD);
        instruction.setAmount(1); instruction.setReference(CartInstructionDto.Reference.EXPLICIT); plan.setCartAction(instruction);
        when(planner.plan(any(), any(), any(), any())).thenReturn(plan);
        var client = mock(StructuredChatClient.class);
        var actions = new CartActionServiceImpl(client, app.getBean(CartQuantityTool.class), persistence);
        var chat = new ChatServiceImpl(persistence, planner, mock(GroundedAnswerService.class), mock(RetrievalService.class),
                mock(RetrievalRepository.class), mock(RetrievalEmbeddingModel.class), carts, actions);
        when(client.cartToolArguments(any(), any(), any())).thenThrow(new ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "CHAT_MODEL_UNAVAILABLE", "Unavailable"));
        assertCode(() -> chat.chat(session, request), "CHAT_MODEL_UNAVAILABLE");
        assertThat(carts.getCart(session).getItems()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_request WHERE session_id=?", String.class, session)).isEqualTo("FAILED");
        doReturn("{\"itemId\":1,\"quantity\":1}").when(client).cartToolArguments(any(), any(), any());
        var response = chat.chat(session, request);
        assertThat(response.getOutcome()).isEqualTo("CART_UPDATED");
        assertThat(chat.chat(session, request).getAssistantMessageId()).isEqualTo(response.getAssistantMessageId());
        assertThat(carts.getCart(session).getItems().getFirst().getQuantity()).isEqualTo(1);
        assertThat(persistence.history(session, null, 50).getMessages()).hasSize(2);
        verify(client, times(2)).cartToolArguments(any(), any(), any());
    }

    private ChatResponseDto execute(String session, ChatTurnDto turn, Long item, int before, int after) {
        var response = new ChatResponseDto(); response.setRequestId(turn.getRequestId());
        var action = new PreparedCartActionDto(CartInstructionDto.Operation.SET, item, before, after);
        // Separate context instances model separate workers; shared objects must not mask concurrency bugs.
        var context = StructuredChatClient.JSON.convertValue(turn.getContext(), ChatContextDto.class);
        return app.getBean(CartActionExecutionService.class).execute(session, turn, action, response, context, new ChatCallBudget(), System.nanoTime());
    }

    private static ChatRequestDto request(String message) {
        var request = new ChatRequestDto(); request.setRequestId(UUID.randomUUID()); request.setMessage(message); return request;
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
