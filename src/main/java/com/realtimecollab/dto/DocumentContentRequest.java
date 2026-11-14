package com.realtimecollab.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record DocumentContentRequest(
        @NotNull String content,
        @NotNull @Min(0) Long baseVersion,
        @NotNull UUID editId) {}
