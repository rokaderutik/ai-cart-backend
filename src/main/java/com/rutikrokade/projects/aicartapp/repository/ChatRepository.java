package com.rutikrokade.projects.aicartapp.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class ChatRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Map<String, Object> request(String session, UUID request) {
        var rows = jdbc.queryForList("SELECT *, response_json::text AS response_text FROM chat_request WHERE session_id = ? AND request_id = ?", session, request);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void expireLeases(String session, Instant now) {
        jdbc.update("UPDATE chat_request SET status='FAILED', error_code='CHAT_ATTEMPT_EXPIRED', updated_at=? WHERE session_id=? AND status='IN_PROGRESS' AND lease_expires_at <= ?",
                java.sql.Timestamp.from(now), session, java.sql.Timestamp.from(now));
    }

    public boolean busy(String session) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM chat_request WHERE session_id=? AND status='IN_PROGRESS')", Boolean.class, session));
    }

    public boolean hasNewer(String session, long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM chat_request WHERE session_id=? AND id>?)", Boolean.class, session, id));
    }

    public void claim(String session, ChatRequestDto request, String hash, UUID attempt, Instant now, boolean retry) {
        if (retry) {
            jdbc.update("UPDATE chat_request SET status='IN_PROGRESS', attempt_token=?, lease_expires_at=?, updated_at=?, error_code=NULL WHERE session_id=? AND request_id=?",
                    attempt, java.sql.Timestamp.from(now.plusSeconds(300)), java.sql.Timestamp.from(now), session, request.getRequestId());
        } else {
            jdbc.update("INSERT INTO chat_request(session_id,request_id,message_hash,status,attempt_token,lease_expires_at) VALUES(?,?,?,'IN_PROGRESS',?,?)",
                    session, request.getRequestId(), hash, attempt, java.sql.Timestamp.from(now.plusSeconds(300)));
            jdbc.update("INSERT INTO chat_message(session_id,request_id,role,content) VALUES(?,?,'USER',?)", session, request.getRequestId(), request.getMessage());
        }
    }

    public ChatContextDto context(String session) {
        return json.readValue(jdbc.queryForObject("SELECT context_json::text FROM guest_session WHERE id=?", String.class, session), ChatContextDto.class);
    }

    public List<Map<String, Object>> catalog() {
        return jdbc.queryForList("SELECT id, name FROM menu_item WHERE source_active AND source_key IS NOT NULL ORDER BY id");
    }

    public List<ChatMessageDto> recentCompleted(String session) {
        var rows = jdbc.query("""
                SELECT m.*, r.status FROM chat_message m JOIN chat_request r USING(session_id,request_id)
                WHERE m.session_id=? AND r.status='COMPLETED' ORDER BY m.id DESC LIMIT 10
                """, this::message, session);
        Collections.reverse(rows);
        return rows;
    }

    public List<ChatMessageDto> history(String session, Long before, int limit) {
        var args = new ArrayList<Object>();
        args.add(session);
        String bound = "";
        if (before != null) { bound = " AND m.id < ?"; args.add(before); }
        args.add(limit);
        var rows = jdbc.query("SELECT m.*, r.status FROM chat_message m JOIN chat_request r USING(session_id,request_id) WHERE m.session_id=?"
                + bound + " ORDER BY m.id DESC LIMIT ?", this::message, args.toArray());
        Collections.reverse(rows);
        return rows;
    }

    public void complete(String session, UUID requestId, UUID attempt, ChatResponseDto response, ChatContextDto context) {
        Long id = jdbc.queryForObject("INSERT INTO chat_message(session_id,request_id,role,content) VALUES(?,?,'ASSISTANT',?) RETURNING id",
                Long.class, session, requestId, response.getReply());
        response.setAssistantMessageId(id);
        if (!response.getRecommendations().isEmpty()) context.setRecommendationMessageId(id);
        Map<String,Object> metadata = json.convertValue(response, new TypeReference<Map<String,Object>>() { });
        metadata.remove("reply");
        jdbc.update("UPDATE chat_message SET metadata_json=?::jsonb WHERE id=?", json.writeValueAsString(metadata), id);
        jdbc.update("UPDATE guest_session SET context_json=?::jsonb WHERE id=?", json.writeValueAsString(context), session);
        int updated = jdbc.update("UPDATE chat_request SET status='COMPLETED', response_json=?::jsonb, action_result_json=?::jsonb, updated_at=now() WHERE session_id=? AND request_id=? AND attempt_token=? AND status='IN_PROGRESS'",
                json.writeValueAsString(response), response.getActionResult() == null ? null : json.writeValueAsString(response.getActionResult()),
                session, requestId, attempt);
        if (updated != 1) throw new IllegalStateException("Chat completion lost ownership");
    }

    public void fail(String session, UUID request, UUID attempt, String code) {
        jdbc.update("UPDATE chat_request SET status='FAILED', error_code=?, updated_at=now() WHERE session_id=? AND request_id=? AND attempt_token=? AND status='IN_PROGRESS'",
                code, session, request, attempt);
    }

    public ChatResponseDto replay(Map<String,Object> row) {
        return json.readValue((String)row.get("response_text"), ChatResponseDto.class);
    }

    private ChatMessageDto message(ResultSet rs, int row) throws SQLException {
        var message = new ChatMessageDto();
        message.setId(rs.getLong("id"));
        message.setRequestId(rs.getObject("request_id", UUID.class));
        message.setRole(rs.getString("role"));
        message.setContent(rs.getString("content"));
        message.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        message.setRequestStatus(rs.getString("status"));
        message.setMetadata(json.readValue(rs.getString("metadata_json"), new TypeReference<Map<String,Object>>() { }));
        return message;
    }
}
