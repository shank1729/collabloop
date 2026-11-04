package com.realtimecollab.config;

import com.realtimecollab.security.CustomUserDetailsService;
import com.realtimecollab.security.JwtService;
import com.realtimecollab.security.WebSocketSessionExpiry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.userdetails.User;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class WebSocketConfigTest {
    static class Registration extends ChannelRegistration {
        List<ChannelInterceptor> interceptors() { return getInterceptors(); }
    }

    @Test
    void authenticationPreservesSpringUserChangeCallbackForPrivateNotifications() {
        var jwt = mock(JwtService.class);
        var users = mock(CustomUserDetailsService.class);
        var user = User.withUsername("person@example.com").password("unused").authorities("USER").build();
        when(jwt.extractUsername("token")).thenReturn(user.getUsername());
        when(users.loadUserByUsername(user.getUsername())).thenReturn(user);
        when(jwt.isTokenValid("token", user)).thenReturn(true);
        when(jwt.extractExpiration("token")).thenReturn(java.time.Instant.now().plusSeconds(60));
        var expiry = mock(WebSocketSessionExpiry.class);
        when(expiry.isActive("session-1")).thenReturn(true);
        var config = new WebSocketConfig(jwt, users, mock(DocumentTopicAuthorization.class), new AllowedOrigins("http://localhost:3000"), expiry);
        var registration = new Registration();
        config.configureClientInboundChannel(registration);
        var accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("session-1");
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setNativeHeader("Authorization", "Bearer token");
        var notified = new AtomicBoolean();
        accessor.setUserChangeCallback(principal -> notified.set(true));
        accessor.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        registration.interceptors().get(0).preSend(message, mock(org.springframework.messaging.MessageChannel.class));
        assertThat(notified).isTrue();
        assertThat(accessor.getUser().getName()).isEqualTo(user.getUsername());
        verify(expiry).authenticate(eq("session-1"), any());
    }

    @Test
    void blocksExpiredIncomingFramesIncludingFramesThatExpiredWhileQueued() {
        var expiry = mock(WebSocketSessionExpiry.class);
        var topics = mock(DocumentTopicAuthorization.class);
        var jwt = mock(JwtService.class);
        var config = new WebSocketConfig(jwt, mock(CustomUserDetailsService.class), topics,
                new AllowedOrigins("http://localhost:3000"), expiry);
        var registration = new Registration();
        config.configureClientInboundChannel(registration);
        var interceptor = (org.springframework.messaging.support.ExecutorChannelInterceptor) registration.interceptors().get(0);
        for (var command : List.of(StompCommand.SEND, StompCommand.SUBSCRIBE)) {
            var accessor = StompHeaderAccessor.create(command);
            accessor.setSessionId("expired");
            accessor.setUser(() -> "person@example.com");
            accessor.setNativeHeader("Authorization", "Bearer replacement-token");
            accessor.setDestination("/app/editDocument");
            var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
            assertThatThrownBy(() -> interceptor.preSend(message, mock(org.springframework.messaging.MessageChannel.class)))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(interceptor.beforeHandle(message, mock(org.springframework.messaging.MessageChannel.class),
                    mock(org.springframework.messaging.MessageHandler.class))).isNull();
        }
        verifyNoInteractions(jwt, topics); // SEND headers cannot replace the CONNECT token.
    }

    @Test
    void blocksExpiredPublicAndPrivateDeliveryAtBothQueueBoundaries() {
        var expiry = mock(WebSocketSessionExpiry.class);
        var topics = mock(DocumentTopicAuthorization.class);
        when(topics.allowDelivery(any())).thenReturn(true);
        var config = new WebSocketConfig(mock(JwtService.class), mock(CustomUserDetailsService.class), topics,
                new AllowedOrigins("http://localhost:3000"), expiry);
        var registration = new Registration();
        config.configureClientOutboundChannel(registration);
        var interceptor = (org.springframework.messaging.support.ExecutorChannelInterceptor) registration.interceptors().get(0);
        for (String destination : List.of("/topic/documents/1", "/queue/document-access-usersession-1")) {
            var accessor = org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(
                    org.springframework.messaging.simp.SimpMessageType.MESSAGE);
            accessor.setSessionId("session-1");
            accessor.setDestination(destination);
            var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
            when(expiry.isActive("session-1")).thenReturn(true);
            assertThat(interceptor.preSend(message, mock(org.springframework.messaging.MessageChannel.class))).isSameAs(message);
            when(expiry.isActive("session-1")).thenReturn(false);
            assertThat(interceptor.beforeHandle(message, mock(org.springframework.messaging.MessageChannel.class),
                    mock(org.springframework.messaging.MessageHandler.class))).isNull();
            assertThat(interceptor.preSend(message, mock(org.springframework.messaging.MessageChannel.class))).isNull();
        }
    }
}
