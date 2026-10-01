package com.shortify.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateShortUrlRequest(@NotBlank String originalUrl, String expiresAt, String customAlias,
                                    String activatesAt, Long maxClicks) {
    public CreateShortUrlRequest(String originalUrl, String expiresAt, String customAlias) {
        this(originalUrl, expiresAt, customAlias, null, null);
    }
}
