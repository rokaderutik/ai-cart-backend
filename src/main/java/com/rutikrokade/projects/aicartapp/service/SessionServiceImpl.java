package com.rutikrokade.projects.aicartapp.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import com.rutikrokade.projects.aicartapp.dto.SessionDto;
import com.rutikrokade.projects.aicartapp.entity.Cart;
import com.rutikrokade.projects.aicartapp.entity.GuestSession;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.repository.CartRepository;
import com.rutikrokade.projects.aicartapp.repository.GuestSessionRepository;
import com.rutikrokade.projects.aicartapp.util.ResourceIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionServiceImpl implements SessionService {

    private static final Duration SESSION_LIFETIME = Duration.ofHours(24);

    private final GuestSessionRepository sessionRepository;
    private final CartRepository cartRepository;
    private final Clock clock;

    @Override
    @Transactional
    public SessionDto createSession() {
        log.info("Creating an anonymous session and empty cart with a 24-hour lifetime");
        // Match PostgreSQL timestamp precision so the issued expiry equals the stored value.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        GuestSession session = sessionRepository.save(new GuestSession(ResourceIdGenerator.newSessionId(),
                now, now.plus(SESSION_LIFETIME), Map.of()));
        cartRepository.save(new Cart(ResourceIdGenerator.newCartId(), session));
        log.debug("Session and cart prepared with expiry at {}", session.getExpiresAt());
        return new SessionDto(session.getId(), session.getExpiresAt());
    }

    @Override
    @Transactional(readOnly = true)
    public GuestSession requireActiveSession(String sessionId) {
        log.debug("Validating anonymous session");
        validateFormat(sessionId);
        return validateExpiry(sessionRepository.findById(sessionId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public GuestSession lockActiveSession(String sessionId) {
        log.debug("Validating and locking anonymous session for a mutation");
        validateFormat(sessionId);
        return validateExpiry(sessionRepository.findByIdForUpdate(sessionId));
    }

    @Override
    @Transactional
    public void endSession(String sessionId) {
        GuestSession session = lockActiveSession(sessionId);
        log.info("Ending anonymous session and removing its cart");
        // PostgreSQL cascades through cart and cart_item in the same transaction.
        sessionRepository.delete(session);
    }

    private void validateFormat(String sessionId) {
        if (!ResourceIdGenerator.isSessionId(sessionId)) {
            log.debug("Session validation rejected a missing or malformed token");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SESSION",
                    "A valid X-Session-Id header is required");
        }
    }

    private GuestSession validateExpiry(Optional<GuestSession> candidate) {
        GuestSession session = candidate.orElseThrow(() -> {
            log.debug("Session validation rejected an unknown or deleted session");
            return new ApiException(HttpStatus.UNAUTHORIZED,
                    "INVALID_SESSION", "Session is invalid; create a new session");
        });
        if (!session.getExpiresAt().isAfter(clock.instant())) {
            log.debug("Session validation rejected an expired session");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED",
                    "Session has expired; create a new session");
        }
        return session;
    }
}
