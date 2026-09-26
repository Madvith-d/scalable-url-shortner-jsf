package com.shortify.dto;

public record AuthResponse(String accessToken, String tokenType, long expiresIn, String email) {
}
