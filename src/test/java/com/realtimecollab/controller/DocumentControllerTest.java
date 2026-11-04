package com.realtimecollab.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimecollab.dto.DocumentRequest;
import com.realtimecollab.dto.ShareDocumentRequest;
import com.realtimecollab.dto.auth.AuthResponse;
import com.realtimecollab.dto.auth.RegisterRequest;
import com.realtimecollab.entity.DocumentRole;
import com.realtimecollab.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import jakarta.persistence.EntityManager;
import com.realtimecollab.repository.DocumentVersionRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DocumentVersionRepository versionRepository;

    @Test
    void documentsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createDocumentReturnsCreatedDocumentForAuthenticatedUser() throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");

        mockMvc.perform(post("/documents")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                documentRequest("Interview Plan", "Initial draft"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Interview Plan"))
                .andExpect(jsonPath("$.content").value("Initial draft"))
                .andExpect(jsonPath("$.ownerEmail").value("owner@example.com"))
                .andExpect(jsonPath("$.version").isNumber());
    }

    @Test
    void createDocumentReturnsValidationErrorsForBlankTitleAndContent() throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");

        mockMvc.perform(post("/documents")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(documentRequest("", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.validationErrors.title").value("Title is required"))
                .andExpect(jsonPath("$.validationErrors.content").value("Content is required"));
    }

    @Test
    void ownerCanShareDocumentWithViewerRole() throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        register("Viewer", "viewer@example.com");
        int documentId = createDocument(owner, "Project Notes", "Private draft");

        ShareDocumentRequest request = new ShareDocumentRequest();
        request.setEmail("viewer@example.com");
        request.setRole(DocumentRole.VIEWER);

        mockMvc.perform(post("/documents/{id}/share", documentId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collaborators", hasSize(1)))
                .andExpect(jsonPath("$.collaborators[0].email").value("viewer@example.com"))
                .andExpect(jsonPath("$.collaborators[0].role").value("VIEWER"));
    }

    @Test
    void ownerCanPromoteViewerToEditorWithJsonRole() throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        register("Teammate", "teammate@example.com");
        int documentId = createDocument(owner, "Project Notes", "Private draft");
        shareDocument(owner, documentId, "teammate@example.com", DocumentRole.VIEWER);

        mockMvc.perform(post("/documents/{id}/share", documentId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"teammate@example.com\",\"role\":\"EDITOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collaborators", hasSize(1)))
                .andExpect(jsonPath("$.collaborators[0].email").value("teammate@example.com"))
                .andExpect(jsonPath("$.collaborators[0].role").value("EDITOR"));
    }

    @Test
    void viewerCanReadButCannotUpdateDocument() throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        AuthResponse viewer = register("Viewer", "viewer@example.com");
        int documentId = createDocument(owner, "Project Notes", "Private draft");
        shareDocument(owner, documentId, "viewer@example.com", DocumentRole.VIEWER);

        mockMvc.perform(get("/documents/{id}", documentId)
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Private draft"));

        mockMvc.perform(put("/documents/{id}", documentId)
                        .header("Authorization", bearer(viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                documentRequest("Project Notes", "Changed by viewer"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Only document owners and editors can edit this document."));
    }

    @Test
    void editorCanPatchSharedDocumentAndHistoryIsReturned() throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        AuthResponse editor = register("Editor", "editor@example.com");
        int documentId = createDocument(owner, "Project Notes", "Initial draft");
        shareDocument(owner, documentId, "editor@example.com", DocumentRole.EDITOR);

        mockMvc.perform(patch("/documents/{id}", documentId)
                        .header("Authorization", bearer(editor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Updated by editor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Updated by editor"))
                .andExpect(jsonPath("$.lastEditedByEmail").value("editor@example.com"));

        mockMvc.perform(get("/documents/{id}/history", documentId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void onlyOwnerCanDeleteDocument() throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        AuthResponse editor = register("Editor", "editor@example.com");
        int documentId = createDocument(owner, "Project Notes", "Initial draft");
        shareDocument(owner, documentId, "editor@example.com", DocumentRole.EDITOR);

        mockMvc.perform(delete("/documents/{id}", documentId)
                        .header("Authorization", bearer(editor)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Only the document owner can delete this document."));

        mockMvc.perform(delete("/documents/{id}", documentId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ownerAndEditorCanRenameWithoutChangingContent(boolean asOwner) throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        AuthResponse editor = register("Editor", "editor@example.com");
        int documentId = createDocument(owner, "Old title", "Keep this content");
        shareDocument(owner, documentId, "editor@example.com", DocumentRole.EDITOR);
        AuthResponse actor = asOwner ? owner : editor;

        mockMvc.perform(patch("/documents/{id}", documentId)
                        .header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  New title  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New title"))
                .andExpect(jsonPath("$.content").value("Keep this content"))
                .andExpect(jsonPath("$.lastEditedByEmail").value(actor.getEmail()));

        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(get("/documents/{id}", documentId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New title"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankRenameIsRejectedAndOriginalTitleIsPreserved(String title) throws Exception {
        assertInvalidRename("{\"title\":\"" + title + "\"}");
    }

    @Test
    void oversizedRenameIsRejected() throws Exception {
        assertInvalidRename("{\"title\":\"" + "a".repeat(256) + "\"}");
    }

    @Test
    void emptyPatchIsRejected() throws Exception {
        assertInvalidRename("{}");
    }

    private void assertInvalidRename(String payload) throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        int documentId = createDocument(owner, "Original", "Keep this content");
        mockMvc.perform(patch("/documents/{id}", documentId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/documents/{id}", documentId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Original"));
    }

    @Test
    void viewerCannotRenameDocument() throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        AuthResponse viewer = register("Viewer", "viewer@example.com");
        int documentId = createDocument(owner, "Original", "Draft");
        shareDocument(owner, documentId, "viewer@example.com", DocumentRole.VIEWER);
        mockMvc.perform(patch("/documents/{id}", documentId)
                        .header("Authorization", bearer(viewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Forbidden\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/documents/{id}", documentId).header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.title").value("Original"));
    }

    @ParameterizedTest
    @EnumSource(DocumentRole.class)
    void collaboratorCannotDeleteAndOwnerDeletionRemovesHistory(DocumentRole role) throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        AuthResponse collaborator = register("Collaborator", "collab@example.com");
        int documentId = createDocument(owner, "Notes", "Draft");
        shareDocument(owner, documentId, "collab@example.com", role);
        mockMvc.perform(delete("/documents/{id}", documentId).header("Authorization", bearer(collaborator)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/documents/{id}", documentId).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();
        assertThat(versionRepository.findByDocumentIdOrderByEditedAtDesc((long) documentId)).isEmpty();
        for (AuthResponse actor : new AuthResponse[] {owner, collaborator}) {
            mockMvc.perform(get("/documents/{id}", documentId).header("Authorization", bearer(actor)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void strangerCannotRenameOrDeleteHiddenDocument() throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        AuthResponse stranger = register("Stranger", "stranger@example.com");
        int documentId = createDocument(owner, "Private", "Draft");
        mockMvc.perform(patch("/documents/{id}", documentId)
                        .header("Authorization", bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Changed\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/documents/{id}", documentId).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @EnumSource(DocumentRole.class)
    void removedCollaboratorLosesReadEditAndHistoryAccess(DocumentRole role) throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        AuthResponse collaborator = register("Collaborator", "collab@example.com");
        int documentId = createDocument(owner, "Notes", "Keep this content");
        shareDocument(owner, documentId, "collab@example.com", role);
        mockMvc.perform(delete("/documents/{id}/collaborators/{userId}", documentId, collaborator.getUserId())
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collaborators", hasSize(0)))
                .andExpect(jsonPath("$.content").value("Keep this content"));
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(get("/documents/{id}", documentId).header("Authorization", bearer(collaborator)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/documents/{id}/history", documentId).header("Authorization", bearer(collaborator)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/documents/{id}", documentId).header("Authorization", bearer(collaborator))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Forbidden\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/documents").header("Authorization", bearer(collaborator)))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @ParameterizedTest
    @EnumSource(DocumentRole.class)
    void onlyOwnerCanManageCollaborators(DocumentRole role) throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        AuthResponse collaborator = register("Collaborator", "collab@example.com");
        int documentId = createDocument(owner, "Notes", "Draft");
        shareDocument(owner, documentId, "collab@example.com", role);
        mockMvc.perform(delete("/documents/{id}/collaborators/{userId}", documentId, collaborator.getUserId())
                        .header("Authorization", bearer(collaborator)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/documents/{id}/share", documentId).header("Authorization", bearer(collaborator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"collab@example.com\",\"role\":\"EDITOR\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void editorDowngradeImmediatelyPreventsEditingAndDoesNotDuplicateAccess() throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        AuthResponse collaborator = register("Collaborator", "collab@example.com");
        int documentId = createDocument(owner, "Notes", "Draft");
        shareDocument(owner, documentId, "collab@example.com", DocumentRole.EDITOR);
        shareDocument(owner, documentId, "collab@example.com", DocumentRole.VIEWER);
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(get("/documents/{id}", documentId).header("Authorization", bearer(owner)))
                .andExpect(jsonPath("$.collaborators", hasSize(1)))
                .andExpect(jsonPath("$.collaborators[0].role").value("VIEWER"));
        mockMvc.perform(patch("/documents/{id}", documentId).header("Authorization", bearer(collaborator))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Forbidden\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerCannotBeRemovedAndMissingCollaboratorIsReported() throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        int documentId = createDocument(owner, "Notes", "Draft");
        mockMvc.perform(delete("/documents/{id}/collaborators/{userId}", documentId, owner.getUserId())
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/documents/{id}/collaborators/{userId}", documentId, Long.MAX_VALUE)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @EnumSource(DocumentRole.class)
    void sharedReadersCanPreviewContentHistoryButStrangersCannot(DocumentRole role) throws Exception {
        AuthResponse owner = register("Owner", "owner@example.com");
        AuthResponse reader = register("Reader", "reader@example.com");
        AuthResponse stranger = register("Stranger", "stranger@example.com");
        int documentId = createDocument(owner, "Notes", "First saved content");
        shareDocument(owner, documentId, reader.getEmail(), role);
        mockMvc.perform(patch("/documents/{id}", documentId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Latest content\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/documents/{id}/history", documentId).header("Authorization", bearer(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].contentSnapshot").value("Latest content"))
                .andExpect(jsonPath("$[0].editedByName").value("Owner"))
                .andExpect(jsonPath("$[1].contentSnapshot").value("First saved content"));
        mockMvc.perform(get("/documents/{id}/history", documentId).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/documents/{id}/history", documentId))
                .andExpect(status().isUnauthorized());
    }

    private AuthResponse register(String name, String email) {
        RegisterRequest request = new RegisterRequest();
        request.setName(name);
        request.setEmail(email);
        request.setPassword("secret123");
        return authService.register(request);
    }

    private int createDocument(AuthResponse user, String title, String content) throws Exception {
        String response = mockMvc.perform(post("/documents")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(documentRequest(title, content))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("id").asInt();
    }

    private void shareDocument(
            AuthResponse owner,
            int documentId,
            String collaboratorEmail,
            DocumentRole role) throws Exception {
        ShareDocumentRequest request = new ShareDocumentRequest();
        request.setEmail(collaboratorEmail);
        request.setRole(role);

        mockMvc.perform(post("/documents/{id}/share", documentId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    private DocumentRequest documentRequest(String title, String content) {
        DocumentRequest request = new DocumentRequest();
        request.setTitle(title);
        request.setContent(content);
        return request;
    }

    private String bearer(AuthResponse response) {
        return "Bearer " + response.getToken();
    }
}
