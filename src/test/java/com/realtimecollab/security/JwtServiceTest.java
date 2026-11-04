package com.realtimecollab.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.userdetails.User;
import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {
    private static final String KEY = "7d9a4f2e6b8c1d3e5a7f9b0c2d4e6f81";

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "short", "replace-with-a-unique-random-secret-of-at-least-32-bytes",
            "realtime-collab-render-demo-secret-change-later-2026"})
    void rejectsMissingWeakOrPlaceholderKeys(String secret) {
        assertThatThrownBy(() -> new JwtService(secret, 86400000, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JWT_SECRET");
    }

    @ParameterizedTest
    @ValueSource(strings = {"local-development-only-secret-at-least-32-bytes", "test-secret-key-for-jwt-signing-that-is-long-enough"})
    void productionRejectsDocumentedDevelopmentKeys(String secret) {
        assertThatThrownBy(() -> new JwtService(secret, 86400000, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesConfigurationAndSignsTokensForTheExpectedUser() {
        assertThatThrownBy(() -> new JwtService(KEY, 0, true)).hasMessageContaining("JWT_EXPIRATION_MS");
        var jwt = new JwtService(KEY, 86400000, true);
        var owner = User.withUsername("owner@example.com").password("unused").authorities("USER").build();
        var other = User.withUsername("other@example.com").password("unused").authorities("USER").build();
        String token = jwt.generateToken(owner);
        assertThat(jwt.isTokenValid(token, owner)).isTrue();
        assertThat(jwt.isTokenValid(token, other)).isFalse();
        assertThat(jwt.extractExpiration(token)).isAfter(java.time.Instant.now());
    }

    @Test
    void rejectsSignedTokensWithoutExpiryOrSubject() {
        var jwt = new JwtService(KEY, 86400000, true);
        var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String noExpiry = io.jsonwebtoken.Jwts.builder().subject("owner@example.com").signWith(key).compact();
        String noSubject = io.jsonwebtoken.Jwts.builder().expiration(new java.util.Date(System.currentTimeMillis() + 60000))
                .signWith(key).compact();
        assertThatThrownBy(() -> jwt.extractExpiration(noExpiry)).isInstanceOf(io.jsonwebtoken.JwtException.class);
        assertThatThrownBy(() -> jwt.extractUsername(noSubject)).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }
}
