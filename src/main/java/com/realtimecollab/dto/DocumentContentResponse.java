package com.realtimecollab.dto;

import java.time.LocalDateTime;

public record DocumentContentResponse(Long id, String content, Long version, LocalDateTime updatedAt) {}
