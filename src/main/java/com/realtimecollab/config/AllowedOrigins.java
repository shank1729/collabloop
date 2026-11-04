package com.realtimecollab.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Component
public class AllowedOrigins {
    private final List<String> origins;

    public AllowedOrigins(@Value("${app.cors.allowed-origins}") String configured) {
        if (configured == null || configured.isBlank()) throw invalidConfiguration();
        origins = Arrays.stream(configured.split(",", -1)).map(String::trim).distinct().toList();
        for (String origin : origins) {
            try {
                URI uri = URI.create(origin);
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) ||
                        uri.getHost() == null || origin.contains("*") || uri.getUserInfo() != null ||
                        uri.getRawQuery() != null || uri.getRawFragment() != null ||
                        !uri.getRawPath().isEmpty() || uri.getPort() == 0 || uri.getPort() > 65535) {
                    throw invalidConfiguration();
                }
            } catch (IllegalArgumentException ex) {
                throw invalidConfiguration();
            }
        }
    }

    public List<String> values() { return origins; }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException("CORS_ALLOWED_ORIGINS must contain exact HTTP(S) origins, without wildcards, paths, trailing slashes or empty entries.");
    }
}
