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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortify.entity.ShortUrl;
import com.shortify.repository.ShortUrlRepository;
import com.shortify.service.ShortCodeGenerator;
import com.shortify.service.ShortUrlWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shortify.base-url=https://sho.rt")
class ShortUrlApiIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ShortUrlRepository repository;

    @Autowired
    private DataSource dataSource;

    @MockitoSpyBean
    private ShortCodeGenerator generator;

    @MockitoSpyBean
    private ShortUrlWriter writer;

    private final Set<Long> createdIds = ConcurrentHashMap.newKeySet();
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();

    @AfterEach
    void cleanUpCommittedHttpRows() {
        reset(generator, writer);
        repository.deleteAllById(createdIds);
    }

    @Test
    void createsRetrievesAndRedirectsUsingRealPostgreSql() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        String original = "https://example.com/a%20b?key=value&next=%2Ftest#section";
        HttpResponse<String> created = create(original, null);
        JsonNode body = mapper.readTree(created.body());
        long id = body.path("id").asLong();
        String code = body.path("shortCode").asText();
        assertThat(id).isPositive();
        assertThat(code).matches("[0-9A-Za-z]{8}");
        assertThat(created.headers().firstValue("Location")).contains("/api/urls/" + id);
        assertThat(body.path("shortUrl").asText()).isEqualTo("https://sho.rt/" + code);
        assertThat(body.path("originalUrl").asText()).isEqualTo(original);
        assertThat(body.path("active").asBoolean()).isTrue();
        assertThat(body.path("expiresAt").isNull()).isTrue();
        assertThat(Instant.parse(body.path("createdAt").asText())).isBefore(Instant.now().plusSeconds(1));
        assertThat(body.size()).isEqualTo(7);
        ShortUrl persisted = repository.findById(id).orElseThrow();
        assertThat(persisted.getShortCode()).isEqualTo(code);
        assertThat(persisted.getOriginalUrl()).isEqualTo(original);

        HttpResponse<String> fetched = request("GET", "/api/urls/" + id, null);
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(fetched.body())).isEqualTo(body);
        assertRedirect(code, original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:1234/test", "HTTPS://EXAMPLE.COM/path", "https://[::1]:8443/a"})
    void acceptsHttpSchemesHostsAndPortsWithoutFetchingDestination(String original) throws Exception {
        JsonNode body = mapper.readTree(create(original, null).body());
        assertRedirect(body.path("shortCode").asText(), original);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "example.com", "//example.com", "ftp://example.com", "javascript:alert(1)", "https://",
            "https:///missing-host", "https://bad_host.com", "https://exa mple.com", "https://example.com/%zz",
            "https://user:secret@example.com", "https://user@example.com", "https://@example.com",
            "https://example.com/\r\nInjected:yes", "https://example.com/\t", "https://example.com/\u0000",
            "https://example.com/%0D%0AInjected:yes", "https://example.com/%00", "https://example.com/%7f",
            "https://example.com/%C2%85", "https://example.com/?q=%C2%9F", "https://example.com/#%0D",
            "https://example.com:65536", "https://example.com:-1", "https://example.com:", "https://example.com\\evil"
    })
    void rejectsInvalidUrlsWithJsonErrors(String original) throws Exception {
        assertError(request("POST", "/api/urls", mapper.writeValueAsString(Map.of("originalUrl", original))),
                400, "INVALID_URL");
        verify(writer, times(0)).insert(anyString(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "{", "null", "[]", "\"https://example.com\"", "{}", "{\"originalUrl\":null}",
            "{\"originalUrl\":\"\"}", "{\"originalUrl\":\"   \"}", "{\"originalUrl\":123}",
            "{\"originalUrl\":true}", "{\"originalUrl\":{}}", "{\"originalUrl\":[]}",
            "{\"originalUrl\":\"https://example.com\",\"customAlias\":\"mine\"}",
            "{\"originalUrl\":\"https://example.com\",\"active\":false}",
            "{\"originalUrl\":\"https://example.com\"} {}",
            "{\"originalUrl\":\"https://example.com\",\"expiresAt\":123}",
            "{\"originalUrl\":\"https://example.com\",\"expiresAt\":{}}",
            "{\"originalUrl\":\"https://example.com\",\"expiresAt\":\"tomorrow\"}",
            "{\"originalUrl\":\"https://example.com\",\"expiresAt\":\"2026-09-26T12:00:00\"}",
            "{\"originalUrl\":\"https://example.com\",\"expiresAt\":\"\"}"
    })
    void rejectsMalformedBodiesAndUnsupportedFields(String body) throws Exception {
        assertError(request("POST", "/api/urls", body), 400, "INVALID_BODY");
        verify(writer, times(0)).insert(anyString(), anyString(), any());
    }

    @Test
    void acceptsExplicitNullExpiration() throws Exception {
        HttpResponse<String> response = request("POST", "/api/urls",
                "{\"originalUrl\":\"https://example.com\",\"expiresAt\":null}");
        assertCreatedAndTrack(response);
        assertThat(mapper.readTree(response.body()).path("expiresAt").isNull()).isTrue();
    }

    @Test
    void returnsNotFoundForMissingCodeAndManagementId() throws Exception {
        assertError(request("GET", "/missing-" + UUID.randomUUID(), null), 404, "URL_NOT_FOUND");
        assertError(request("GET", "/api/urls/9223372036854775807", null), 404, "URL_NOT_FOUND");
        assertError(request("DELETE", "/api/urls/9223372036854775807", null), 404, "URL_NOT_FOUND");
    }

    @Test
    void returnsConsistentErrorsForMalformedIdsUnknownRoutesAndUnsupportedRequests() throws Exception {
        assertError(request("GET", "/api/urls/not-an-id", null), 400, "INVALID_PARAMETER");
        assertError(request("DELETE", "/api/urls/9223372036854775808", null), 400, "INVALID_PARAMETER");
        assertError(request("GET", "/no/such/route", null), 404, "NOT_FOUND");
        assertError(request("PUT", "/api/urls", "{}"), 405, "METHOD_NOT_ALLOWED");
        HttpRequest request = HttpRequest.newBuilder(uri("/api/urls")).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("bad body")).build();
        assertError(client.send(request, HttpResponse.BodyHandlers.ofString()), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void pastExpirationIsGoneButStillReadableForManagement() throws Exception {
        String expiresAt = "2000-01-01T00:00:00Z";
        JsonNode body = mapper.readTree(create("https://example.com/expired", expiresAt).body());
        assertThat(body.path("expiresAt").asText()).isEqualTo(expiresAt);
        assertError(request("GET", "/" + body.path("shortCode").asText(), null), 410, "URL_EXPIRED");
        assertThat(request("GET", "/api/urls/" + body.path("id").asLong(), null).statusCode()).isEqualTo(200);
    }

    @Test
    void futureExpirationRedirects() throws Exception {
        JsonNode body = mapper.readTree(create("https://example.com/future", "2099-01-01T00:00:00Z").body());
        assertRedirect(body.path("shortCode").asText(), "https://example.com/future");
    }

    @Test
    void timestampPrecisionIsConsistentAcrossCreationAndRetrieval() throws Exception {
        JsonNode created = mapper.readTree(create("https://example.com/precision", "2099-01-01T00:00:00.123456789Z").body());
        assertThat(created.path("expiresAt").asText()).isEqualTo("2099-01-01T00:00:00.123456Z");
        HttpResponse<String> fetched = request("GET", "/api/urls/" + created.path("id").asLong(), null);
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(fetched.body())).isEqualTo(created);
    }

    @Test
    void deleteDeactivatesPersistentlyAndIsIdempotent() throws Exception {
        JsonNode body = mapper.readTree(create("https://example.com/delete", null).body());
        long id = body.path("id").asLong();
        String code = body.path("shortCode").asText();
        assertRedirect(code, "https://example.com/delete");
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> deleted = request("DELETE", "/api/urls/" + id, null);
            assertThat(deleted.statusCode()).isEqualTo(204);
            assertThat(deleted.body()).isEmpty();
        }
        assertThat(repository.findById(id).orElseThrow().isActive()).isFalse();
        HttpResponse<String> fetched = request("GET", "/api/urls/" + id, null);
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(fetched.body()).path("active").asBoolean()).isFalse();
        assertError(request("GET", "/" + code, null), 410, "URL_INACTIVE");
    }

    @Test
    void concurrentHttpCreatesAreUniqueEvenForSameOriginalUrl() throws Exception {
        Set<String> codes = ConcurrentHashMap.newKeySet();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                futures.add(executor.submit(() -> mapper.readTree(create("https://example.com/shared", null).body())
                        .path("shortCode").asText()));
            }
            for (Future<String> future : futures) {
                codes.add(future.get());
            }
        }
        assertThat(codes).hasSize(40).allSatisfy(code -> assertThat(code).matches("[0-9A-Za-z]{8}"));
        assertThat(repository.findAllById(createdIds)).hasSize(40);
    }

    @Test
    void realUniqueConstraintCollisionRollsBackBeforeRetryAndPreservesExistingUrl() throws Exception {
        String occupied = new ShortCodeGenerator().generate();
        String available = new ShortCodeGenerator().generate();
        ShortUrl existing = repository.saveAndFlush(new ShortUrl(occupied, "https://example.com/existing", null, true));
        createdIds.add(existing.getId());
        doReturn(occupied, available).when(generator).generate();

        JsonNode created = mapper.readTree(create("https://example.com/retried", null).body());
        assertThat(created.path("shortCode").asText()).isEqualTo(available);
        verify(generator, times(2)).generate();
        assertThat(repository.findByShortCode(occupied).orElseThrow().getOriginalUrl()).isEqualTo("https://example.com/existing");
        assertRedirect(available, "https://example.com/retried");
    }

    @Test
    void exhaustedRealCollisionsReturnSanitized503() throws Exception {
        String occupied = new ShortCodeGenerator().generate();
        ShortUrl existing = repository.saveAndFlush(new ShortUrl(occupied, "https://example.com/existing", null, false));
        createdIds.add(existing.getId());
        doReturn(occupied).when(generator).generate();
        assertError(request("POST", "/api/urls", "{\"originalUrl\":\"https://example.com/new\"}"),
                503, "CODE_GENERATION_UNAVAILABLE");
        verify(generator, times(10)).generate();
        assertThat(repository.findById(existing.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void unexpectedDatabaseFailureIsSanitizedAndNotRetried() throws Exception {
        doThrow(new DataIntegrityViolationException("secret SQL INSERT INTO short_urls password=hidden"))
                .when(writer).insert(anyString(), anyString(), any());
        HttpResponse<String> response = request("POST", "/api/urls", "{\"originalUrl\":\"https://example.com\"}");
        assertError(response, 500, "INTERNAL_ERROR");
        assertThat(response.body()).doesNotContain("secret", "SQL", "password", "hidden");
        verify(generator, times(1)).generate();
    }

    private HttpResponse<String> create(String original, String expiresAt) throws Exception {
        Map<String, String> body = expiresAt == null ? Map.of("originalUrl", original)
                : Map.of("originalUrl", original, "expiresAt", expiresAt);
        HttpResponse<String> response = request("POST", "/api/urls", mapper.writeValueAsString(body));
        assertCreatedAndTrack(response);
        return response;
    }

    private void assertCreatedAndTrack(HttpResponse<String> response) throws Exception {
        if (response.statusCode() == 201) {
            createdIds.add(mapper.readTree(response.body()).path("id").asLong());
        }
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private void assertRedirect(String code, String original) throws Exception {
        HttpResponse<String> redirect = request("GET", "/" + code, null);
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).contains(original);
        assertThat(redirect.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(redirect.body()).isEmpty();
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(Instant.parse(body.path("timestamp").asText())).isBefore(Instant.now().plusSeconds(1));
        assertThat(body.size()).isEqualTo(4);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.body()).doesNotContain("org.hibernate", "SQLException", "uk_short_urls", "stackTrace");
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
