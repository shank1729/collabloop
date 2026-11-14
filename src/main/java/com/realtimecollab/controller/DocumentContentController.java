package com.realtimecollab.controller;

import com.realtimecollab.dto.DocumentContentRequest;
import com.realtimecollab.dto.DocumentContentResponse;
import com.realtimecollab.service.DocumentContentService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/documents/{id}/content")
public class DocumentContentController {
    private final DocumentContentService content;
    private final SimpMessagingTemplate messaging;

    public DocumentContentController(DocumentContentService content, SimpMessagingTemplate messaging) {
        this.content = content;
        this.messaging = messaging;
    }

    @PutMapping
    public ResponseEntity<DocumentContentResponse> save(@PathVariable Long id,
            @Valid @RequestBody DocumentContentRequest request, Principal principal) {
        var saved = content.save(id, request, principal.getName());
        // The transaction has committed before the acknowledgement and broadcast.
        messaging.convertAndSend("/topic/documents/" + id, saved);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(saved);
    }
}
