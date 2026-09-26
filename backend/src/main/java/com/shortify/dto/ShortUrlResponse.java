package com.shortify.dto;

import java.time.Instant;

public record ShortUrlResponse(Long id, String shortCode, String shortUrl, String originalUrl,
                               Instant createdAt, Instant expiresAt, boolean active) {
}
