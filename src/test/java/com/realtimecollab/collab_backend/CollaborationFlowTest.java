package com.realtimecollab.collab_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimecollab.dto.*;
import com.realtimecollab.dto.auth.*;
import com.realtimecollab.service.DocumentPresenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${collab.test.database-url:jdbc:h2:mem:collab_wire_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CollaborationFlowTest {
    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper mapper;
    @LocalServerPort private int port;
    @Autowired private DocumentPresenceService presence;

    @Test
    @Timeout(60)
    void twoUsersCollaborateAndRevokedSubscriptionStopsReceivingUpdates() throws Exception {
        AuthResponse owner = register("Wire Owner", "wire-owner@example.com");
        AuthResponse editor = register("Wire Editor", "wire-editor@example.com");
        var login = new AuthRequest();
        login.setEmail(editor.getEmail()); login.setPassword("secret123");
        assertThat(rest.postForEntity("/auth/login", login, AuthResponse.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var request = new DocumentRequest();
        request.setTitle("Wire notes"); request.setContent("Initial draft");
        var created = exchange("/documents", HttpMethod.POST, request, owner, DocumentResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long id = created.getBody().getId();

        var client = new WebSocketStompClient(new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        var converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(mapper);
        client.setMessageConverter(converter);
        client.setDefaultHeartbeat(new long[] {0, 0});
        StompSession ownerSocket = null;
        StompSession editorSocket = null;
        try {
            ownerSocket = connect(client, owner);
            editorSocket = connect(client, editor);
            var ownerEdits = subscribe(ownerSocket, "/topic/documents/" + id);
            var ownerBoard = subscribe(ownerSocket, "/topic/documents/" + id + "/whiteboard");
            var ownerPresence = subscribe(ownerSocket, "/topic/documents/" + id + "/presence");
            ownerSocket.send("/app/documents/" + id + "/presence/join", Map.of());
            ownerPresence.await(message -> message.path("activeUsers").size() == 1, "owner join");
            share(id, owner, editor, "EDITOR");

            var editorEdits = subscribe(editorSocket, "/topic/documents/" + id);
            var editorBoard = subscribe(editorSocket, "/topic/documents/" + id + "/whiteboard");
            var accessEvents = subscribe(editorSocket, "/user/queue/document-access");
            editorSocket.send("/app/documents/" + id + "/presence/join", Map.of());
            ownerPresence.await(message -> message.path("activeUsers").size() == 2, "editor join");

            String boardPath = "/documents/" + id + "/whiteboard";
            String ownerStroke = boardPath + "/strokes/" + UUID.randomUUID();
            UUID editorShapeId = UUID.randomUUID();
            String editorStroke = boardPath + "/strokes/" + editorShapeId;
            var drawing = Map.of("color", "#2456d6", "width", 4,
                    "points", List.of(Map.of("x", 100, "y", 200), Map.of("x", 140, "y", 240)));
            var shape = Map.of("tool", "RECTANGLE", "color", "#2456d6", "width", 4, "points", drawing.get("points"));
            var invalidPoint = Map.of("color", "#2456d6", "width", 4,
                    "points", List.of(Map.of("x", 1001, "y", 200)));
            var nullPoint = Map.of("color", "#2456d6", "width", 4, "points", Collections.singletonList(null));
            assertThat(exchange(ownerStroke, HttpMethod.PUT, invalidPoint, owner, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exchange(ownerStroke, HttpMethod.PUT, nullPoint, owner, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exchange(ownerStroke, HttpMethod.PUT, drawing, owner, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            editorBoard.await(message -> message.path("documentId").asLong() == id, "owner stroke reaches editor");
            assertThat(exchange(editorStroke, HttpMethod.PUT, shape, editor, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            ownerBoard.await(message -> message.path("documentId").asLong() == id, "owner stroke echo");
            ownerBoard.await(message -> message.path("documentId").asLong() == id, "editor stroke reaches owner");
            assertThat(exchange(editorStroke, HttpMethod.PUT, drawing, editor, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            var board = exchange(boardPath, HttpMethod.GET, null, editor, JsonNode.class);
            assertThat(board.getBody()).hasSize(2);
            assertThat(board.getBody().get(0).path("drawing").path("tool").asText()).isEqualTo("PEN");
            assertThat(board.getBody().get(1).path("drawing").path("tool").asText()).isEqualTo("RECTANGLE");
            assertThat(board.getBody().get(1).path("drawing").path("points")).hasSize(2);
            assertThat(exchange(ownerStroke, HttpMethod.DELETE, null, editor, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            var eraseShape = Map.of("strokeIds", List.of(editorShapeId));
            assertThat(exchange(boardPath + "/erase", HttpMethod.POST, eraseShape, owner, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            ownerBoard.await(message -> message.path("documentId").asLong() == id, "owner erase echo");
            editorBoard.await(message -> message.path("documentId").asLong() == id, "editor shape echo");
            editorBoard.await(message -> message.path("documentId").asLong() == id, "erase reaches shape author");
            assertThat(exchange(editorStroke, HttpMethod.PUT, drawing, editor, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(exchange(boardPath, HttpMethod.GET, null, owner, JsonNode.class).getBody()).hasSize(1);

            sendEdit(ownerSocket, id, "Owner edit");
            ownerEdits.await(content("Owner edit"), "owner edit echo");
            editorEdits.await(content("Owner edit"), "owner edit reaches editor");
            sendEdit(editorSocket, id, "Editor edit");
            ownerEdits.await(content("Editor edit"), "editor edit reaches owner");
            editorEdits.await(content("Editor edit"), "editor edit echo");
            editorSocket.send("/app/documents/" + id + "/presence/typing", Map.of());
            ownerPresence.await(message -> {
                for (JsonNode person : message.path("activeUsers")) {
                    if (person.path("email").asText().equals(editor.getEmail()) && person.path("status").asText().equals("TYPING")) return true;
                }
                return false;
            }, "editor typing");
            editorSocket.send("/app/documents/" + id + "/presence/leave", Map.of());
            ownerPresence.await(message -> message.path("activeUsers").size() == 1, "explicit editor leave");

            share(id, owner, editor, "VIEWER");
            accessEvents.await(message -> message.path("role").asText().equals("VIEWER"), "private downgrade notification");
            assertThat(exchange(boardPath, HttpMethod.GET, null, editor, JsonNode.class).getBody()).hasSize(1);
            assertThat(exchange(editorStroke, HttpMethod.PUT, drawing, editor, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange(boardPath + "/erase", HttpMethod.POST, eraseShape, editor, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            share(id, owner, editor, "EDITOR");
            accessEvents.await(message -> message.path("role").asText().equals("EDITOR"), "private promotion notification");
            editorSocket.send("/app/documents/" + id + "/presence/join", Map.of());
            ownerPresence.await(message -> message.path("activeUsers").size() == 2, "editor rejoin");

            var removed = exchange("/documents/" + id + "/collaborators/" + editor.getUserId(), HttpMethod.DELETE, null, owner, DocumentResponse.class);
            assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
            accessEvents.await(message -> message.path("role").isNull(), "private removal notification");
            assertThat(presence.snapshot(id).getActiveUsers()).as("server presence after revocation").hasSize(1);
            ownerPresence.await(message -> message.path("activeUsers").size() == 1, "revocation clears presence");
            editorEdits.messages.clear();
            editorBoard.messages.clear();
            assertThat(exchange(ownerStroke, HttpMethod.DELETE, null, owner, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            ownerBoard.await(message -> message.path("documentId").asLong() == id, "owner undo echo");
            sendEdit(ownerSocket, id, "Private after revocation");
            ownerEdits.await(content("Private after revocation"), "owner continues editing");
            assertThat(editorEdits.messages.poll(750, TimeUnit.MILLISECONDS)).as("revoked subscription receives no new content").isNull();
            assertThat(editorBoard.messages).as("revoked subscription receives no board updates").isEmpty();
            assertThat(exchange(boardPath, HttpMethod.GET, null, editor, Void.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exchange("/documents/" + id, HttpMethod.GET, null, editor, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            var history = exchange("/documents/" + id + "/history", HttpMethod.GET, null, owner, String.class);
            assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(mapper.readTree(history.getBody()).get(0).path("contentSnapshot").asText()).isEqualTo("Private after revocation");
            assertThat(exchange("/documents/" + id, HttpMethod.DELETE, null, owner, String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(exchange("/documents/" + id, HttpMethod.GET, null, owner, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            if (ownerSocket != null && ownerSocket.isConnected()) ownerSocket.disconnect();
            if (editorSocket != null && editorSocket.isConnected()) editorSocket.disconnect();
            client.stop();
        }
    }

    private AuthResponse register(String name, String email) {
        var request = new RegisterRequest();
        request.setName(name); request.setEmail(email); request.setPassword("secret123");
        var response = rest.postForEntity("/auth/register", request, AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void share(Long id, AuthResponse owner, AuthResponse editor, String role) {
        var response = exchange("/documents/" + id + "/share", HttpMethod.POST,
                Map.of("email", editor.getEmail(), "role", role), owner, DocumentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, Object payload, AuthResponse actor, Class<T> type) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(actor.getToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), type);
    }

    private StompSession connect(WebSocketStompClient client, AuthResponse user) throws Exception {
        var connectHeaders = new StompHeaders();
        connectHeaders.set("Authorization", "Bearer " + user.getToken());
        var handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.setOrigin("http://localhost:3000");
        return client.connectAsync("ws://localhost:" + port + "/ws", handshakeHeaders, connectHeaders,
                new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
    }

    private Mailbox subscribe(StompSession session, String destination) {
        var mailbox = new Mailbox();
        session.subscribe(destination, mailbox);
        return mailbox;
    }

    private void sendEdit(StompSession session, Long id, String content) {
        session.send("/app/editDocument", Map.of("id", id, "content", content));
    }

    private Predicate<JsonNode> content(String value) {
        return message -> message.path("content").asText().equals(value);
    }

    private static class Mailbox implements StompFrameHandler {
        private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        @Override public Type getPayloadType(StompHeaders headers) { return JsonNode.class; }
        @Override public void handleFrame(StompHeaders headers, Object payload) { messages.add((JsonNode) payload); }

        void await(Predicate<JsonNode> expected, String description) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            var received = new ArrayList<JsonNode>();
            while (System.nanoTime() < deadline) {
                JsonNode message = messages.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (message != null && expected.test(message)) return;
                if (message != null && received.size() < 10) received.add(message);
            }
            fail("Timed out waiting for " + description + "; received " + received);
        }
    }
}
