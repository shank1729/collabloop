package com.realtimecollab.config;

import com.realtimecollab.repository.DocumentRepository;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class DocumentTopicAuthorization {
    private static final Pattern TOPIC = Pattern.compile("^/topic/documents/(\\d+)(?:/presence)?$");
    private final DocumentRepository documents;
    private final Map<String, String> usersBySession = new ConcurrentHashMap<>();

    public DocumentTopicAuthorization(DocumentRepository documents) {
        this.documents = documents;
    }

    public void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (accessor.getUser() == null || accessor.getSessionId() == null || destination == null) {
            throw new AccessDeniedException("Authentication required for subscriptions.");
        }
        String email = accessor.getUser().getName();
        if (!"/user/queue/document-access".equals(destination)) {
            var match = TOPIC.matcher(destination);
            if (!match.matches() || !canRead(match.group(1), email)) {
                throw new AccessDeniedException("Document access denied.");
            }
        }
        usersBySession.put(accessor.getSessionId(), email);
    }

    public void authorizeSend(String destination) {
        if (!"/app/editDocument".equals(destination) &&
                (destination == null || !destination.matches("^/app/documents/\\d+/presence/(join|typing|viewing|heartbeat|leave)$"))) {
            throw new AccessDeniedException("Clients cannot publish to broker destinations.");
        }
    }

    public boolean allowDelivery(Message<?> message) {
        var accessor = SimpMessageHeaderAccessor.wrap(message);
        String destination = accessor.getDestination();
        if (destination == null) return true;
        var match = TOPIC.matcher(destination);
        if (!match.matches()) return true;
        String sessionId = accessor.getSessionId();
        String email = sessionId == null ? null : usersBySession.get(sessionId);
        // Re-check delivery because subscriptions can outlive a permission grant.
        return email != null && canRead(match.group(1), email);
    }

    private boolean canRead(String id, String email) {
        try {
            return documents.canRead(Long.valueOf(id), email);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        usersBySession.remove(event.getSessionId());
    }
}
