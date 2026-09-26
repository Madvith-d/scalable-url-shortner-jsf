package com.shortify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortify.entity.ShortUrl;
import com.shortify.repository.ClickEventRepository;
import com.shortify.repository.ShortUrlRepository;
import com.shortify.repository.UserRepository;
import com.shortify.service.AnalyticsService;
import com.shortify.service.RedirectCache;
import com.shortify.service.ShortUrlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"shortify.cleanup.interval=250", "shortify.cleanup.batch-size=2",
        "shortify.analytics.workers=1", "shortify.analytics.queue-capacity=1", "shortify.cache.ttl=3s"})
class Phase4ApiIT extends RedisIntegrationSupport {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @MockitoSpyBean private ShortUrlRepository urls;
    @MockitoSpyBean private ClickEventRepository clicks;
    @Autowired private UserRepository users;
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private RedirectCache cache;
    @Autowired private ShortUrlService service;
    @Autowired private AnalyticsService analytics;
    @Autowired private ThreadPoolTaskExecutor analyticsExecutor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationEventPublisher events;
    @Value("${shortify.redis.prefix}") private String prefix;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<Long> ids = new ArrayList<>();
    private final List<Long> ownerIds = new ArrayList<>();
    private String token;
    private long owner;

    @BeforeEach
    void account() throws Exception {
        token = register();
        owner = ownerIds.getLast();
    }

    @AfterEach
    void cleanup() {
        await().atMost(Duration.ofSeconds(10)).until(() -> analyticsExecutor.getActiveCount() == 0
                && analyticsExecutor.getThreadPoolExecutor().getQueue().isEmpty());
        reset(urls, clicks);
        urls.deleteAllById(ids);
        users.deleteAllById(ownerIds);
    }

    @Test
    void missesPopulateAndHitsResolveIdAndDestinationWithoutPostgreSql() throws Exception {
        var url = create(null);
        clearInvocations(urls);
        assertThat(get("/" + url.getShortCode()).statusCode()).isEqualTo(302);
        awaitClicks(url.getId(), 1);
        assertThat(redis.hasKey(key(url))).isTrue();
        assertThat(redis.getExpire(key(url), TimeUnit.MILLISECONDS)).isBetween(1L, 3000L);
        assertThat(get("/" + url.getShortCode()).headers().firstValue("Location")).contains(url.getOriginalUrl());
        awaitClicks(url.getId(), 2);
        verify(urls, times(1)).findByShortCode(url.getShortCode());
        assertThat(mapper.readValue(redis.opsForValue().get(key(url)), RedirectCache.Entry.class).target().id())
                .isEqualTo(url.getId());
    }

    @Test
    void patchAndDeleteInvalidateAfterCommit() throws Exception {
        var url = create(null);
        get("/" + url.getShortCode());
        assertThat(redis.hasKey(key(url))).isTrue();
        assertThat(send("PATCH", "/api/urls/" + url.getId(), "{\"active\":false}", token).statusCode()).isEqualTo(200);
        assertThat(redis.hasKey(key(url))).isFalse();
        assertThat(get("/" + url.getShortCode()).statusCode()).isEqualTo(410);
        assertThat(send("PATCH", "/api/urls/" + url.getId(), "{\"active\":true}", token).statusCode()).isEqualTo(200);
        assertThat(get("/" + url.getShortCode()).statusCode()).isEqualTo(302);
        assertThat(send("DELETE", "/api/urls/" + url.getId(), null, token).statusCode()).isEqualTo(204);
        assertThat(redis.hasKey(key(url))).isFalse();
        assertThat(get("/" + url.getShortCode()).statusCode()).isEqualTo(410);
    }

    @Test
    void rollbackDoesNotInvalidateAndCommitDoes() {
        var url = create(null);
        service.resolve(url.getShortCode());
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            urls.findById(url.getId()).orElseThrow().deactivate();
            events.publishEvent(new RedirectCache.Changed(url.getShortCode()));
            assertThat(redis.hasKey(key(url))).isTrue();
            status.setRollbackOnly();
        });
        assertThat(redis.hasKey(key(url))).isTrue();
        assertThat(urls.findById(url.getId()).orElseThrow().isActive()).isTrue();
        tx.executeWithoutResult(status -> {
            urls.findById(url.getId()).orElseThrow().deactivate();
            events.publishEvent(new RedirectCache.Changed(url.getShortCode()));
            assertThat(redis.hasKey(key(url))).isTrue();
        });
        assertThat(redis.hasKey(key(url))).isFalse();
    }

    @Test
    void inflightMissCannotRepopulateAfterCommittedInvalidation() throws Exception {
        var url = create(null);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> cache.resolve(url.getShortCode(), () -> {
                var snapshot = RedirectCache.Target.from(urls.findByShortCode(url.getShortCode()).orElseThrow());
                loaded.countDown();
                waitFor(release);
                return snapshot;
            }));
            try {
                assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(send("DELETE", "/api/urls/" + url.getId(), null, token).statusCode()).isEqualTo(204);
            } finally {
                release.countDown();
            }
            future.get(5, TimeUnit.SECONDS);
        }
        assertThat(redis.hasKey(key(url))).isFalse();
        assertThat(get("/" + url.getShortCode()).statusCode()).isEqualTo(410);
    }

    @Test
    void expiryIsCheckedEvenOnCacheHitAndPopulationTtlIsBoundedByExpiry() throws Exception {
        var url = create(Instant.now().plusSeconds(2));
        service.resolve(url.getShortCode());
        assertThat(redis.getExpire(key(url), TimeUnit.MILLISECONDS)).isBetween(1L, 2000L);
        var expired = new RedirectCache.Target(url.getId(), url.getOriginalUrl(), true, Instant.now().minusSeconds(1));
        redis.opsForValue().set(key(url), mapper.writeValueAsString(new RedirectCache.Entry(expired,
                Instant.now().plusSeconds(2))), Duration.ofSeconds(2));
        clearInvocations(urls);
        var response = get("/" + url.getShortCode());
        assertThat(response.statusCode()).isEqualTo(410);
        assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("URL_EXPIRED");
        verify(urls, never()).findByShortCode(anyString());
        assertThat(clicks.countByShortUrlId(url.getId())).isZero();
    }

    @Test
    void analyticsPersistAsynchronouslyAndExposeOnlyOwnerAggregatesAndPrivateMetadata() throws Exception {
        var url = create(null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        analyticsExecutor.execute(() -> {
            assertThat(Thread.currentThread().getName()).startsWith("analytics-");
            entered.countDown();
            waitFor(release);
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            var request = HttpRequest.newBuilder(uri("/" + url.getShortCode()))
                    .header("Referer", "https://person:secret@EXAMPLE.com/private/path?token=secret#fragment")
                    .header("User-Agent", "private-user-agent Mobile identifier")
                    .header("X-Forwarded-For", "198.51.100.9").header("X-Country", "PrivateCountry").GET().build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(302);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(clicks.countByShortUrlId(url.getId())).isZero();
        } finally {
            release.countDown();
        }
        awaitClicks(url.getId(), 1);
        reset(clicks);
        var rows = jdbc.queryForList("SELECT * FROM click_events WHERE short_url_id = ?", url.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("referrer", "example.com").containsEntry("device", "Mobile")
                .containsEntry("geography", "Unknown");
        assertThat(rows.toString()).doesNotContain("secret", "private", "198.51.100.9", "PrivateCountry");
        assertThat(rows.getFirst().keySet()).containsExactlyInAnyOrder("id", "short_url_id", "accessed_at", "referrer", "device", "geography");
        var response = send("GET", "/api/urls/" + url.getId() + "/analytics", null, token);
        JsonNode body = mapper.readTree(response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.size()).isEqualTo(5);
        assertThat(body.path("totalClicks").asLong()).isEqualTo(1);
        assertThat(body.path("clicksOverTime").get(0).path("date").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(body.path("referrers").get(0)).isEqualTo(mapper.valueToTree(Map.of("label", "example.com", "clicks", 1)));
        assertThat(body.path("devices").get(0).path("label").asText()).isEqualTo("Mobile");
        assertThat(body.path("geography").get(0).path("label").asText()).isEqualTo("Unknown");
        String other = register();
        assertThat(send("GET", "/api/urls/" + url.getId() + "/analytics", null, other).statusCode()).isEqualTo(404);
        assertThat(get("/api/urls/" + url.getId() + "/analytics").statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/urls/9223372036854775807/analytics", null, token).statusCode()).isEqualTo(404);
    }

    @Test
    void redisOutageFailsAuthAndCreationClosedButAllowsBoundedDatabaseRedirectFallback() throws Exception {
        var url = create(null);
        service.resolve(url.getShortCode());
        assertThat(redis.hasKey(key(url))).isTrue();
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"))
                .when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                        org.mockito.ArgumentMatchers.anyList(), any(Object[].class));
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"))
                .when(redis).opsForValue();
        try {
            for (String path : List.of("/api/auth/login", "/api/auth/register", "/api/urls")) {
                var response = send("POST", path, "{}", token);
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(response.headers().firstValue("Retry-After")).contains("1");
                assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("RATE_LIMIT_UNAVAILABLE");
            }
            clearInvocations(urls);
            assertThat(get("/" + url.getShortCode()).statusCode()).isEqualTo(302);
            verify(urls).findByShortCode(url.getShortCode());
            assertThat(send("DELETE", "/api/urls/" + url.getId(), null, token).statusCode()).isEqualTo(204);
            assertThat(get("/" + url.getShortCode()).statusCode()).isEqualTo(410);
        } finally {
            reset(redis);
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(get("/" + url.getShortCode()).statusCode()).isEqualTo(410));
    }

    @Test
    void aggregateQueriesGroupUtcDaysAndCategoriesWithoutLoadingRawEvents() throws Exception {
        var url = create(null);
        clicks.saveAllAndFlush(List.of(
                new com.shortify.entity.ClickEvent(url.getId(), Instant.parse("2026-01-01T23:59:59Z"), "Direct", "Desktop", "Unknown"),
                new com.shortify.entity.ClickEvent(url.getId(), Instant.parse("2026-01-02T00:00:00Z"), "example.com", "Mobile", "Unknown"),
                new com.shortify.entity.ClickEvent(url.getId(), Instant.parse("2026-01-02T01:00:00Z"), "example.com", "Mobile", "Unknown")));
        clearInvocations(clicks);
        JsonNode body = mapper.readTree(send("GET", "/api/urls/" + url.getId() + "/analytics", null, token).body());
        assertThat(body.path("totalClicks").asLong()).isEqualTo(3);
        assertThat(body.path("clicksOverTime")).isEqualTo(mapper.valueToTree(List.of(
                Map.of("date", "2026-01-01", "clicks", 1), Map.of("date", "2026-01-02", "clicks", 2))));
        assertThat(body.path("referrers")).isEqualTo(mapper.valueToTree(List.of(
                Map.of("label", "example.com", "clicks", 2), Map.of("label", "Direct", "clicks", 1))));
        assertThat(body.path("devices").get(0)).isEqualTo(mapper.valueToTree(Map.of("label", "Mobile", "clicks", 2)));
        assertThat(body.path("geography").get(0)).isEqualTo(mapper.valueToTree(Map.of("label", "Unknown", "clicks", 3)));
        verify(clicks, never()).findAll();
    }

    @Test
    void emptyAnalyticsUsesEmptyArraysAndHeadOrInvalidRedirectsDoNotCount() throws Exception {
        var url = create(null);
        assertThat(send("HEAD", "/" + url.getShortCode(), null, null).statusCode()).isEqualTo(302);
        assertThat(get("/unknown-" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        send("DELETE", "/api/urls/" + url.getId(), null, token);
        assertThat(get("/" + url.getShortCode()).statusCode()).isEqualTo(410);
        JsonNode body = mapper.readTree(send("GET", "/api/urls/" + url.getId() + "/analytics", null, token).body());
        assertThat(body.path("totalClicks").asLong()).isZero();
        for (String field : List.of("clicksOverTime", "referrers", "devices", "geography")) {
            assertThat(body.path(field).isArray()).isTrue();
            assertThat(body.path(field).size()).isZero();
        }
    }

    @Test
    void saturatedExecutorDropsWithoutRunningOnRedirectThread() throws Exception {
        var url = create(null);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        analyticsExecutor.execute(() -> { running.countDown(); waitFor(release); });
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        analyticsExecutor.execute(() -> waitFor(release));
        long before = analytics.droppedCount();
        try {
            assertThat(get("/" + url.getShortCode()).statusCode()).isEqualTo(302);
            assertThat(analytics.droppedCount()).isEqualTo(before + 1);
            assertThat(analyticsExecutor.getPoolSize()).isEqualTo(1);
            assertThat(analyticsExecutor.getThreadPoolExecutor().getQueue()).hasSize(1);
            assertThat(clicks.countByShortUrlId(url.getId())).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    void schedulerAutomaticallyDeactivatesBoundedBatchesPreservingOwnershipAndHistory() throws Exception {
        Instant expiry = Instant.now().plusSeconds(2);
        var url = create(expiry);
        get("/" + url.getShortCode());
        awaitClicks(url.getId(), 1);
        List<ShortUrl> batch = new ArrayList<>();
        batch.add(url);
        for (int i = 0; i < 4; i++) {
            batch.add(create(expiry));
        }
        clearInvocations(urls);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            for (var item : batch) {
                assertThat(urls.findById(item.getId()).orElseThrow().isActive()).isFalse();
            }
        });
        assertThat(jdbc.queryForObject("SELECT user_id FROM short_urls WHERE id = ?", Long.class, url.getId())).isEqualTo(owner);
        assertThat(clicks.countByShortUrlId(url.getId())).isEqualTo(1);
        assertThat(redis.hasKey(key(url))).isFalse();
        verify(urls, org.mockito.Mockito.atLeast(3)).lockExpiredBatch(any(), org.mockito.ArgumentMatchers.eq(2));
        assertThat(get("/" + url.getShortCode()).statusCode()).isEqualTo(410);
    }

    private ShortUrl create(Instant expires) {
        var url = urls.saveAndFlush(new ShortUrl("p4-" + UUID.randomUUID(), "https://example.com/destination", expires,
                true, users.findById(owner).orElseThrow()));
        ids.add(url.getId());
        return url;
    }

    private String register() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        var response = send("POST", "/api/auth/register", mapper.writeValueAsString(Map.of("email", email,
                "password", "test-password-123")), null);
        assertThat(response.statusCode()).isEqualTo(201);
        ownerIds.add(users.findByEmail(email).orElseThrow().getId());
        return mapper.readTree(response.body()).path("accessToken").asText();
    }

    private String key(ShortUrl url) { return prefix + "url:{" + url.getShortCode() + "}:data"; }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }

    private HttpResponse<String> send(String method, String path, String body, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (bearer != null) { request.header("Authorization", "Bearer " + bearer); }
        if (body != null) { request.header("Content-Type", "application/json"); }
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void awaitClicks(Long id, long count) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(clicks.countByShortUrlId(id)).isEqualTo(count));
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
