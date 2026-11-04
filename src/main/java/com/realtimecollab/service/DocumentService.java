package com.realtimecollab.service;

import com.realtimecollab.dto.DocumentPatchRequest;
import com.realtimecollab.dto.DocumentAccessChangedEvent;
import com.realtimecollab.dto.ShareDocumentRequest;
import com.realtimecollab.entity.DocumentAccess;
import com.realtimecollab.entity.Document;
import com.realtimecollab.entity.DocumentRole;
import com.realtimecollab.entity.DocumentVersion;
import com.realtimecollab.entity.User;
import com.realtimecollab.exception.BadRequestException;
import com.realtimecollab.exception.ResourceNotFoundException;
import com.realtimecollab.repository.DocumentRepository;
import com.realtimecollab.repository.DocumentVersionRepository;
import com.realtimecollab.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class DocumentService {
    private final DocumentRepository repo;
    private final DocumentVersionRepository documentVersionRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;
    @Autowired
    public DocumentService(
            DocumentRepository repo,
            DocumentVersionRepository documentVersionRepository,
            UserRepository userRepository,
            ApplicationEventPublisher events) {
        this.repo = repo;
        this.documentVersionRepository = documentVersionRepository;
        this.userRepository = userRepository;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Document> getAll(String email) {
        User user = getUserByEmail(email);
        return repo.findAllAccessibleByUserId(user.getId());
    }

    @Transactional(readOnly = true)
    public Document getById(Long id, String email) {
        User user = getUserByEmail(email);
        return repo.findAccessibleByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + id));
    }

    @Transactional
    public Document create(Document doc, String email) {
        User user = getUserByEmail(email);
        doc.setOwner(user);
        doc.setLastEditedBy(user);
        Document savedDocument = repo.saveAndFlush(doc);
        createVersionSnapshot(savedDocument, user);
        return savedDocument;
    }

    @Transactional
    public Document update(Long id, Document newDoc, String email) {
        Document existing = getById(id, email);
        User editor = getUserByEmail(email);
        requireEditorAccess(existing, editor);
        existing.setTitle(newDoc.getTitle());
        existing.setContent(newDoc.getContent());
        existing.setLastEditedBy(editor);
        Document savedDocument = repo.saveAndFlush(existing);
        createVersionSnapshot(savedDocument, editor);
        return savedDocument;
    }

    @Transactional
    public Document updateDocumentContent(Long id, String newContent, String editorEmail) {
        Document doc = getById(id, editorEmail);
        User editor = getUserByEmail(editorEmail);
        requireEditorAccess(doc, editor);
        doc.setContent(newContent);
        doc.setLastEditedBy(editor);
        Document savedDocument = repo.saveAndFlush(doc);
        createVersionSnapshot(savedDocument, editor);
        return savedDocument;
    }
    
    @Transactional
    public Document patch(Long id, DocumentPatchRequest partialDoc, String email) {
        Document existing = getById(id, email);
        User editor = getUserByEmail(email);
        requireEditorAccess(existing, editor);

        if (partialDoc.getTitle() != null) {
            existing.setTitle(partialDoc.getTitle().trim());
        }
        if (partialDoc.getContent() != null) {
            existing.setContent(partialDoc.getContent());
        }

        existing.setLastEditedBy(editor);
        Document savedDocument = repo.saveAndFlush(existing);
        createVersionSnapshot(savedDocument, editor);
        return savedDocument;
    }

    @Transactional
    public void delete(Long id, String email) {
        Document existing = getOwnedDocument(id, email, "delete");
        // Entity deletes keep Hibernate's managed state consistent with the database.
        documentVersionRepository.deleteAll(
                documentVersionRepository.findByDocumentIdOrderByEditedAtDesc(existing.getId()));
        repo.delete(existing);
    }

    private User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
    }

    @Transactional
    public Document createStarterDocument(String email) {
        Document document = new Document();
        document.setTitle("Getting Started");
        document.setContent("Start collaborating on your first document.");
        return create(document, email);
    }

    @Transactional
    public Document getFirstOrCreateStarter(String email) {
        List<Document> documents = getAll(email);
        if (!documents.isEmpty()) {
            return documents.get(0);
        }

        return createStarterDocument(email);
    }

    @Transactional
    public Document shareDocument(Long id, ShareDocumentRequest request, String ownerEmail) {
        Document document = getOwnedDocument(id, ownerEmail, "share");
        String normalizedEmail = request.getEmail().trim().toLowerCase();
        User collaborator = getUserByEmail(normalizedEmail);
        DocumentRole role = request.getRole() != null ? request.getRole() : DocumentRole.EDITOR;

        if (document.getOwner() != null && normalizedEmail.equals(document.getOwner().getEmail())) {
            throw new BadRequestException("The owner already has access to this document.");
        }

        Optional<DocumentAccess> existingAccess = document.getCollaboratorAccesses().stream()
                .filter(access -> access.getUser().getEmail().equalsIgnoreCase(normalizedEmail))
                .findFirst();

        if (existingAccess.isPresent()) {
            existingAccess.get().setRole(role);
            events.publishEvent(new DocumentAccessChangedEvent(id, normalizedEmail, role.name()));
            return repo.save(document);
        }

        DocumentAccess access = new DocumentAccess();
        access.setDocument(document);
        access.setUser(collaborator);
        access.setRole(role);
        document.getCollaboratorAccesses().add(access);
        events.publishEvent(new DocumentAccessChangedEvent(id, normalizedEmail, role.name()));
        return repo.save(document);
    }

    @Transactional
    public Document removeCollaborator(Long id, Long userId, String ownerEmail) {
        Document document = getOwnedDocument(id, ownerEmail, "share");
        if (document.getOwner().getId().equals(userId)) {
            throw new BadRequestException("The document owner cannot be removed.");
        }
        DocumentAccess access = document.getCollaboratorAccesses().stream()
                .filter(item -> item.getUser().getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Collaborator not found."));
        String email = access.getUser().getEmail();
        document.getCollaboratorAccesses().remove(access);
        events.publishEvent(new DocumentAccessChangedEvent(id, email, null));
        return repo.save(document);
    }

    @Transactional(readOnly = true)
    public List<DocumentVersion> getVersionHistory(Long id, String email) {
        Document document = getById(id, email);
        return documentVersionRepository.findByDocumentIdOrderByEditedAtDesc(document.getId());
    }

    private Document getOwnedDocument(Long id, String ownerEmail, String action) {
        Document document = getById(id, ownerEmail);
        if (document.getOwner() == null || !document.getOwner().getEmail().equalsIgnoreCase(ownerEmail)) {
            throw new AccessDeniedException("Only the document owner can " + action + " this document.");
        }
        return document;
    }

    private void requireEditorAccess(Document document, User user) {
        if (isOwner(document, user) || hasRole(document, user, DocumentRole.EDITOR)) {
            return;
        }

        throw new AccessDeniedException("Only document owners and editors can edit this document.");
    }

    @Transactional(readOnly = true)
    public void requireEditorAccess(Long documentId, String email) {
        requireEditorAccess(getById(documentId, email), getUserByEmail(email));
    }

    private boolean isOwner(Document document, User user) {
        return document.getOwner() != null && document.getOwner().getId().equals(user.getId());
    }

    private boolean hasRole(Document document, User user, DocumentRole role) {
        return document.getCollaboratorAccesses().stream()
                .anyMatch(access ->
                        access.getUser().getId().equals(user.getId()) && access.getRole() == role);
    }

    private void createVersionSnapshot(Document document, User editor) {
        DocumentVersion version = new DocumentVersion();
        version.setDocument(document);
        version.setDocumentVersionNumber(document.getVersion() != null ? document.getVersion() : 0L);
        version.setContentSnapshot(document.getContent());
        version.setEditedBy(editor);
        version.setEditedAt(document.getUpdatedAt() != null ? document.getUpdatedAt() : document.getCreatedAt());
        documentVersionRepository.save(version);
    }
}
