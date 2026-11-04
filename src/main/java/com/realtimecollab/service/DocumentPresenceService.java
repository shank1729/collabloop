package com.realtimecollab.service;

import com.realtimecollab.dto.DocumentPresenceMessage;
import com.realtimecollab.dto.DocumentPresenceResponse;
import com.realtimecollab.entity.User;
import com.realtimecollab.exception.ResourceNotFoundException;
import com.realtimecollab.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class DocumentPresenceService {
    static final Duration SESSION_TTL = Duration.ofSeconds(45);
    static final Duration TYPING_TTL = Duration.ofSeconds(4);
    private final UserRepository users;
    private final DocumentService documents;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Map<String, PresenceSession> sessions = new HashMap<>();

    @Autowired
    public DocumentPresenceService(UserRepository users, DocumentService documents, ApplicationEventPublisher events) {
        this(users, documents, events, Clock.systemDefaultZone());
    }

    DocumentPresenceService(UserRepository users, DocumentService documents, ApplicationEventPublisher events, Clock clock) {
        this.users = users;
        this.documents = documents;
        this.events = events;
        this.clock = clock;
    }

    public synchronized DocumentPresenceResponse join(Long documentId, String sessionId, String email) {
        return update(documentId, sessionId, email, "VIEWING");
    }

    public synchronized DocumentPresenceResponse markTyping(Long documentId, String sessionId, String email) {
        documents.requireEditorAccess(documentId, email);
        return update(documentId, sessionId, email, "TYPING");
    }

    public synchronized DocumentPresenceResponse markViewing(Long documentId, String sessionId, String email) {
        return update(documentId, sessionId, email, "VIEWING");
    }

    public synchronized DocumentPresenceResponse heartbeat(Long documentId, String sessionId, String email) {
        return update(documentId, sessionId, email, "HEARTBEAT");
    }

    private DocumentPresenceResponse update(Long documentId, String sessionId, String email, String status) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Session id is required.");
        documents.getById(documentId, email);
        User user = users.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
        LocalDateTime now = LocalDateTime.now(clock);
        PresenceSession previous = sessions.get(sessionId);
        boolean sameDocument = previous != null && previous.documentId().equals(documentId);
        LocalDateTime typedAt = "TYPING".equals(status) ? now :
                "HEARTBEAT".equals(status) && sameDocument ? previous.typedAt() : null;
        sessions.put(sessionId, new PresenceSession(documentId, user.getId(), user.getName(), user.getEmail(),
                sameDocument ? previous.joinedAt() : now, now, typedAt));
        if (previous != null && !sameDocument) publish(previous.documentId());
        return publish(documentId);
    }

    public synchronized DocumentPresenceResponse leave(String sessionId) {
        PresenceSession previous = sessions.remove(sessionId);
        return previous == null ? null : publish(previous.documentId());
    }

    public synchronized DocumentPresenceResponse leave(Long documentId, String sessionId) {
        PresenceSession previous = sessions.get(sessionId);
        // A delayed leave for the old document must not evict a newer selection.
        return previous != null && previous.documentId().equals(documentId) ? leave(sessionId) : null;
    }

    public synchronized void accessChanged(Long documentId, String email, String role) {
        boolean changed = false;
        var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var session = entry.getValue();
            if (!session.documentId().equals(documentId) || !session.email().equalsIgnoreCase(email)) continue;
            if (role == null) {
                iterator.remove();
                changed = true;
            } else if ("VIEWER".equals(role) && session.typedAt() != null) {
                entry.setValue(session.withoutTyping());
                changed = true;
            }
        }
        if (changed) publish(documentId);
    }

    public synchronized void expireStaleSessions() {
        LocalDateTime now = LocalDateTime.now(clock);
        Set<Long> changed = new HashSet<>();
        var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var session = entry.getValue();
            if (!session.lastSeenAt().plus(SESSION_TTL).isAfter(now)) {
                iterator.remove();
                changed.add(session.documentId());
            } else if (session.typedAt() != null && !session.typedAt().plus(TYPING_TTL).isAfter(now)) {
                entry.setValue(session.withoutTyping());
                changed.add(session.documentId());
            }
        }
        changed.forEach(this::publish);
    }

    public synchronized DocumentPresenceResponse snapshot(Long documentId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, DocumentPresenceMessage> people = new HashMap<>();
        for (var session : sessions.values()) {
            if (!session.documentId().equals(documentId) || !session.lastSeenAt().plus(SESSION_TTL).isAfter(now)) continue;
            String status = session.typedAt() != null && session.typedAt().plus(TYPING_TTL).isAfter(now) ? "TYPING" : "VIEWING";
            var person = people.computeIfAbsent(session.userId(), ignored -> {
                var message = new DocumentPresenceMessage();
                message.setUserId(session.userId());
                message.setName(session.name());
                message.setEmail(session.email());
                message.setStatus("VIEWING");
                message.setJoinedAt(session.joinedAt());
                message.setLastSeenAt(session.lastSeenAt());
                return message;
            });
            if ("TYPING".equals(status)) person.setStatus("TYPING");
            if (session.joinedAt().isBefore(person.getJoinedAt())) person.setJoinedAt(session.joinedAt());
            if (session.lastSeenAt().isAfter(person.getLastSeenAt())) person.setLastSeenAt(session.lastSeenAt());
        }
        var response = new DocumentPresenceResponse();
        response.setDocumentId(documentId);
        response.setActiveUsers(people.values().stream().sorted(Comparator.comparing(DocumentPresenceMessage::getName)
                .thenComparing(DocumentPresenceMessage::getEmail)).toList());
        response.setUpdatedAt(now);
        return response;
    }

    private DocumentPresenceResponse publish(Long documentId) {
        var response = snapshot(documentId);
        events.publishEvent(response);
        return response;
    }

    private record PresenceSession(Long documentId, Long userId, String name, String email,
                                   LocalDateTime joinedAt, LocalDateTime lastSeenAt, LocalDateTime typedAt) {
        PresenceSession withoutTyping() {
            return new PresenceSession(documentId, userId, name, email, joinedAt, lastSeenAt, null);
        }
    }
}
