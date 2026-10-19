package com.rutikrokade.projects.aicartapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import com.rutikrokade.projects.aicartapp.dto.SessionDto;
import com.rutikrokade.projects.aicartapp.entity.Cart;
import com.rutikrokade.projects.aicartapp.entity.GuestSession;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.repository.CartRepository;
import com.rutikrokade.projects.aicartapp.repository.GuestSessionRepository;
import com.rutikrokade.projects.aicartapp.util.ResourceIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final String ID = "SESSION_00000000-0000-4000-8000-000000000001";

    @Mock
    private GuestSessionRepository sessionRepository;
    @Mock
    private CartRepository cartRepository;
    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionServiceImpl(sessionRepository, cartRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsPrefixedSessionAndCartWithFixedLifetime() {
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SessionDto result = service.createSession();
        assertThat(ResourceIdGenerator.isSessionId(result.getSessionId())).isTrue();
        assertThat(result.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        ArgumentCaptor<Cart> cart = ArgumentCaptor.forClass(Cart.class);
        org.mockito.Mockito.verify(cartRepository).save(cart.capture());
        assertThat(cart.getValue().getId()).matches("CART_[0-9a-f-]{36}");
        assertThat(cart.getValue().getSession().getId()).isEqualTo(result.getSessionId());
        assertThat(cart.getValue().getSession().getContextJson()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "00000000-0000-4000-8000-000000000001", "SESSION_1-1-1-1-1"})
    void rejectsMalformedTokensBeforeDatabaseLookup(String token) {
        assertThatThrownBy(() -> service.requireActiveSession(token)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_SESSION");
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void rejectsUnknownSession() {
        when(sessionRepository.findById(ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireActiveSession(ID)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_SESSION");
    }

    @Test
    void rejectsSessionAtExactExpiry() {
        when(sessionRepository.findById(ID)).thenReturn(Optional.of(
                new GuestSession(ID, NOW.minusSeconds(86400), NOW, Map.of())));
        assertThatThrownBy(() -> service.requireActiveSession(ID)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("SESSION_EXPIRED");
    }

    @Test
    void activeSessionDoesNotExtendItsExpiry() {
        GuestSession session = new GuestSession(ID, NOW.minusSeconds(86399), NOW.plusSeconds(1), Map.of());
        when(sessionRepository.findById(ID)).thenReturn(Optional.of(session));
        assertThat(service.requireActiveSession(ID).getExpiresAt()).isEqualTo(NOW.plusSeconds(1));
    }
}
