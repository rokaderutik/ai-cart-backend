package com.rutikrokade.projects.aicartapp.service;

import java.time.Clock;
import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.repository.ChatRepository;
import com.rutikrokade.projects.aicartapp.util.KnowledgeDocuments;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatPersistenceServiceImpl implements ChatPersistenceService {

    private final SessionService sessions;
    private final ChatRepository repository;
    private final Clock clock;

    @Override
    @Transactional
    public ChatTurnDto begin(String session, ChatRequestDto request) {
        sessions.lockActiveSession(session);
        var row = repository.request(session, request.getRequestId());
        String hash = KnowledgeDocuments.hash(request.getMessage());
        if (row != null && !hash.equals(row.get("message_hash"))) throw conflict("REQUEST_ID_CONFLICT", "This request ID was used for a different message");
        var turn = new ChatTurnDto();
        turn.setRequestId(request.getRequestId());
        if (row != null && row.get("status").equals("COMPLETED")) {
            log.info("Replaying completed chat request {}", request.getRequestId());
            turn.setReplay(repository.replay(row));
            return turn;
        }
        var now = clock.instant();
        if (row != null && row.get("status").equals("IN_PROGRESS")
                && ((java.sql.Timestamp)row.get("lease_expires_at")).toInstant().isAfter(now)) {
            throw conflict("CHAT_REQUEST_IN_PROGRESS", "This chat request is still processing");
        }
        repository.expireLeases(session, now);
        if (repository.busy(session)) throw conflict("CHAT_BUSY", "Wait for the current message to complete");
        if (row != null && repository.hasNewer(session, ((Number)row.get("id")).longValue())) {
            throw conflict("CHAT_RETRY_STALE", "Newer messages exist; submit this message with a new request ID");
        }
        turn.setAttemptToken(UUID.randomUUID());
        repository.claim(session, request, hash, turn.getAttemptToken(), now, row != null);
        turn.setContext(repository.context(session));
        turn.setHistory(repository.recentCompleted(session));
        turn.setCatalog(repository.catalog());
        log.info("Claimed chat request {} retry={}", request.getRequestId(), row != null);
        return turn;
    }

    @Override
    @Transactional
    public ChatResponseDto complete(String session, ChatTurnDto turn, ChatResponseDto response, ChatContextDto context) {
        sessions.lockActiveSession(session);
        var row = repository.request(session, turn.getRequestId());
        if (row == null || !turn.getAttemptToken().equals(row.get("attempt_token")) || !"IN_PROGRESS".equals(row.get("status"))
                || !((java.sql.Timestamp)row.get("lease_expires_at")).toInstant().isAfter(clock.instant())) {
            throw conflict("CHAT_ATTEMPT_EXPIRED", "This processing attempt expired; retry the request");
        }
        repository.complete(session, turn.getRequestId(), turn.getAttemptToken(), response, context);
        log.info("Completed chat request {} outcome={}", turn.getRequestId(), response.getOutcome());
        return response;
    }

    @Override
    @Transactional
    public void fail(String session, ChatTurnDto turn, String code) {
        // The attempt predicate prevents a late worker from failing a newer retry; deleted sessions are a no-op.
        repository.fail(session, turn.getRequestId(), turn.getAttemptToken(), code);
        log.warn("Chat request {} failed: {}", turn.getRequestId(), code);
    }

    @Override
    @Transactional(readOnly = true)
    public ChatHistoryDto history(String session, Long before, int limit) {
        sessions.requireActiveSession(session);
        if (limit < 1 || limit > 100 || before != null && before < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "limit must be 1-100 and before must be positive");
        }
        var rows = repository.history(session, before, limit + 1);
        var result = new ChatHistoryDto();
        if (rows.size() > limit) {
            rows.removeFirst();
            result.setNextBefore(rows.getFirst().getId());
        }
        result.setMessages(rows);
        log.debug("Loaded {} chat history messages", rows.size());
        return result;
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
