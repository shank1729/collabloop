package com.realtimecollab.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;
import java.util.Locale;
import java.time.Instant;
import io.jsonwebtoken.JwtException;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs,
            @Value("${app.security.production:false}") boolean production) {
        String normalized = secret == null ? "" : secret.toLowerCase(Locale.ROOT);
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32 ||
                normalized.startsWith("replace") || normalized.contains("change-later") ||
                (production && (normalized.startsWith("local-") || normalized.startsWith("test-") || normalized.contains("-demo-")))) {
            throw new IllegalArgumentException("JWT_SECRET is required and must be a unique random secret of at least 32 UTF-8 bytes; example secrets are not accepted in production.");
        }
        if (expirationMs <= 0) throw new IllegalArgumentException("JWT_EXPIRATION_MS must be positive.");
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String extractUsername(String token) {
        String username = extractClaim(token, Claims::getSubject);
        if (username == null || username.isBlank()) throw new JwtException("Token subject is required.");
        return username;
    }

    public Instant extractExpiration(String token) {
        Date expiration = extractClaim(token, Claims::getExpiration);
        if (expiration == null) throw new JwtException("Token expiration is required.");
        return expiration.toInstant();
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String generateToken(UserDetails userDetails) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(userDetails.getUsername())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return !Instant.now().isBefore(extractExpiration(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
