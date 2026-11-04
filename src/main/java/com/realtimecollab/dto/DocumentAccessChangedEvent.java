package com.realtimecollab.dto;

public record DocumentAccessChangedEvent(Long documentId, String email, String role) {
}
