package com.realtimecollab.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class AllowedOriginsTest {
    @Test
    void acceptsExactOriginsAndRemovesDuplicates() {
        assertThat(new AllowedOrigins(" http://localhost:3000,https://app.example.com,http://localhost:3000 ").values())
                .containsExactly("http://localhost:3000", "https://app.example.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "*", "https://*.example.com", "https://app.example.com/", "https://app.example.com/path",
            "https://user:password@app.example.com", "https://app.example.com?query=1", "https://app.example.com#fragment",
            "file:///tmp", "null", "http://localhost:0", "http://localhost:65536", "http://localhost:3000,"})
    void rejectsAmbiguousOrWildcardConfiguration(String value) {
        assertThatThrownBy(() -> new AllowedOrigins(value)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CORS_ALLOWED_ORIGINS");
    }
}
