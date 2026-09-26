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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortify.entity.ClickEvent;
import com.shortify.entity.ShortUrl;
import com.shortify.repository.ClickEventRepository;
import com.shortify.repository.ShortUrlRepository;
import com.shortify.repository.UserRepository;
import com.shortify.service.AnalyticsService;
import com.shortify.service.ClientIpResolver;
import com.shortify.service.GeoLocation;
import com.shortify.service.GeoLookupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"shortify.analytics.workers=1", "shortify.analytics.queue-capacity=1"})
class GeographyApiIT extends RedisIntegrationSupport {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private ShortUrlRepository urls;
    @Autowired private UserRepository users;
    @MockitoSpyBean private ClickEventRepository clicks;
    @MockitoBean private ClientIpResolver clientIps;
    @MockitoBean private GeoLookupService lookup;
    @Autowired private AnalyticsService analytics;
    @Autowired private ThreadPoolTaskExecutor analyticsExecutor;
    @Autowired private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<Long> ownerIds = new ArrayList<>();
    private final List<Long> urlIds = new ArrayList<>();
    private String token;
    private ShortUrl url;

    @BeforeEach
    void setUp() throws Exception {
        token = register();
        url = urls.saveAndFlush(new ShortUrl("geo-" + UUID.randomUUID(), "https://example.com/destination", null,
                true, users.findById(ownerIds.getLast()).orElseThrow()));
        urlIds.add(url.getId());
        when(clientIps.resolve(any())).thenReturn("8.8.8.8");
        when(lookup.lookup("8.8.8.8")).thenReturn(new GeoLocation("US", "Mountain View"));
    }

    @AfterEach
    void cleanup() {
        await().atMost(Duration.ofSeconds(10)).until(() -> analyticsExecutor.getActiveCount() == 0
                && analyticsExecutor.getThreadPoolExecutor().getQueue().isEmpty());
        urls.deleteAllById(urlIds);
        users.deleteAllById(ownerIds);
    }

    @Test
    void aggregatesCountriesAndCountryQualifiedCitiesIncludingHistoricalUnknowns() throws Exception {
        add("US", "Springfield", 3);
        add("CA", "Springfield", 2);
        add("US", null, 1);
        clicks.saveAndFlush(new ClickEvent(url.getId(), Instant.now(), "Direct", "Desktop", "Legacy region"));
        clearInvocations(clicks);
        var response = send("GET", geography(""), token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode country = mapper.readTree(response.body());
        assertThat(country.size()).isEqualTo(2);
        assertThat(country.path("totalClicks").asLong()).isEqualTo(7);
        assertThat(country.path("buckets")).isEqualTo(mapper.valueToTree(List.of(
                Map.of("label", "US", "clicks", 4), Map.of("label", "CA", "clicks", 2),
                Map.of("label", "Unknown", "clicks", 1))));
        JsonNode city = mapper.readTree(send("GET", geography("?by=city"), token).body());
        assertThat(city.path("totalClicks").asLong()).isEqualTo(7);
        assertThat(city.path("buckets")).isEqualTo(mapper.valueToTree(List.of(
                Map.of("label", "Springfield, US", "clicks", 3), Map.of("label", "Springfield, CA", "clicks", 2),
                Map.of("label", "Unknown", "clicks", 2))));
        JsonNode legacy = mapper.readTree(send("GET", "/api/urls/" + url.getId() + "/analytics", token).body());
        assertThat(legacy.size()).isEqualTo(5);
        assertThat(legacy.path("geography")).isEqualTo(country.path("buckets"));
        verify(clicks, never()).findAll();
    }

    @Test
    void topTenUsesStableTiesAndLifetimeDenominatorForBothSelectors() throws Exception {
        List<String> countries = List.of("AU", "BR", "CA", "DE", "ES", "FR", "GB", "IN", "IT", "JP", "NZ", "US");
        for (String country : countries) {
            add(country, "City", country.equals("US") ? 3 : 1);
        }
        for (String by : List.of("country", "city")) {
            JsonNode body = mapper.readTree(send("GET", geography("?by=" + by), token).body());
            assertThat(body.path("totalClicks").asLong()).isEqualTo(14);
            assertThat(body.path("buckets").size()).isEqualTo(10);
            List<String> labels = new ArrayList<>();
            body.path("buckets").forEach(bucket -> labels.add(bucket.path("label").asText()));
            String prefix = by.equals("city") ? "City, " : "";
            List<String> expected = new ArrayList<>();
            expected.add(prefix + "US");
            countries.subList(0, 9).forEach(country -> expected.add(prefix + country));
            assertThat(labels).containsExactlyElementsOf(expected);
            assertThat(body.path("buckets").get(0).path("clicks").asLong()).isEqualTo(3);
        }
        JsonNode legacy = mapper.readTree(send("GET", "/api/urls/" + url.getId() + "/analytics", token).body());
        assertThat(legacy.path("geography").size()).isEqualTo(12);
    }

    @Test
    void emptySelectorAndConcealedOwnershipContracts() throws Exception {
        JsonNode empty = mapper.readTree(send("GET", geography(""), token).body());
        assertThat(empty).isEqualTo(mapper.valueToTree(Map.of("totalClicks", 0, "buckets", List.of())));
        assertThat(send("GET", geography("?by=region"), token).statusCode()).isEqualTo(400);
        assertThat(send("GET", geography("?by=COUNTRY"), token).statusCode()).isEqualTo(400);
        assertThat(send("GET", geography(""), null).statusCode()).isEqualTo(401);
        String other = register();
        assertThat(send("GET", geography("?by=city"), other).statusCode()).isEqualTo(404);
        assertThat(send("GET", geography("?by=invalid"), other).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/urls/9223372036854775807/analytics/geography", token).statusCode()).isEqualTo(404);
    }

    @Test
    void blockedLookupDoesNotDelayRedirectOrHoldTransactionAndPreservesRequestTime() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean transactionDuringLookup = new AtomicBoolean(true);
        when(lookup.lookup("8.8.8.8")).thenAnswer(invocation -> {
            transactionDuringLookup.set(TransactionSynchronizationManager.isActualTransactionActive());
            assertThat(Thread.currentThread().getName()).startsWith("analytics-");
            entered.countDown();
            waitFor(release);
            return new GeoLocation("US", "Mountain View");
        });
        Instant before = Instant.now();
        Instant responded;
        try {
            var response = send("GET", "/" + url.getShortCode(), null);
            responded = Instant.now();
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("Location")).contains(url.getOriginalUrl());
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(transactionDuringLookup).isFalse();
            assertThat(clicks.countByShortUrlId(url.getId())).isZero();
        } finally {
            release.countDown();
        }
        awaitClicks(1);
        var row = jdbc.queryForMap("SELECT * FROM click_events WHERE short_url_id = ?", url.getId());
        assertThat(row).containsEntry("country_code", "US").containsEntry("city", "Mountain View")
                .containsEntry("geography", "Unknown");
        Instant accessedAt = jdbc.queryForObject("SELECT accessed_at FROM click_events WHERE short_url_id = ?",
                (rs, index) -> rs.getTimestamp(1).toInstant(), url.getId());
        assertThat(accessedAt).isBetween(before.minusMillis(1), responded.plusMillis(1));
        assertThat(row.keySet()).containsExactlyInAnyOrder("id", "short_url_id", "accessed_at", "referrer", "device",
                "geography", "country_code", "city");
        assertThat(row.toString()).doesNotContain("8.8.8.8");
    }

    @Test
    void lookupFailureStillSavesUnknownClick() throws Exception {
        when(lookup.lookup("8.8.8.8")).thenThrow(new IllegalStateException("provider unavailable"));
        long dropped = analytics.droppedCount();
        assertThat(send("GET", "/" + url.getShortCode(), null).statusCode()).isEqualTo(302);
        awaitClicks(1);
        assertThat(analytics.droppedCount()).isEqualTo(dropped);
        var row = jdbc.queryForMap("SELECT country_code, city FROM click_events WHERE short_url_id = ?", url.getId());
        assertThat(row).containsEntry("country_code", null).containsEntry("city", null);
        JsonNode body = mapper.readTree(send("GET", geography("?by=city"), token).body());
        assertThat(body.path("buckets")).isEqualTo(mapper.valueToTree(List.of(Map.of("label", "Unknown", "clicks", 1))));
    }

    @Test
    void saturatedWorkerNeverInvokesLookupAndHeadDoesNotResolveIp() throws Exception {
        assertThat(send("HEAD", "/" + url.getShortCode(), null).statusCode()).isEqualTo(302);
        verifyNoInteractions(clientIps, lookup);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        analyticsExecutor.execute(() -> { entered.countDown(); waitFor(release); });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        analyticsExecutor.execute(() -> waitFor(release));
        long dropped = analytics.droppedCount();
        try {
            assertThat(send("GET", "/" + url.getShortCode(), null).statusCode()).isEqualTo(302);
            assertThat(analytics.droppedCount()).isEqualTo(dropped + 1);
            verifyNoInteractions(lookup);
            assertThat(clicks.countByShortUrlId(url.getId())).isZero();
        } finally {
            release.countDown();
        }
    }

    private void add(String country, String city, int count) {
        List<ClickEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(new ClickEvent(url.getId(), Instant.now(), "Direct", "Desktop", country, city));
        }
        clicks.saveAllAndFlush(events);
    }

    private String geography(String query) { return "/api/urls/" + url.getId() + "/analytics/geography" + query; }

    private String register() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/register"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(Map.of("email", email, "password", "test-password-123")))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        ownerIds.add(users.findByEmail(email).orElseThrow().getId());
        return mapper.readTree(response.body()).path("accessToken").asText();
    }

    private HttpResponse<String> send(String method, String path, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(5));
        if (bearer != null) { request.header("Authorization", "Bearer " + bearer); }
        return client.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void awaitClicks(long count) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(clicks.countByShortUrlId(url.getId())).isEqualTo(count));
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
