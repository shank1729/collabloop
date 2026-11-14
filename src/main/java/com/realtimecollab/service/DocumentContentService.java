package com.realtimecollab.service;

import com.realtimecollab.dto.DocumentContentRequest;
import com.realtimecollab.dto.DocumentContentResponse;
import com.realtimecollab.entity.Document;
import com.realtimecollab.entity.DocumentVersion;
import com.realtimecollab.exception.BadRequestException;
import com.realtimecollab.repository.DocumentRepository;
import com.realtimecollab.repository.DocumentVersionRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentContentService {
    private final DocumentService documents;
    private final DocumentRepository repository;
    private final DocumentVersionRepository versions;

    public DocumentContentService(DocumentService documents, DocumentRepository repository,
                                  DocumentVersionRepository versions) {
        this.documents = documents;
        this.repository = repository;
        this.versions = versions;
    }

    @Transactional
    public DocumentContentResponse save(Long id, DocumentContentRequest request, String email) {
        documents.requireEditorAccess(id, email);
        Document document = documents.getById(id, email);
        var accepted = versions.findByDocumentIdAndClientEditId(id, request.editId());
        if (accepted.isPresent()) {
            DocumentVersion snapshot = accepted.get();
            if (!snapshot.getEditedBy().getEmail().equals(email)
                    || !snapshot.getContentSnapshot().equals(request.content())) {
                throw new BadRequestException("An edit identifier cannot be reused for another edit.");
            }
            return response(id, snapshot);
        }
        if (!document.getVersion().equals(request.baseVersion())) {
            throw new ObjectOptimisticLockingFailureException(Document.class, id);
        }
        // JPA's version check makes concurrent commits atomic: a stale writer gets HTTP 409.
        document.setContent(request.content());
        var editor = document.getOwner();
        if (!editor.getEmail().equals(email)) {
            editor = document.getCollaboratorAccesses().stream()
                    .map(access -> access.getUser()).filter(user -> user.getEmail().equals(email))
                    .findFirst().orElseThrow();
        }
        document.setLastEditedBy(editor);
        repository.saveAndFlush(document);
        DocumentVersion snapshot = new DocumentVersion();
        snapshot.setDocument(document);
        snapshot.setDocumentVersionNumber(document.getVersion());
        snapshot.setContentSnapshot(request.content());
        snapshot.setEditedBy(editor);
        snapshot.setEditedAt(document.getUpdatedAt());
        snapshot.setClientEditId(request.editId());
        versions.saveAndFlush(snapshot);
        return response(id, snapshot);
    }

    private DocumentContentResponse response(Long id, DocumentVersion snapshot) {
        return new DocumentContentResponse(id, snapshot.getContentSnapshot(),
                snapshot.getDocumentVersionNumber(), snapshot.getEditedAt());
    }
}
