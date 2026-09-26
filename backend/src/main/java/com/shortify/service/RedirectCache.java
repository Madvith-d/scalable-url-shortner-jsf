package com.shortify.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortify.entity.ShortUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RedirectCache {

    public record Target(Long id, String destination, boolean active, Instant expiresAt) {
        public static Target from(ShortUrl url) {
            return new Target(url.getId(), url.getOriginalUrl(), url.isActive(), url.getExpiresAt());
        }
    }

    public record Changed(String code) { }
    public record Entry(Target target, Instant validUntil) { }

    private static final Logger log = LoggerFactory.getLogger(RedirectCache.class);
    private static final DefaultRedisScript<Long> POPULATE = new DefaultRedisScript<>("""
            if (redis.call('GET', KEYS[1]) or '') ~= ARGV[1] then return 0 end
            local t = redis.call('TIME')
            local remaining = math.min(tonumber(ARGV[4]), tonumber(ARGV[3]) - (t[1] * 1000 + math.floor(t[2] / 1000)))
            if remaining <= 0 then return 0 end
            redis.call('SET', KEYS[2], ARGV[2], 'PX', remaining)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return redis.call('DEL', KEYS[2])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String prefix;
    private final Duration ttl;
    private final LongAdder failures = new LongAdder();

    public RedirectCache(StringRedisTemplate redis, ObjectMapper mapper, Clock clock,
                         @Value("${shortify.redis.prefix}") String prefix,
                         @Value("${shortify.cache.ttl}") Duration ttl) {
        if (ttl.toMillis() < 1 || ttl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Cache TTL must be positive and at most five minutes.");
        }
        this.redis = redis;
        this.mapper = mapper;
        this.clock = clock;
        this.prefix = prefix;
        this.ttl = ttl;
    }

    public Target resolve(String code, Supplier<Target> load) {
        Instant deadline = clock.instant().plus(ttl);
        String generation;
        try {
            generation = redis.opsForValue().get(generationKey(code));
            String json = redis.opsForValue().get(dataKey(code));
            if (json != null) {
                Entry entry = mapper.readValue(json, Entry.class);
                if (entry.validUntil().isAfter(clock.instant())) {
                    return entry.target();
                }
            }
        } catch (RuntimeException | JsonProcessingException exception) {
            failed();
            return load.get();
        }
        Target target = load.get();
        if (target.expiresAt() != null && target.expiresAt().isBefore(deadline)) {
            deadline = target.expiresAt();
        }
        if (deadline.isAfter(clock.instant())) {
            try {
                redis.execute(POPULATE, List.of(generationKey(code), dataKey(code)),
                        generation == null ? "" : generation, mapper.writeValueAsString(new Entry(target, deadline)),
                        Long.toString(deadline.toEpochMilli()), Long.toString(ttl.toMillis()));
            } catch (RuntimeException | JsonProcessingException exception) {
                failed();
            }
        }
        return target;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidate(Changed event) {
        try {
            redis.execute(INVALIDATE, List.of(generationKey(event.code()), dataKey(event.code())),
                    UUID.randomUUID().toString(), Long.toString(ttl.multipliedBy(2).toMillis()));
        } catch (RuntimeException exception) {
            failed();
        }
    }

    private void failed() {
        failures.increment();
        long count = failures.sum();
        if (count == 1 || count % 100 == 0) {
            log.warn("Redis cache operation failed; bounded-TTL PostgreSQL fallback (failures={})", count);
        }
    }

    private String generationKey(String code) {
        return prefix + "url:{" + code + "}:generation";
    }

    private String dataKey(String code) {
        return prefix + "url:{" + code + "}:data";
    }
}
