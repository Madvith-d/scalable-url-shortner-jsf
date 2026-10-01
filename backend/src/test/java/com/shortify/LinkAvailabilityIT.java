package com.shortify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortify.repository.ShortUrlRepository;
import com.shortify.repository.UserRepository;
import com.shortify.service.AnalyticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LinkAvailabilityIT extends RedisIntegrationSupport {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired ShortUrlRepository urls;
    @Autowired UserRepository users;
    // No analytics writes: admission must enforce its cap independently of the analytics queue.
    @MockitoBean AnalyticsService analytics;
    @MockitoBean Clock clock;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    org.springframework.data.redis.core.StringRedisTemplate redis;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<Long> ids = new ArrayList<>();
    private Instant now;
    private String token;
    private Long owner;

    @BeforeEach
    void setup() throws Exception {
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        when(clock.instant()).thenReturn(now);
        String email = UUID.randomUUID() + "@example.test";
        var response = send("POST", "/api/auth/register", mapper.writeValueAsString(Map.of("email", email, "password", "test-password-123")));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        token = mapper.readTree(response.body()).path("accessToken").asText();
        owner = users.findByEmail(email).orElseThrow().getId();
    }

    @AfterEach
    void cleanup() {
        urls.deleteAllById(ids);
        if (owner != null) users.deleteById(owner);
    }

    @Test
    void scheduleStartsAtExactBoundaryEvenWhenTargetIsCachedAndExpirationStillWins() throws Exception {
        var url = create(Map.of("activatesAt", now.plusSeconds(10).toString(), "expiresAt", now.plusSeconds(20).toString(), "maxClicks", 2));
        String path = "/" + url.path("shortCode").asText();
        assertError(send("GET", path, null), 404, "URL_NOT_ACTIVE_YET");
        assertThat(send("HEAD", path, null).statusCode()).isEqualTo(404);
        assertThat(count(url)).isZero();
        when(clock.instant()).thenReturn(now.plusSeconds(10));
        assertThat(send("HEAD", path, null).statusCode()).isEqualTo(302);
        assertThat(count(url)).isZero();
        assertThat(send("GET", path, null).statusCode()).isEqualTo(302);
        assertThat(count(url)).isEqualTo(1);
        when(clock.instant()).thenReturn(now.plusSeconds(20));
        assertError(send("GET", path, null), 410, "URL_EXPIRED");
        assertThat(count(url)).isEqualTo(1);
    }

    @Test
    void concurrentRequestsCannotExceedCapEvenWithWarmRedisAndNoAnalytics() throws Exception {
        var url = create(Map.of("maxClicks", 5));
        String path = "/" + url.path("shortCode").asText();
        assertThat(send("HEAD", path, null).statusCode()).isEqualTo(302); // warm cache, no click
        try (var executor = Executors.newFixedThreadPool(12)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<Integer>>();
            for (int i = 0; i < 30; i++) tasks.add(() -> send("GET", path, null).statusCode());
            var statuses = new ArrayList<Integer>();
            for (var result : executor.invokeAll(tasks)) statuses.add(result.get());
            assertThat(statuses.stream().filter(status -> status == 302).count()).isEqualTo(5);
            assertThat(statuses.stream().filter(status -> status == 410).count()).isEqualTo(25);
        }
        assertThat(count(url)).isEqualTo(5);
        assertError(send("GET", path, null), 410, "URL_CLICK_CAP_REACHED");
        assertThat(send("HEAD", path, null).statusCode()).isEqualTo(410);
        String management = "/api/urls/" + url.path("id").asLong();
        assertThat(send("PATCH", management, "{\"active\":false}").statusCode()).isEqualTo(200);
        assertThat(send("PATCH", management, "{\"active\":true}").statusCode()).isEqualTo(200);
        assertError(send("GET", path, null), 410, "URL_CLICK_CAP_REACHED");
        JsonNode fetched = mapper.readTree(send("GET", management, null).body());
        assertThat(fetched.path("clickCount").asLong()).isEqualTo(5);
        assertThat(fetched.path("maxClicks").asLong()).isEqualTo(5);
    }

    @Test
    void cacheOutageCannotBypassCap() throws Exception {
        var url = create(Map.of("maxClicks", 1));
        String path = "/" + url.path("shortCode").asText();
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("test outage"))
                .when(redis).opsForValue();
        assertThat(send("GET", path, null).statusCode()).isEqualTo(302);
        assertError(send("GET", path, null), 410, "URL_CLICK_CAP_REACHED");
        assertThat(count(url)).isEqualTo(1);
    }

    @Test
    void scheduledActivationDoesNotOverrideManualDeactivation() throws Exception {
        var url = create(Map.of("activatesAt", now.plusSeconds(5).toString(), "maxClicks", 1));
        String path = "/" + url.path("shortCode").asText();
        String management = "/api/urls/" + url.path("id").asLong();
        assertThat(send("PATCH", management, "{\"active\":false}").statusCode()).isEqualTo(200);
        when(clock.instant()).thenReturn(now.plusSeconds(5));
        assertError(send("GET", path, null), 410, "URL_INACTIVE");
        assertThat(count(url)).isZero();
        assertThat(send("PATCH", management, "{\"active\":true}").statusCode()).isEqualTo(200);
        assertThat(send("GET", path, null).statusCode()).isEqualTo(302);
    }

    @Test
    void nullPoliciesRemainUnlimitedAndPastActivationIsImmediate() throws Exception {
        var url = create(Map.of("activatesAt", now.minusSeconds(10).toString()));
        String path = "/" + url.path("shortCode").asText();
        for (int i = 0; i < 3; i++) assertThat(send("GET", path, null).statusCode()).isEqualTo(302);
        assertThat(send("HEAD", path, null).statusCode()).isEqualTo(302);
        assertThat(count(url)).isEqualTo(3);
        assertThat(url.path("maxClicks").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "\"2\"", "true", "{}", "9007199254740992", "9223372036854775808"})
    void rejectsInvalidCaps(String cap) throws Exception {
        assertError(send("POST", "/api/urls", "{\"originalUrl\":\"https://example.com\",\"maxClicks\":" + cap + "}"), 400, "INVALID_BODY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"tomorrow\"", "\"2027-01-01T12:00:00\"", "123", "{}", "\"0000-01-01T00:00:00Z\""})
    void rejectsMalformedActivation(String activation) throws Exception {
        assertError(send("POST", "/api/urls", "{\"originalUrl\":\"https://example.com\",\"activatesAt\":" + activation + "}"), 400, "INVALID_BODY");
    }

    @Test
    void activationMustPrecedeExpiration() throws Exception {
        for (int offset : List.of(0, 1)) {
            var body = Map.of("originalUrl", "https://example.com", "activatesAt", now.plusSeconds(offset).toString(), "expiresAt", now.toString());
            assertError(send("POST", "/api/urls", mapper.writeValueAsString(body)), 400, "INVALID_BODY");
        }
    }

    private long count(JsonNode url) { return urls.findById(url.path("id").asLong()).orElseThrow().getClickCount(); }

    private JsonNode create(Map<String, Object> policy) throws Exception {
        var body = new java.util.HashMap<String, Object>(policy);
        body.put("originalUrl", "https://example.com/destination");
        var response = send("POST", "/api/urls", mapper.writeValueAsString(body));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode url = mapper.readTree(response.body());
        ids.add(url.path("id").asLong());
        return url;
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
}
