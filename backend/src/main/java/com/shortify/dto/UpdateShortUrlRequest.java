package com.shortify.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateShortUrlRequest(@NotNull Boolean active) {
}
