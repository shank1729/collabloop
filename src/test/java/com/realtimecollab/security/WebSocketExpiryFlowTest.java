package com.realtimecollab.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimecollab.service.DocumentPresenceService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.datasource.url=jdbc:h2:mem:socket_expiry_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Import(WebSocketExpiryFlowTest.ControlledExpiry.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebSocketExpiryFlowTest {
    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper mapper;
    @Autowired private TestClock clock;
    @Autowired private DocumentPresenceService presence;
    @LocalServerPort private int port;
    @Value("${app.jwt.secret}") private String secret;

    @Test
    @Timeout(60)
    void idleExpiredEditorDisconnectsAndLeavesPresenceWhileOwnerContinuesEditing() throws Exception {
        var owner = register("Expiry Owner", "expiry-owner@example.com");
        var editor = register("Expiry Editor", "expiry-editor@example.com");
        String ownerToken = owner.path("token").asText();
        var created = request("/documents", HttpMethod.POST, Map.of("title", "Expiry notes", "content", "Initial"), ownerToken);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = created.getBody().path("id").asLong();
        assertThat(request("/documents/" + id + "/share", HttpMethod.POST,
                Map.of("email", editor.path("email").asText(), "role", "EDITOR"), ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        String editorToken = Jwts.builder().subject(editor.path("email").asText())
                .expiration(Date.from(clock.instant().plusSeconds(1800)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();

        var client = new WebSocketStompClient(new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        var converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(mapper);
        client.setMessageConverter(converter);
        client.setDefaultHeartbeat(new long[] {0, 0});
        StompSession ownerSocket = null, editorSocket = null;
        try {
            ownerSocket = connect(client, ownerToken, new StompSessionHandlerAdapter() {});
            var lost = new CompletableFuture<Throwable>();
            editorSocket = connect(client, editorToken, new StompSessionHandlerAdapter() {
                @Override public void handleTransportError(StompSession session, Throwable error) { lost.complete(error); }
            });
            var updates = new Inbox();
            var active = new Inbox();
            ownerSocket.subscribe("/topic/documents/" + id, updates);
            ownerSocket.subscribe("/topic/documents/" + id + "/presence", active);
            ownerSocket.send("/app/documents/" + id + "/presence/join", Map.of());
            active.await(message -> message.path("activeUsers").size() == 1);
            editorSocket.send("/app/documents/" + id + "/presence/join", Map.of());
            active.await(message -> message.path("activeUsers").size() == 2);

            // The scheduled sweep sees an expired JWT deadline without a real 30-minute wait.
            clock.advance(Duration.ofMinutes(31));
            assertThat(lost.get(10, TimeUnit.SECONDS)).isNotNull();
            active.await(message -> message.path("activeUsers").size() == 1);
            assertThat(editorSocket.isConnected()).isFalse();
            assertThat(presence.snapshot(id).getActiveUsers()).hasSize(1);
            ownerSocket.send("/app/editDocument", Map.of("id", id, "content", "Owner still connected"));
            updates.await(message -> message.path("content").asText().equals("Owner still connected"));
            assertThat(request("/documents/" + id, HttpMethod.GET, null, ownerToken)
                    .getBody().path("content").asText()).isEqualTo("Owner still connected");
        } finally {
            if (ownerSocket != null && ownerSocket.isConnected()) ownerSocket.disconnect();
            if (editorSocket != null && editorSocket.isConnected()) editorSocket.disconnect();
            client.stop();
        }
    }

    private JsonNode register(String name, String email) {
        var response = request("/auth/register", HttpMethod.POST,
                Map.of("name", name, "email", email, "password", "secret123"), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> request(String path, HttpMethod method, Object body, String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private StompSession connect(WebSocketStompClient client, String token, StompSessionHandler handler) throws Exception {
        var headers = new StompHeaders();
        headers.set("Authorization", "Bearer " + token);
        var handshake = new WebSocketHttpHeaders();
        handshake.setOrigin("http://localhost:3000");
        return client.connectAsync("ws://localhost:" + port + "/ws", handshake, headers, handler).get(10, TimeUnit.SECONDS);
    }

    private static class Inbox implements StompFrameHandler {
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        @Override public Type getPayloadType(StompHeaders headers) { return JsonNode.class; }
        @Override public void handleFrame(StompHeaders headers, Object payload) { messages.add((JsonNode) payload); }
        void await(Predicate<JsonNode> expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                JsonNode message = messages.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (message != null && expected.test(message)) return;
            }
            throw new AssertionError("Timed out waiting for expected WebSocket message");
        }
    }

    static class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledExpiry {
        @Bean TestClock expiryClock() { return new TestClock(); }
        @Bean @Primary WebSocketSessionExpiry controlledSessionExpiry(TestClock clock) { return new WebSocketSessionExpiry(clock); }
    }
}
