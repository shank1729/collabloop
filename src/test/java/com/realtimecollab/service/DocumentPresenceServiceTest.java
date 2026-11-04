package com.realtimecollab.service;

import com.realtimecollab.dto.DocumentPresenceResponse;
import com.realtimecollab.dto.auth.RegisterRequest;
import com.realtimecollab.entity.Document;
import com.realtimecollab.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import com.realtimecollab.dto.ShareDocumentRequest;
import com.realtimecollab.entity.DocumentRole;
import com.realtimecollab.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentPresenceServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentPresenceService presenceService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void joinAddsUserToDocumentPresence() {
        register("Owner", "owner@example.com");
        Document document = documentService.create(document("Roadmap", "Draft"), "owner@example.com");

        DocumentPresenceResponse response =
                presenceService.join(document.getId(), "session-1", "owner@example.com");

        assertThat(response.getDocumentId()).isEqualTo(document.getId());
        assertThat(response.getActiveUsers())
                .hasSize(1)
                .first()
                .satisfies(user -> {
                    assertThat(user.getEmail()).isEqualTo("owner@example.com");
                    assertThat(user.getStatus()).isEqualTo("VIEWING");
                });
    }

    @Test
    void typingUpdatesUserStatus() {
        register("Owner", "owner@example.com");
        Document document = documentService.create(document("Roadmap", "Draft"), "owner@example.com");
        presenceService.join(document.getId(), "session-1", "owner@example.com");

        DocumentPresenceResponse response =
                presenceService.markTyping(document.getId(), "session-1", "owner@example.com");

        assertThat(response.getActiveUsers())
                .first()
                .extracting("status")
                .isEqualTo("TYPING");
    }

    @Test
    void leaveRemovesSessionFromPresence() {
        register("Owner", "owner@example.com");
        Document document = documentService.create(document("Roadmap", "Draft"), "owner@example.com");
        presenceService.join(document.getId(), "session-1", "owner@example.com");

        DocumentPresenceResponse response = presenceService.leave("session-1");

        assertThat(response.getDocumentId()).isEqualTo(document.getId());
        assertThat(response.getActiveUsers()).isEmpty();
    }

    @Test
    void inaccessibleDocumentCannotBeJoined() {
        register("Owner", "owner@example.com");
        register("Stranger", "stranger@example.com");
        Document document = documentService.create(document("Roadmap", "Draft"), "owner@example.com");

        assertThatThrownBy(() -> presenceService.join(document.getId(), "session-1", "stranger@example.com"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Document not found with id: " + document.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sharedEditorCanTypeWithoutAnHttpOrCallerTransaction() {
        String owner = "presence-owner@example.com";
        String editor = "presence-editor@example.com";
        register("Owner", owner);
        register("Editor", editor);
        Document document = documentService.create(document("Presence", "Draft"), owner);
        try {
            var request = new ShareDocumentRequest();
            request.setEmail(editor);
            request.setRole(DocumentRole.EDITOR);
            documentService.shareDocument(document.getId(), request, owner);
            assertThat(presenceService.markTyping(document.getId(), "editor-session", editor).getActiveUsers())
                    .singleElement().extracting("status").isEqualTo("TYPING");
        } finally {
            presenceService.leave("editor-session");
            documentService.delete(document.getId(), owner);
            userRepository.delete(userRepository.findByEmail(owner).orElseThrow());
            userRepository.delete(userRepository.findByEmail(editor).orElseThrow());
        }
    }

    private void register(String name, String email) {
        RegisterRequest request = new RegisterRequest();
        request.setName(name);
        request.setEmail(email);
        request.setPassword("secret123");
        authService.register(request);
    }

    private Document document(String title, String content) {
        Document document = new Document();
        document.setTitle(title);
        document.setContent(content);
        return document;
    }
}
