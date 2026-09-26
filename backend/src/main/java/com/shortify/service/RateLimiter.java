package com.shortify.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {

    public record Decision(int status, long retryAfter) { }

    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if count > tonumber(ARGV[2]) then return redis.call('PTTL', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String prefix;
    private final byte[] secret;
    private final long windowMillis;
    private final int authLimit;
    private final int createLimit;
    private final int redirectLimit;
    private final int fallbackLimit;
    private long fallbackStarted = System.nanoTime();
    private int fallbackCount;

    public RateLimiter(StringRedisTemplate redis, @Value("${shortify.redis.prefix}") String prefix,
                       @Value("${shortify.jwt.secret}") String secret,
                       @Value("${shortify.rate-limit.window}") Duration window,
                       @Value("${shortify.rate-limit.auth}") int authLimit,
                       @Value("${shortify.rate-limit.create}") int createLimit,
                       @Value("${shortify.rate-limit.redirect}") int redirectLimit,
                       @Value("${shortify.rate-limit.redirect-fallback}") int fallbackLimit) {
        if (window.toMillis() < 1 || window.compareTo(Duration.ofDays(1)) > 0
                || Math.min(Math.min(authLimit, createLimit), Math.min(redirectLimit, fallbackLimit)) < 1) {
            throw new IllegalArgumentException("Rate limits must be positive and window at most one day.");
        }
        this.redis = redis;
        this.prefix = prefix;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.windowMillis = window.toMillis();
        this.authLimit = authLimit;
        this.createLimit = createLimit;
        this.redirectLimit = redirectLimit;
        this.fallbackLimit = fallbackLimit;
    }

    public Decision check(String category, String remoteAddress) {
        int limit = switch (category) {
            case "auth" -> authLimit;
            case "create" -> createLimit;
            case "redirect" -> redirectLimit;
            default -> throw new IllegalArgumentException("Unknown rate-limit category.");
        };
        try {
            Long retry = redis.execute(LIMIT, List.of(prefix + "limit:" + category + ":" + hash(remoteAddress)),
                    Long.toString(windowMillis), Integer.toString(limit));
            if (retry == null || retry < 0) {
                throw new IllegalStateException("Invalid limiter result.");
            }
            return new Decision(retry == 0 ? 200 : 429, Math.max(1, (retry + 999) / 1000));
        } catch (RuntimeException exception) {
            return category.equals("redirect") ? fallback() : new Decision(503, 1);
        }
    }

    private synchronized Decision fallback() {
        long elapsed = (System.nanoTime() - fallbackStarted) / 1_000_000;
        if (elapsed >= windowMillis) {
            fallbackStarted = System.nanoTime();
            fallbackCount = 0;
            elapsed = 0;
        }
        if (fallbackCount >= fallbackLimit) {
            return new Decision(503, Math.max(1, (windowMillis - elapsed + 999) / 1000));
        }
        fallbackCount++;
        return new Decision(200, 0);
    }

    private String hash(String address) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(address.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC unavailable.", exception);
        }
    }
}
