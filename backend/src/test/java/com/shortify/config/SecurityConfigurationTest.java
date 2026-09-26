package com.shortify.config;

import java.security.SecureRandom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigurationTest {

    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "1234567890123456789012345678901", "                                "})
    void rejectsWeakOrEmptySecret(String secret) {
        assertThatThrownBy(() -> configuration.jwtKey(secret)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JWT_SECRET must contain at least 32 UTF-8 bytes.");
    }

    @Test
    void acceptsRandomSecretOfAtLeast32Bytes() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String secret = java.util.HexFormat.of().formatHex(bytes);
        assertThat(configuration.jwtKey(secret).getEncoded()).hasSize(64);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "http://*.example.com", "http://localhost:3000/", "http://localhost:3000/path",
            "http://user@localhost:3000", "http://localhost:3000?x=1", "http://localhost:3000#fragment"})
    void rejectsNonExactCorsOrigins(String origin) {
        assertThatThrownBy(() -> configuration.corsFilter(origin, new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }
}
