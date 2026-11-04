package com.realtimecollab.config;

import com.realtimecollab.security.CustomUserDetailsService;
import com.realtimecollab.security.JwtService;
import com.realtimecollab.security.WebSocketSessionExpiry;
import io.jsonwebtoken.JwtException;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSocketMessageBroker
@EnableScheduling
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;
    private final AllowedOrigins allowedOrigins;
    private final DocumentTopicAuthorization topicAuthorization;
    private final WebSocketSessionExpiry sessionExpiry;

    public WebSocketConfig(
            JwtService jwtService,
            CustomUserDetailsService userDetailsService,
            DocumentTopicAuthorization topicAuthorization,
            AllowedOrigins allowedOrigins,
            WebSocketSessionExpiry sessionExpiry) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.topicAuthorization = topicAuthorization;
        this.allowedOrigins = allowedOrigins;
        this.sessionExpiry = sessionExpiry;
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessionExpiry.opened(session);
                try { super.afterConnectionEstablished(session); }
                catch (Exception ex) { sessionExpiry.closed(session.getId()); throw ex; }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessionExpiry.closed(session.getId());
                super.afterConnectionClosed(session, status);
            }
        });
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setPreserveReceiveOrder(true);
       
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins.values().toArray(String[]::new))
                .addInterceptors(new TokenHandshakeInterceptor())
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ExecutorChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) accessor = StompHeaderAccessor.wrap(message);

                if (StompCommand.CONNECT.equals(accessor.getCommand())
                        || StompCommand.SEND.equals(accessor.getCommand())
                        || StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    if (StompCommand.CONNECT.equals(accessor.getCommand())) authenticateInboundFrame(accessor);
                    if (accessor.getUser() == null || !sessionExpiry.isActive(accessor.getSessionId())) {
                        sessionExpiry.closeIfExpired(accessor.getSessionId());
                        throw new org.springframework.security.access.AccessDeniedException("WebSocket authentication required.");
                    }
                    if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                        topicAuthorization.authorizeSubscription(accessor);
                    }
                    if (StompCommand.SEND.equals(accessor.getCommand())) {
                        topicAuthorization.authorizeSend(accessor.getDestination());
                    }
                }

                return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
            }

            @Override
            public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
                var accessor = StompHeaderAccessor.wrap(message);
                if ((StompCommand.SEND.equals(accessor.getCommand()) || StompCommand.SUBSCRIBE.equals(accessor.getCommand()))
                        && !sessionExpiry.isActive(accessor.getSessionId())) {
                    // A frame can expire while waiting on the inbound executor.
                    sessionExpiry.closeIfExpired(accessor.getSessionId());
                    return null;
                }
                return message;
            }
        });
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ExecutorChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                return allowsAuthenticatedDelivery(message) && topicAuthorization.allowDelivery(message) ? message : null;
            }

            @Override
            public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
                return allowsAuthenticatedDelivery(message) ? message : null;
            }
        });
    }

    private boolean allowsAuthenticatedDelivery(Message<?> message) {
        var accessor = SimpMessageHeaderAccessor.wrap(message);
        return accessor.getMessageType() != SimpMessageType.MESSAGE || sessionExpiry.isActive(accessor.getSessionId());
    }

    private void authenticateInboundFrame(StompHeaderAccessor accessor) {
        String authHeader = firstNativeHeader(accessor, "Authorization");
        String jwt = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwt = authHeader.substring(7);
        } else if (accessor.getSessionAttributes() != null) {
            Object token = accessor.getSessionAttributes().get("token");
            jwt = token instanceof String ? (String) token : null;
        }

        if (jwt == null || jwt.isBlank()) {
            log.warn("WebSocket {} frame did not include a JWT.", accessor.getCommand());
            return;
        }

        try {
            String username = jwtService.extractUsername(jwt);
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            if (jwtService.isTokenValid(jwt, userDetails)) {
                sessionExpiry.authenticate(accessor.getSessionId(), jwtService.extractExpiration(jwt));
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );
                accessor.setUser(authentication);
                if (accessor.getSessionAttributes() != null) accessor.getSessionAttributes().remove("token");
                log.debug("Authenticated WebSocket {} frame for {}.", accessor.getCommand(), username);
            }
        } catch (JwtException | IllegalArgumentException | AuthenticationException ex) {
            accessor.setUser(null);
            log.warn("Rejected WebSocket {} frame with invalid JWT.", accessor.getCommand());
        }
    }

    private String firstNativeHeader(StompHeaderAccessor accessor, String headerName) {
        List<String> values = accessor.getNativeHeader(headerName);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static class TokenHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Map<String, Object> attributes) {
            String query = request.getURI().getRawQuery();
            if (query == null || query.isBlank()) {
                return true;
            }

            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && "token".equals(parts[0])) {
                    attributes.put("token", URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                    break;
                }
            }

            return true;
        }

        @Override
        public void afterHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Exception exception) {
        }
    }
}
