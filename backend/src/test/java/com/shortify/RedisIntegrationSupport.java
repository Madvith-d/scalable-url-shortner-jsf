package com.shortify;

import java.util.UUID;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "shortify.rate-limit.auth=10000", "shortify.rate-limit.create=10000",
        "shortify.rate-limit.redirect=10000", "shortify.cleanup.interval=3600000"
})
abstract class RedisIntegrationSupport {

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        String prefix = "shortify-test:" + UUID.randomUUID() + ":";
        registry.add("shortify.redis.prefix", () -> prefix);
    }
}
