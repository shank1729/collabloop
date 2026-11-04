package com.realtimecollab.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketSessionExpiryTest {
    private final MutableClock clock = new MutableClock();
    private final WebSocketSessionExpiry expiry = new WebSocketSessionExpiry(clock);

    private WebSocketSession connection(String id) {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        expiry.opened(session);
        return session;
    }

    @Test
    void blocksExactlyAtDeadlineBeforeScheduledCloseAndClosesIdleSessionOnce() throws Exception {
        var socket = connection("one");
        assertThat(expiry.isActive("one")).isFalse();
        expiry.authenticate("one", clock.instant().plusSeconds(60));
        assertThat(expiry.isActive("one")).isTrue();
        clock.now = clock.now.plusSeconds(60);
        assertThat(expiry.isActive("one")).isFalse();
        verify(socket, never()).close(any());
        expiry.closeExpiredConnections();
        expiry.closeExpiredConnections();
        verify(socket, times(1)).close(WebSocketSessionExpiry.AUTH_EXPIRED);
    }

    @Test
    void cannotReplaceAConnectionsDeadlineWithALaterToken() {
        connection("one");
        expiry.authenticate("one", clock.instant().plusSeconds(10));
        assertThatThrownBy(() -> expiry.authenticate("one", clock.instant().plusSeconds(3600)))
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        clock.now = clock.now.plusSeconds(10);
        assertThat(expiry.isActive("one")).isFalse();
    }

    @Test
    void disconnectReleasesStateAndOtherConnectionsHaveIndependentDeadlines() throws Exception {
        var first = connection("one");
        var second = connection("two");
        expiry.authenticate("one", clock.instant().plusSeconds(10));
        expiry.authenticate("two", clock.instant().plusSeconds(60));
        expiry.closed("one");
        expiry.closed("one");
        clock.now = clock.now.plusSeconds(10);
        expiry.closeExpiredConnections();
        assertThat(expiry.isActive("one")).isFalse();
        assertThat(expiry.isActive("two")).isTrue();
        verify(first, never()).close(any());
        verify(second, never()).close(any());
    }

    @Test
    void rejectsUnknownMissingOrAlreadyExpiredCredentials() {
        connection("one");
        for (Instant deadline : new Instant[] {null, clock.instant(), clock.instant().minusSeconds(1)}) {
            assertThatThrownBy(() -> expiry.authenticate("one", deadline))
                    .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        }
        assertThatThrownBy(() -> expiry.authenticate("unknown", clock.instant().plusSeconds(60)))
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        assertThat(expiry.isActive(null)).isFalse();
    }

    @Test
    void closeFailureDoesNotRestoreAccessAndIsRetriedWithoutBlockingOtherConnections() throws Exception {
        var first = connection("one");
        var second = connection("two");
        expiry.authenticate("one", clock.instant().plusSeconds(10));
        expiry.authenticate("two", clock.instant().plusSeconds(10));
        doThrow(new IOException("Transport unavailable")).doNothing().when(first).close(any());
        clock.now = clock.now.plusSeconds(10);
        expiry.closeExpiredConnections();
        assertThat(expiry.isActive("one")).isFalse();
        expiry.closeExpiredConnections();
        verify(first, times(2)).close(WebSocketSessionExpiry.AUTH_EXPIRED);
        verify(second, times(1)).close(WebSocketSessionExpiry.AUTH_EXPIRED);
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
