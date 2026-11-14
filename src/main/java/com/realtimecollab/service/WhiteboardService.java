package com.realtimecollab.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimecollab.dto.WhiteboardStrokeRequest;
import com.realtimecollab.exception.BadRequestException;
import com.realtimecollab.exception.ResourceNotFoundException;
import com.realtimecollab.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WhiteboardService {
    private static final int MAX_STROKES = 500;

    private final DocumentService documents;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper mapper;

    public WhiteboardService(DocumentService documents, UserRepository users, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.documents = documents;
        this.users = users;
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<Stroke> getBoard(Long documentId, String email) {
        documents.getById(documentId, email);
        return jdbc.query("""
                SELECT stroke_id, author_id, stroke_data FROM whiteboard_stroke
                WHERE document_id = ? AND stroke_data IS NOT NULL ORDER BY id
                """,
                (row, index) -> new Stroke(row.getObject("stroke_id", UUID.class),
                        row.getLong("author_id"), readStroke(row.getString("stroke_data"))),
                documentId);
    }

    @Transactional
    public boolean addStroke(Long documentId, UUID strokeId, WhiteboardStrokeRequest stroke, String email) {
        Long authorId = lockBoardForEdit(documentId, email);
        if (isOwnStroke(documentId, strokeId, authorId)) return false;

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM whiteboard_stroke WHERE document_id = ? AND stroke_data IS NOT NULL
                """, Integer.class, documentId);
        if (count >= MAX_STROKES) {
            throw new BadRequestException("This whiteboard has reached its 500-mark limit. Erase a mark to make room.");
        }

        jdbc.update("""
                INSERT INTO whiteboard_stroke (document_id, stroke_id, author_id, stroke_data) VALUES (?, ?, ?, ?)
                """, documentId, strokeId, authorId, writeStroke(stroke));
        return true;
    }

    @Transactional
    public boolean undoStroke(Long documentId, UUID strokeId, String email) {
        Long authorId = lockBoardForEdit(documentId, email);
        if (!isOwnStroke(documentId, strokeId, authorId)) return false;
        return removeStrokes(documentId, List.of(strokeId));
    }

    @Transactional
    public boolean eraseStrokes(Long documentId, List<UUID> strokeIds, String email) {
        lockBoardForEdit(documentId, email);
        return removeStrokes(documentId, strokeIds);
    }

    private boolean removeStrokes(Long documentId, List<UUID> strokeIds) {
        // Keep the IDs so a retried save cannot bring an erased mark back.
        return namedJdbc.update("""
                UPDATE whiteboard_stroke SET stroke_data = NULL
                WHERE document_id = :documentId AND stroke_id IN (:strokeIds) AND stroke_data IS NOT NULL
                """, Map.of("documentId", documentId, "strokeIds", strokeIds)) != 0;
    }

    private Long lockBoardForEdit(Long documentId, String email) {
        // Serialize mutations for this board and prevent a document deletion during a write.
        if (jdbc.queryForList("SELECT id FROM document WHERE id = ? FOR UPDATE", Long.class, documentId).isEmpty()) {
            throw new ResourceNotFoundException("Document not found with id: " + documentId);
        }
        documents.requireEditorAccess(documentId, email);
        return users.findByEmail(email).orElseThrow().getId();
    }

    private boolean isOwnStroke(Long documentId, UUID strokeId, Long authorId) {
        List<Long> authors = jdbc.queryForList("""
                SELECT author_id FROM whiteboard_stroke WHERE document_id = ? AND stroke_id = ?
                """, Long.class, documentId, strokeId);
        if (authors.isEmpty()) return false;
        if (!authors.get(0).equals(authorId)) {
            throw new AccessDeniedException("You can only change your own strokes.");
        }
        return true;
    }

    private String writeStroke(WhiteboardStrokeRequest stroke) {
        try {
            return mapper.writeValueAsString(stroke);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize the whiteboard stroke.", ex);
        }
    }

    private WhiteboardStrokeRequest readStroke(String data) {
        try {
            return mapper.readValue(data, WhiteboardStrokeRequest.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not read the saved whiteboard stroke.", ex);
        }
    }

    public record Stroke(UUID id, Long authorId, WhiteboardStrokeRequest drawing) {}
}
