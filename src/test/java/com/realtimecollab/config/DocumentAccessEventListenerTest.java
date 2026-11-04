package com.realtimecollab.config;

import com.realtimecollab.dto.DocumentAccessChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.*;

class DocumentAccessEventListenerTest {
    @Test
    void notifiesOnlyAfterCommitAndNeverAfterRollback() {
        var messaging = mock(SimpMessagingTemplate.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SimpMessagingTemplate.class, () -> messaging);
            context.registerBean(DocumentAccessEventListener.class);
            context.registerBean(TransactionalEventListenerFactory.class);
            context.refresh();
            var transactions = new TransactionTemplate(new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
                @Override protected void doCommit(DefaultTransactionStatus status) { }
                @Override protected void doRollback(DefaultTransactionStatus status) { }
            });
            var event = new DocumentAccessChangedEvent(1L, "person@example.com", "VIEWER");
            transactions.executeWithoutResult(status -> {
                context.publishEvent(event);
                verifyNoInteractions(messaging);
            });
            verify(messaging).convertAndSendToUser(event.email(), "/queue/document-access", event);
            clearInvocations(messaging);
            transactions.executeWithoutResult(status -> {
                context.publishEvent(event);
                status.setRollbackOnly();
            });
            verifyNoInteractions(messaging);
        }
    }
}
