package com.realtimecollab.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class WebSocketSessionExpiry {
    public static final CloseStatus AUTH_EXPIRED = new CloseStatus(4001, "Authentication expired");
    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionExpiry.class);
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Clock clock;

    public WebSocketSessionExpiry() { this(Clock.systemUTC()); }

    WebSocketSessionExpiry(Clock clock) { this.clock = clock; }

    public void opened(WebSocketSession session) {
        connections.put(session.getId(), new Connection(session));
    }

    public void closed(String sessionId) { connections.remove(sessionId); }

    public void authenticate(String sessionId, Instant expiresAt) {
        Connection connection = sessionId == null ? null : connections.get(sessionId);
        if (connection == null || expiresAt == null || !clock.instant().isBefore(expiresAt)) {
            throw new BadCredentialsException("WebSocket token expired or connection unavailable.");
        }
        synchronized (connection) {
            if (connection.expiresAt != null) {
                throw new BadCredentialsException("A WebSocket connection cannot authenticate twice.");
            }
            connection.expiresAt = expiresAt;
        }
    }

    public boolean isActive(String sessionId) {
        Connection connection = sessionId == null ? null : connections.get(sessionId);
        return connection != null && connection.expiresAt != null
                && clock.instant().isBefore(connection.expiresAt) && !connection.closing.get();
    }

    public void closeIfExpired(String sessionId) {
        Connection connection = sessionId == null ? null : connections.get(sessionId);
        if (connection == null || connection.expiresAt == null || clock.instant().isBefore(connection.expiresAt)) return;
        if (!connection.closing.compareAndSet(false, true)) return;
        try {
            connection.session.close(AUTH_EXPIRED);
        } catch (IOException ex) {
            connection.closing.set(false); // Messages remain blocked; a later sweep retries closing.
            log.warn("Unable to close expired WebSocket session {}.", sessionId);
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void closeExpiredConnections() {
        connections.keySet().forEach(this::closeIfExpired);
    }

    private static class Connection {
        final WebSocketSession session;
        final AtomicBoolean closing = new AtomicBoolean();
        volatile Instant expiresAt;

        Connection(WebSocketSession session) { this.session = session; }
    }
}
