package com.shortify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortify.entity.ShortUrl;
import com.shortify.repository.ShortUrlRepository;
import com.shortify.repository.UserRepository;
import com.shortify.service.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"shortify.rate-limit.auth=2", "shortify.rate-limit.create=2", "shortify.rate-limit.redirect=2"})
class RateLimitApiIT extends RedisIntegrationSupport {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private ShortUrlRepository urls;
    @Autowired private UserRepository users;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RateLimiter limiter;
    @Autowired private ThreadPoolTaskExecutor analyticsExecutor;
    @Value("${shortify.redis.prefix}") private String prefix;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private String token;
    private long owner;
    private ShortUrl url;

    @BeforeEach
    void setup() throws Exception {
        clearLimits();
        String email = UUID.randomUUID() + "@example.com";
        var response = send("POST", "/api/auth/register", "{\"email\":\"" + email + "\",\"password\":\"test-password-123\"}", false);
        assertThat(response.statusCode()).isEqualTo(201);
        token = mapper.readTree(response.body()).path("accessToken").asText();
        owner = users.findByEmail(email).orElseThrow().getId();
        url = urls.saveAndFlush(new ShortUrl("limit-" + UUID.randomUUID(), "https://example.com", null, true,
                users.findById(owner).orElseThrow()));
        clearLimits();
    }

    @AfterEach
    void cleanup() {
        await().atMost(Duration.ofSeconds(5)).until(() -> analyticsExecutor.getActiveCount() == 0
                && analyticsExecutor.getThreadPoolExecutor().getQueue().isEmpty());
        urls.deleteById(url.getId());
        users.deleteById(owner);
        clearLimits();
    }

    @Test
    void authenticationReturnsJson429WithRetryAfterAcrossLoginAndRegister() throws Exception {
        assertThat(send("POST", "/api/auth/login", "{}", false).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/auth/register", "{}", false).statusCode()).isEqualTo(400);
        assertLimited(send("POST", "/api/auth/login", "{}", false));
    }

    @Test
    void creationReturnsJson429WithoutLimitingManagementReads() throws Exception {
        assertThat(send("POST", "/api/urls", "{}", true).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/urls", "{}", true).statusCode()).isEqualTo(400);
        assertLimited(send("POST", "/api/urls", "{}", true));
        for (int i = 0; i < 4; i++) {
            assertThat(send("GET", "/api/urls", null, true).statusCode()).isEqualTo(200);
            assertThat(send("GET", "/api/urls/" + url.getId() + "/analytics", null, true).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void redirectReturns429AndSpoofedForwardingHeadersCannotBypassIt() throws Exception {
        assertThat(send("GET", "/" + url.getShortCode(), null, false).statusCode()).isEqualTo(302);
        assertThat(send("GET", "/" + url.getShortCode(), null, false).statusCode()).isEqualTo(302);
        assertLimited(send("GET", "/" + url.getShortCode(), null, false));
        for (String path : List.of("/assets/test.js", "/static/test.css", "/error")) {
            assertThat(send("GET", path, null, false).statusCode()).isNotEqualTo(429);
        }
        var keys = redis.keys(prefix + "limit:*");
        assertThat(keys).hasSize(1);
        for (String key : keys) {
            assertThat(key).matches(java.util.regex.Pattern.quote(prefix) + "limit:redirect:[a-f0-9]{64}");
            assertThat(key).doesNotContain("127.0.0.1", "198.51.100");
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 60000L);
        }
    }

    @Test
    void atomicLuaAllowsExactlyTheConfiguredCountUnderConcurrency() throws Exception {
        try (var executor = Executors.newFixedThreadPool(10)) {
            List<Future<RateLimiter.Decision>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> limiter.check("auth", "203.0.113.22")));
            }
            int allowed = 0;
            for (var future : futures) {
                var decision = future.get(5, TimeUnit.SECONDS);
                assertThat(decision.status()).isIn(200, 429);
                if (decision.status() == 200) { allowed++; }
            }
            assertThat(allowed).isEqualTo(2);
        }
    }

    @Test
    void expiredWindowAllowsRequestsAgain() {
        var shortWindow = new RateLimiter(redis, prefix + "window:", "test-secret", Duration.ofMillis(100), 1, 1, 1, 1);
        assertThat(shortWindow.check("auth", "203.0.113.1").status()).isEqualTo(200);
        assertThat(shortWindow.check("auth", "203.0.113.1").status()).isEqualTo(429);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(shortWindow.check("auth", "203.0.113.1").status()).isEqualTo(200));
    }

    private void assertLimited(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 60L);
        var body = mapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.path("code").asText()).isEqualTo("RATE_LIMITED");
    }

    private HttpResponse<String> send(String method, String path, String body, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Forwarded-For", "198.51.100." + java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 255))
                .header("Forwarded", "for=203.0.113.1");
        if (authenticated) { request.header("Authorization", "Bearer " + token); }
        if (body != null) { request.header("Content-Type", "application/json"); }
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void clearLimits() {
        var keys = redis.keys(prefix + "limit:*");
        if (keys != null && !keys.isEmpty()) { redis.delete(keys); }
    }
}
