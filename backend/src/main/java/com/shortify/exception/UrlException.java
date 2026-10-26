package com.shortify.exception;

import java.time.Instant;

import org.springframework.http.HttpStatus;

public class UrlException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Instant activatesAt;
    private final Instant expiresAt;
    private final Long maxClicks;
    private final Long clickCount;

    public UrlException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null, null, null);
    }

    public UrlException(HttpStatus status, String code, String message, Instant activatesAt, Instant expiresAt,
                        Long maxClicks, Long clickCount) {
        super(message);
        this.status = status;
        this.code = code;
        this.activatesAt = activatesAt;
        this.expiresAt = expiresAt;
        this.maxClicks = maxClicks;
        this.clickCount = clickCount;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Instant getActivatesAt() {
        return activatesAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Long getMaxClicks() {
        return maxClicks;
    }

    public Long getClickCount() {
        return clickCount;
    }
}
