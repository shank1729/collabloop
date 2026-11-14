package com.realtimecollab.collab_backend;

import com.realtimecollab.dto.DocumentContentRequest;
import com.realtimecollab.dto.DocumentContentResponse;
import com.realtimecollab.dto.DocumentResponse;
import com.realtimecollab.dto.auth.AuthResponse;
import com.realtimecollab.dto.auth.RegisterRequest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${collab.test.database-url:jdbc:h2:mem:content_sync_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
class ContentSynchronizationTest {
    @Autowired TestRestTemplate rest;

    @Test
    @Timeout(60)
    void staleCollaboratorCannotOverwriteTheOwnerAndRetriesAreIdempotent() throws Exception {
        var owner = register("Owner");
        var editor = register("Editor");
        var viewer = register("Viewer");
        var doc = exchange("/documents", HttpMethod.POST, Map.of("title", "Sync test", "content", "Initial"),
                owner, DocumentResponse.class).getBody();
        Long id = doc.getId();
        share(id, owner, editor, "EDITOR");
        share(id, owner, viewer, "VIEWER");
        var baseline = exchange("/documents/" + id, HttpMethod.GET, null, owner, DocumentResponse.class).getBody();
        String path = "/documents/" + id + "/content";
        var ownerEdit = new DocumentContentRequest("Owner's text", baseline.getVersion(), UUID.randomUUID());
        var editorEdit = new DocumentContentRequest("Editor's text", baseline.getVersion(), UUID.randomUUID());
        var start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> race(start, path, ownerEdit, owner));
        var second = CompletableFuture.supplyAsync(() -> race(start, path, editorEdit, editor));
        start.countDown();
        var firstResult = first.get(15, TimeUnit.SECONDS);
        var secondResult = second.get(15, TimeUnit.SECONDS);
        assertThat(java.util.List.of(firstResult.getStatusCode(), secondResult.getStatusCode()))
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        var winner = firstResult.getStatusCode() == HttpStatus.OK ? owner : editor;
        var winningEdit = firstResult.getStatusCode() == HttpStatus.OK ? ownerEdit : editorEdit;
        var accepted = exchange(path, HttpMethod.PUT, winningEdit, winner, DocumentContentResponse.class).getBody();
        assertThat(accepted.content()).isEqualTo(winningEdit.content());
        assertThat(accepted.version()).isGreaterThan(baseline.getVersion());

        var next = new DocumentContentRequest("Both users' merged text", accepted.version(), UUID.randomUUID());
        var updated = exchange(path, HttpMethod.PUT, next, owner, DocumentContentResponse.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        var retry = exchange(path, HttpMethod.PUT, winningEdit, winner, DocumentContentResponse.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody()).isEqualTo(accepted);
        var stored = exchange("/documents/" + id, HttpMethod.GET, null, editor, DocumentResponse.class).getBody();
        assertThat(stored.getContent()).isEqualTo(next.content());
        assertThat(stored.getVersion()).isEqualTo(updated.getBody().version());

        assertThat(exchange(path, HttpMethod.PUT,
                new DocumentContentRequest("Not allowed", stored.getVersion(), UUID.randomUUID()), viewer,
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(path, HttpMethod.PUT, Map.of("content", "Missing version"), owner,
                String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var cleared = exchange(path, HttpMethod.PUT,
                new DocumentContentRequest("", stored.getVersion(), UUID.randomUUID()), editor,
                DocumentContentResponse.class);
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().content()).isEmpty();
    }

    private ResponseEntity<String> race(CountDownLatch start, String path, DocumentContentRequest edit, AuthResponse user) {
        try { start.await(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); }
        return exchange(path, HttpMethod.PUT, edit, user, String.class);
    }

    private AuthResponse register(String name) {
        var request = new RegisterRequest();
        request.setName(name);
        request.setEmail("sync-" + UUID.randomUUID() + "@example.com");
        request.setPassword("secret123");
        var response = rest.postForEntity("/auth/register", request, AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void share(Long id, AuthResponse owner, AuthResponse user, String role) {
        assertThat(exchange("/documents/" + id + "/share", HttpMethod.POST,
                Map.of("email", user.getEmail(), "role", role), owner, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, Object payload, AuthResponse user, Class<T> type) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(user.getToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), type);
    }
}
