package com.realtimecollab.config;

import com.realtimecollab.dto.DocumentAccessChangedEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DocumentAccessEventListener {
    private final SimpMessagingTemplate messaging;

    public DocumentAccessEventListener(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener
    public void accessChanged(DocumentAccessChangedEvent event) {
        messaging.convertAndSendToUser(event.email(), "/queue/document-access", event);
    }
}
