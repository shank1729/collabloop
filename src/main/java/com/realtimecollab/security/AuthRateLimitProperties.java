package com.realtimecollab.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.auth.rate-limit")
public record AuthRateLimitProperties(
        @Min(1) @Max(1000) int requests,
        @Min(1) @Max(3600) int windowSeconds,
        @Min(1) @Max(100000) int maxClients) {
}
