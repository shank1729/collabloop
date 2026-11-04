package com.realtimecollab.config;

import com.realtimecollab.dto.DocumentPresenceResponse;
import com.realtimecollab.service.DocumentPresenceService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import com.realtimecollab.dto.DocumentAccessChangedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class WebSocketPresenceEventListener {
    private final DocumentPresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketPresenceEventListener(
            DocumentPresenceService presenceService,
            SimpMessagingTemplate messagingTemplate) {
        this.presenceService = presenceService;
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        presenceService.leave(event.getSessionId());
    }

    @EventListener
    public void presenceChanged(DocumentPresenceResponse response) {
        messagingTemplate.convertAndSend("/topic/documents/" + response.getDocumentId() + "/presence", response);
    }

    @Scheduled(fixedDelay = 1000)
    public void expirePresence() {
        presenceService.expireStaleSessions();
    }

    @TransactionalEventListener
    public void accessChanged(DocumentAccessChangedEvent event) {
        presenceService.accessChanged(event.documentId(), event.email(), event.role());
    }
}
