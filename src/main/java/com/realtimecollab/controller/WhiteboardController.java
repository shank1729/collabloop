package com.realtimecollab.controller;

import com.realtimecollab.dto.WhiteboardStrokeRequest;
import com.realtimecollab.service.WhiteboardService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/documents/{documentId}/whiteboard")
public class WhiteboardController {
    private final WhiteboardService whiteboard;
    private final SimpMessagingTemplate messaging;

    public WhiteboardController(WhiteboardService whiteboard, SimpMessagingTemplate messaging) {
        this.whiteboard = whiteboard;
        this.messaging = messaging;
    }

    @GetMapping
    public ResponseEntity<List<WhiteboardService.Stroke>> getBoard(@PathVariable Long documentId, Principal user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(whiteboard.getBoard(documentId, user.getName()));
    }

    @PutMapping("/strokes/{strokeId}")
    public ResponseEntity<Void> addStroke(
            @PathVariable Long documentId, @PathVariable UUID strokeId,
            @Valid @RequestBody WhiteboardStrokeRequest stroke, Principal user) {
        if (whiteboard.addStroke(documentId, strokeId, stroke, user.getName())) notifyBoardChanged(documentId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/strokes/{strokeId}")
    public ResponseEntity<Void> undoStroke(@PathVariable Long documentId, @PathVariable UUID strokeId, Principal user) {
        if (whiteboard.undoStroke(documentId, strokeId, user.getName())) notifyBoardChanged(documentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/erase")
    public ResponseEntity<Void> eraseStrokes(
            @PathVariable Long documentId, @Valid @RequestBody EraseRequest request, Principal user) {
        if (whiteboard.eraseStrokes(documentId, request.strokeIds(), user.getName())) notifyBoardChanged(documentId);
        return ResponseEntity.noContent().build();
    }

    public record EraseRequest(@NotNull @Size(min = 1, max = 500) List<UUID> strokeIds) {
        public EraseRequest {
            if (strokeIds != null && strokeIds.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("A whiteboard mark ID cannot be null.");
            }
        }
    }

    private void notifyBoardChanged(Long documentId) {
        // The service's transaction has committed before subscribers are notified.
        messaging.convertAndSend("/topic/documents/" + documentId + "/whiteboard", Map.of("documentId", documentId));
    }
}
