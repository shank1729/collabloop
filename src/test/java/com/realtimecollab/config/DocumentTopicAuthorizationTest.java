package com.realtimecollab.config;

import com.realtimecollab.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentTopicAuthorizationTest {
    private DocumentRepository repository;
    private DocumentTopicAuthorization authorization;

    @BeforeEach
    void setup() {
        repository = mock(DocumentRepository.class);
        authorization = new DocumentTopicAuthorization(repository);
    }

    private StompHeaderAccessor subscription(String destination) {
        var accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("session-1");
        accessor.setUser(() -> "person@example.com");
        accessor.setDestination(destination);
        return accessor;
    }

    private Message<?> delivery(String destination) {
        var accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId("session-1");
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void rejectsSubscriptionsWithoutDocumentAccessIncludingPresence() {
        assertThatThrownBy(() -> authorization.authorizeSubscription(subscription("/topic/documents/42")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authorization.authorizeSubscription(subscription("/topic/documents/42/presence")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authorization.authorizeSubscription(subscription("/queue/document-access-user-other")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authorization.authorizeSubscription(subscription("/topic/documents/*")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rechecksDeliveryForAlreadySubscribedUserAfterRevocation() {
        when(repository.canRead(42L, "person@example.com")).thenReturn(true);
        authorization.authorizeSubscription(subscription("/topic/documents/42"));
        assertThat(authorization.allowDelivery(delivery("/topic/documents/42"))).isTrue();
        when(repository.canRead(42L, "person@example.com")).thenReturn(false);
        assertThat(authorization.allowDelivery(delivery("/topic/documents/42"))).isFalse();
        assertThat(authorization.allowDelivery(delivery("/topic/documents/42/presence"))).isFalse();
    }

    @Test
    void allowsPrivateAccessNotificationButNotUnregisteredDocumentDelivery() {
        assertThatCode(() -> authorization.authorizeSubscription(subscription("/user/queue/document-access")))
                .doesNotThrowAnyException();
        assertThat(authorization.allowDelivery(delivery("/queue/document-access-usersession-1"))).isTrue();
        assertThat(authorization.allowDelivery(delivery("/topic/documents/42"))).isFalse();
    }

    @Test
    void clientsCannotForgeDocumentOrAccessNotifications() {
        assertThatThrownBy(() -> authorization.authorizeSend("/topic/documents/42"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authorization.authorizeSend("/queue/document-access-user-other"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatCode(() -> authorization.authorizeSend("/app/editDocument")).doesNotThrowAnyException();
        assertThatCode(() -> authorization.authorizeSend("/app/documents/42/presence/join")).doesNotThrowAnyException();
    }
}
