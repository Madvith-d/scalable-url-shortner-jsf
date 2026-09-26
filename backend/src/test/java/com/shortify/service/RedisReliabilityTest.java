package com.shortify.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisReliabilityTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void unavailableCacheFallsBackToPostgreSqlSupplier() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("offline"));
        var cache = cache(Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicInteger reads = new AtomicInteger();
        var target = target(true);
        assertThat(cache.resolve("code", () -> { reads.incrementAndGet(); return target; })).isEqualTo(target);
        assertThat(reads).hasValue(1);
    }

    @Test
    void failedInvalidationDoesNotFailCommittedMutationAndRecoveredStaleEntryHasAbsoluteDeadline() throws Exception {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("test:url:{code}:data")).thenReturn(mapper.writeValueAsString(
                new RedirectCache.Entry(target(true), NOW.plusSeconds(30))));
        doThrow(new RedisConnectionFailureException("offline")).when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        var cache = cache(clock);
        assertThatCode(() -> cache.invalidate(new RedirectCache.Changed("code"))).doesNotThrowAnyException();
        Supplier<RedirectCache.Target> load = mock(Supplier.class);
        when(load.get()).thenReturn(target(false));
        assertThat(cache.resolve("code", load).active()).isTrue();
        verify(load, never()).get();
        when(clock.instant()).thenReturn(NOW.plusSeconds(31));
        assertThat(cache.resolve("code", load).active()).isFalse();
        verify(load).get();
    }

    @Test
    void slowMissCannotPopulateBeyondItsOriginalLifetime() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(31));
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        assertThat(cache(clock).resolve("code", () -> target(true)).active()).isTrue();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void corruptCachePayloadFallsBackRatherThanReturningServerError() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("bad-json");
        assertThat(cache(Clock.fixed(NOW, ZoneOffset.UTC)).resolve("code", () -> target(false)).active()).isFalse();
    }

    @Test
    void authAndCreateFailClosedWhileRedirectFallbackIsGloballyBoundedWithoutPerIpState() {
        doThrow(new RedisConnectionFailureException("offline")).when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        var limiter = new RateLimiter(redis, "test:", "secret", Duration.ofSeconds(60), 2, 2, 2, 2);
        for (String category : List.of("auth", "create")) {
            assertThat(limiter.check(category, "127.0.0.1").status()).isEqualTo(503);
        }
        assertThat(limiter.check("redirect", "1.1.1.1").status()).isEqualTo(200);
        assertThat(limiter.check("redirect", "2.2.2.2").status()).isEqualTo(200);
        assertThat(limiter.check("redirect", "3.3.3.3").status()).isEqualTo(503);
        assertThat(limiter.check("redirect", "4.4.4.4").retryAfter()).isPositive();
    }

    private RedirectCache cache(Clock clock) {
        return new RedirectCache(redis, mapper, clock, "test:", Duration.ofSeconds(30));
    }

    private RedirectCache.Target target(boolean active) {
        return new RedirectCache.Target(1L, "https://example.com", active, null);
    }
}
