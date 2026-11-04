package com.realtimecollab.service;

import com.realtimecollab.dto.DocumentPresenceResponse;
import com.realtimecollab.entity.User;
import com.realtimecollab.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import java.time.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentPresenceLifecycleTest {
    private DocumentPresenceService presence;
    private DocumentService documents;
    private ApplicationEventPublisher events;
    private final AtomicReference<Instant> now = new AtomicReference<>();

    @BeforeEach
    void setup() {
        now.set(Instant.parse("2026-10-31T10:00:00Z"));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(ignored -> now.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        var users = mock(UserRepository.class);
        var user = new User();
        user.setId(1L);
        user.setName("Owner");
        user.setEmail("owner@example.com");
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        documents = mock(DocumentService.class);
        events = mock(ApplicationEventPublisher.class);
        presence = new DocumentPresenceService(users, documents, events, clock);
    }

    @Test
    void switchingDocumentsCleansOldPresenceAndLateLeaveCannotRemoveNewPresence() {
        presence.join(1L, "session", "owner@example.com");
        presence.join(2L, "session", "owner@example.com");
        assertThat(presence.snapshot(1L).getActiveUsers()).isEmpty();
        assertThat(presence.leave(1L, "session")).isNull();
        assertThat(presence.snapshot(2L).getActiveUsers()).hasSize(1);
        verify(events).publishEvent(argThat((DocumentPresenceResponse response) ->
                response.getDocumentId().equals(1L) && response.getActiveUsers().isEmpty()));
    }

    @Test
    void multipleTabsCountOnceAndOneTypingTabTakesPrecedence() {
        presence.join(1L, "tab-1", "owner@example.com");
        presence.join(1L, "tab-2", "owner@example.com");
        presence.markTyping(1L, "tab-1", "owner@example.com");
        presence.markViewing(1L, "tab-2", "owner@example.com");
        assertThat(presence.snapshot(1L).getActiveUsers()).singleElement()
                .extracting("status").isEqualTo("TYPING");
        presence.leave("tab-1");
        assertThat(presence.snapshot(1L).getActiveUsers()).singleElement()
                .extracting("status").isEqualTo("VIEWING");
        presence.leave("tab-2");
        assertThat(presence.snapshot(1L).getActiveUsers()).isEmpty();
        assertThat(presence.leave("tab-2")).isNull();
    }

    @Test
    void typingExpiresIndependentlyOfHeartbeatsAndDeadSessionsAreRemoved() {
        presence.markTyping(1L, "session", "owner@example.com");
        now.set(now.get().plusSeconds(3));
        presence.heartbeat(1L, "session", "owner@example.com");
        now.set(now.get().plusSeconds(1));
        presence.expireStaleSessions();
        assertThat(presence.snapshot(1L).getActiveUsers()).singleElement()
                .extracting("status").isEqualTo("VIEWING");
        now.set(now.get().plusSeconds(44));
        presence.expireStaleSessions();
        assertThat(presence.snapshot(1L).getActiveUsers()).isEmpty();
        assertThat(presence.leave("session")).isNull();
    }

    @Test
    void heartbeatKeepsIdleViewerPresent() {
        presence.join(1L, "session", "owner@example.com");
        now.set(now.get().plusSeconds(40));
        presence.heartbeat(1L, "session", "owner@example.com");
        now.set(now.get().plusSeconds(40));
        presence.expireStaleSessions();
        assertThat(presence.snapshot(1L).getActiveUsers()).hasSize(1);
    }

    @Test
    void downgradeClearsTypingAndRevocationRemovesAllTabsForOnlyThatDocument() {
        presence.markTyping(1L, "tab-1", "owner@example.com");
        presence.markTyping(1L, "tab-2", "owner@example.com");
        presence.join(2L, "tab-3", "owner@example.com");
        presence.accessChanged(1L, "owner@example.com", "VIEWER");
        assertThat(presence.snapshot(1L).getActiveUsers()).singleElement()
                .extracting("status").isEqualTo("VIEWING");
        presence.accessChanged(1L, "owner@example.com", null);
        assertThat(presence.snapshot(1L).getActiveUsers()).isEmpty();
        assertThat(presence.snapshot(2L).getActiveUsers()).hasSize(1);
    }

    @Test
    void viewerCannotClaimToBeTyping() {
        doThrow(new AccessDeniedException("Viewer access"))
                .when(documents).requireEditorAccess(1L, "owner@example.com");
        assertThatThrownBy(() -> presence.markTyping(1L, "session", "owner@example.com"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(presence.snapshot(1L).getActiveUsers()).isEmpty();
        verifyNoInteractions(events);
    }
}
