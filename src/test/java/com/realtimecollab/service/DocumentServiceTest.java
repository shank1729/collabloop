package com.realtimecollab.service;

import com.realtimecollab.dto.ShareDocumentRequest;
import com.realtimecollab.dto.DocumentPatchRequest;
import com.realtimecollab.dto.auth.RegisterRequest;
import com.realtimecollab.entity.Document;
import com.realtimecollab.entity.DocumentRole;
import org.springframework.security.access.AccessDeniedException;
import com.realtimecollab.exception.ResourceNotFoundException;
import com.realtimecollab.repository.DocumentVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentVersionRepository documentVersionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void createStoresOwnerAndVersionSnapshot() {
        register("Owner", "owner@example.com");
        Document document = document("Interview Notes", "Initial content");

        Document savedDocument = documentService.create(document, "owner@example.com");

        assertThat(savedDocument.getId()).isNotNull();
        assertThat(savedDocument.getOwner().getEmail()).isEqualTo("owner@example.com");
        assertThat(savedDocument.getLastEditedBy().getEmail()).isEqualTo("owner@example.com");
        assertThat(documentVersionRepository.findByDocumentIdOrderByEditedAtDesc(savedDocument.getId()))
                .hasSize(1)
                .first()
                .extracting("contentSnapshot")
                .isEqualTo("Initial content");
    }

    @Test
    void ownerCanShareDocumentWithAnotherRegisteredUser() {
        register("Owner", "owner@example.com");
        register("Collaborator", "collab@example.com");
        Document savedDocument = documentService.create(
                document("System Design", "Draft"),
                "owner@example.com"
        );

        Document sharedDocument = documentService.shareDocument(
                savedDocument.getId(),
                shareRequest("collab@example.com"),
                "owner@example.com"
        );

        assertThat(sharedDocument.getCollaboratorAccesses())
                .extracting(access -> access.getUser().getEmail())
                .containsExactly("collab@example.com");
        assertThat(documentService.getById(savedDocument.getId(), "collab@example.com").getId())
                .isEqualTo(savedDocument.getId());
    }

    @Test
    void ownerCanUpdateExistingCollaboratorRole() {
        register("Owner", "owner@example.com");
        register("Collaborator", "collab@example.com");
        Document savedDocument = documentService.create(
                document("Role Update", "Draft"),
                "owner@example.com"
        );
        documentService.shareDocument(
                savedDocument.getId(),
                shareRequest("collab@example.com", DocumentRole.VIEWER),
                "owner@example.com"
        );

        Document updatedDocument = documentService.shareDocument(
                savedDocument.getId(),
                shareRequest("collab@example.com", DocumentRole.EDITOR),
                "owner@example.com"
        );

        assertThat(updatedDocument.getCollaboratorAccesses())
                .hasSize(1)
                .first()
                .extracting("role")
                .isEqualTo(DocumentRole.EDITOR);
    }

    @Test
    void nonCollaboratorCannotReadDocument() {
        register("Owner", "owner@example.com");
        register("Stranger", "stranger@example.com");
        Document savedDocument = documentService.create(
                document("Private Notes", "Sensitive draft"),
                "owner@example.com"
        );

        assertThatThrownBy(() -> documentService.getById(savedDocument.getId(), "stranger@example.com"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Document not found with id: " + savedDocument.getId());
    }

    @Test
    void collaboratorCannotShareDocumentOwnedBySomeoneElse() {
        register("Owner", "owner@example.com");
        register("Collaborator", "collab@example.com");
        register("Third User", "third@example.com");
        Document savedDocument = documentService.create(
                document("Shared Notes", "Draft"),
                "owner@example.com"
        );
        documentService.shareDocument(savedDocument.getId(), shareRequest("collab@example.com"), "owner@example.com");

        assertThatThrownBy(() ->
                documentService.shareDocument(savedDocument.getId(), shareRequest("third@example.com"), "collab@example.com"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only the document owner can share this document.");
    }

    @Test
    void viewerCanReadButCannotEditDocument() {
        register("Owner", "owner@example.com");
        register("Viewer", "viewer@example.com");
        Document savedDocument = documentService.create(
                document("Read Only Plan", "Original"),
                "owner@example.com"
        );
        documentService.shareDocument(savedDocument.getId(), shareRequest("viewer@example.com", DocumentRole.VIEWER), "owner@example.com");

        assertThat(documentService.getById(savedDocument.getId(), "viewer@example.com").getContent())
                .isEqualTo("Original");
        assertThatThrownBy(() ->
                documentService.update(savedDocument.getId(), document("Read Only Plan", "Changed"), "viewer@example.com"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only document owners and editors can edit this document.");
    }

    @Test
    void editorCanUpdateSharedDocument() {
        register("Owner", "owner@example.com");
        register("Editor", "editor@example.com");
        Document savedDocument = documentService.create(
                document("Editable Plan", "Original"),
                "owner@example.com"
        );
        documentService.shareDocument(savedDocument.getId(), shareRequest("editor@example.com", DocumentRole.EDITOR), "owner@example.com");

        Document updatedDocument = documentService.update(
                savedDocument.getId(),
                document("Editable Plan", "Updated by editor"),
                "editor@example.com"
        );

        assertThat(updatedDocument.getContent()).isEqualTo("Updated by editor");
        assertThat(updatedDocument.getLastEditedBy().getEmail()).isEqualTo("editor@example.com");
    }

    @Test
    void updateCreatesAdditionalVersionSnapshot() {
        register("Owner", "owner@example.com");
        Document savedDocument = documentService.create(
                document("Versioned Notes", "Version one"),
                "owner@example.com"
        );

        documentService.update(savedDocument.getId(), document("Versioned Notes", "Version two"), "owner@example.com");

        assertThat(documentVersionRepository.findByDocumentIdOrderByEditedAtDesc(savedDocument.getId()))
                .hasSize(2)
                .extracting("contentSnapshot")
                .contains("Version one", "Version two");
    }

    @ParameterizedTest
    @ValueSource(strings = {"put", "patch", "socket"})
    void snapshotsUsePersistedVersionAndTimestampAndPreserveOlderContent(String path) {
        register("Owner", "owner@example.com");
        Document original = documentService.create(document("Notes", "Version one"), "owner@example.com");
        Long id = original.getId();
        Long initialVersion = original.getVersion();
        if (path.equals("put")) {
            documentService.update(id, document("Notes", "Version two"), "owner@example.com");
        } else if (path.equals("patch")) {
            var request = new DocumentPatchRequest();
            request.setContent("Version two");
            documentService.patch(id, request, "owner@example.com");
        } else {
            documentService.updateDocumentContent(id, "Version two", "owner@example.com");
        }
        entityManager.flush();
        entityManager.clear();
        Document persisted = documentService.getById(id, "owner@example.com");
        var versions = documentVersionRepository.findByDocumentIdOrderByEditedAtDesc(id);
        assertThat(versions).hasSize(2);
        assertThat(persisted.getVersion()).isGreaterThan(initialVersion);
        assertThat(versions.get(0).getDocumentVersionNumber()).isEqualTo(persisted.getVersion());
        assertThat(versions.get(0).getEditedAt()).isEqualTo(persisted.getUpdatedAt());
        assertThat(versions.get(0).getContentSnapshot()).isEqualTo("Version two");
        assertThat(versions.get(1).getContentSnapshot()).isEqualTo("Version one");
        assertThat(versions.get(1).getDocumentVersionNumber()).isEqualTo(initialVersion);
    }

    @Test
    void snapshotOrderingUsesIdToBreakTimestampTies() {
        register("Owner", "owner@example.com");
        Document document = documentService.create(document("Notes", "First"), "owner@example.com");
        documentService.updateDocumentContent(document.getId(), "Second", "owner@example.com");
        var versions = documentVersionRepository.findByDocumentIdOrderByEditedAtDesc(document.getId());
        versions.forEach(version -> version.setEditedAt(LocalDateTime.of(2026, 10, 31, 10, 0)));
        documentVersionRepository.saveAllAndFlush(versions);
        entityManager.clear();
        assertThat(documentVersionRepository.findByDocumentIdOrderByEditedAtDesc(document.getId()))
                .extracting("contentSnapshot").containsExactly("Second", "First");
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

    private ShareDocumentRequest shareRequest(String email) {
        return shareRequest(email, DocumentRole.EDITOR);
    }

    private ShareDocumentRequest shareRequest(String email, DocumentRole role) {
        ShareDocumentRequest request = new ShareDocumentRequest();
        request.setEmail(email);
        request.setRole(role);
        return request;
    }
}
