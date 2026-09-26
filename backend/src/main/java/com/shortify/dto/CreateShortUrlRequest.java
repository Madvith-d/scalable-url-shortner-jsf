package com.shortify.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateShortUrlRequest(@NotBlank String originalUrl, String expiresAt) {
}
