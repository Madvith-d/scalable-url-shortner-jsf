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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import com.shortify.entity.ShortUrl;
import com.shortify.repository.ShortUrlRepository;
import com.shortify.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shortify.cors.allowed-origin=http://localhost:3000")
class AuthOwnershipApiIT {

    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private UserRepository users;
    @Autowired
    private ShortUrlRepository urls;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private JwtEncoder encoder;
    @Value("${shortify.jwt.issuer}")
    private String issuer;
    @Value("${shortify.jwt.expires-in}")
    private long expiresIn;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final Set<Long> userIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> urlIds = ConcurrentHashMap.newKeySet();
    private Account alice;
    private Account bob;
    private static final String PASSWORD = "correct-password-123";

    private record Account(String email, String token, long id) {
    }

    @BeforeEach
    void accounts() throws Exception {
        alice = register("Alice-" + UUID.randomUUID() + "@Example.COM");
        bob = register("bob-" + UUID.randomUUID() + "@example.com");
    }

    @AfterEach
    void cleanup() {
        urls.deleteAllById(urlIds);
        users.deleteAllById(userIds);
    }

    @Test
    void registerNormalizesEmailHashesPasswordAndLoginReturnsStandardJwt() throws Exception {
        var stored = users.findById(alice.id()).orElseThrow();
        assertThat(stored.getEmail()).isEqualTo(alice.email()).isLowerCase();
        assertThat(stored.getPasswordHash()).startsWith("$2a$").hasSize(60).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, stored.getPasswordHash())).isTrue();
        assertThat(passwords.matches("wrong-password", stored.getPasswordHash())).isFalse();
        assertThat(stored.getPasswordHash()).isNotEqualTo(users.findById(bob.id()).orElseThrow().getPasswordHash());
        assertThat(stored.getCreatedAt()).isBefore(Instant.now().plusSeconds(1));
        var response = send("POST", "/api/auth/login", Map.of("email", "  " + alice.email().toUpperCase() + "  ",
                "password", PASSWORD), null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.path("email").asText()).isEqualTo(alice.email());
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asLong()).isEqualTo(expiresIn);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        SignedJWT jwt = SignedJWT.parse(body.path("accessToken").asText());
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(Long.toString(alice.id()));
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo(issuer);
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond()
                - jwt.getJWTClaimsSet().getIssueTime().toInstant().getEpochSecond()).isEqualTo(expiresIn);
        assertThat(jwt.getJWTClaimsSet().getClaims()).doesNotContainKeys("password", "passwordHash");
        assertThat(send("GET", "/api/urls", null, body.path("accessToken").asText()).statusCode()).isEqualTo(200);
        create(new Account(alice.email(), body.path("accessToken").asText(), alice.id()), null, null);
        error(send("POST", "/api/auth/register", Map.of("email", " " + alice.email().toUpperCase() + " ",
                "password", PASSWORD), null), 409, "EMAIL_IN_USE");
    }

    @Test
    void wrongPasswordAndUnknownAccountHaveSameError() throws Exception {
        error(send("POST", "/api/auth/login", Map.of("email", alice.email(), "password", "wrong-password"), null),
                401, "INVALID_CREDENTIALS");
        error(send("POST", "/api/auth/login", Map.of("email", UUID.randomUUID() + "@example.com", "password", PASSWORD), null),
                401, "INVALID_CREDENTIALS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{", "{\"email\":\"bad\",\"password\":\"12345678\"}",
            "{\"email\":\"test@example.com\",\"password\":\"short\"}",
            "{\"email\":\"test@example.com\",\"password\":12345678}",
            "{\"email\":\"test@example.com\",\"password\":\"12345678\",\"id\":1}"})
    void rejectsInvalidAuthBodies(String json) throws Exception {
        for (String action : List.of("register", "login")) {
            error(raw("POST", "/api/auth/" + action, json, null), 400, "INVALID_BODY");
        }
    }

    @Test
    void rejectsBcryptByteTruncationAndAcceptsBoundary() throws Exception {
        for (String password : List.of("a".repeat(73), "é".repeat(37))) {
            error(send("POST", "/api/auth/register", Map.of("email", UUID.randomUUID() + "@example.com",
                    "password", password), null), 400, "INVALID_BODY");
        }
        String email = UUID.randomUUID() + "@example.com";
        var response = send("POST", "/api/auth/register", Map.of("email", email, "password", "é".repeat(36)), null);
        assertThat(response.statusCode()).isEqualTo(201);
        userIds.add(users.findByEmail(email).orElseThrow().getId());
        assertThat(send("POST", "/api/auth/login", Map.of("email", email, "password", "é".repeat(36)), null)
                .statusCode()).isEqualTo(200);
    }

    @Test
    void allManagementRoutesRequireBearerIncludingFutureAnalytics() throws Exception {
        JsonNode url = create(alice, null, null);
        String path = "/api/urls/" + url.path("id").asLong();
        error(send("POST", "/api/urls", Map.of("originalUrl", "https://example.com"), null), 401, "UNAUTHORIZED");
        for (String target : List.of("/api/urls", path, path + "/analytics")) {
            error(send("GET", target, null, null), 401, "UNAUTHORIZED");
        }
        error(send("DELETE", path, null, null), 401, "UNAUTHORIZED");
        error(send("PATCH", path, Map.of("active", false), null), 401, "UNAUTHORIZED");
        error(send("GET", "/api/urls?access_token=" + alice.token(), null, null), 401, "UNAUTHORIZED");
        assertThat(send("GET", "/" + url.path("shortCode").asText(), null, null).statusCode()).isEqualTo(302);
    }

    @Test
    void ownershipConcealsGetDeletePatchAndListsOnlyOwnRecords() throws Exception {
        JsonNode a = create(alice, null, null);
        JsonNode b = create(bob, null, null);
        for (var pair : List.of(Map.entry(a, bob), Map.entry(b, alice))) {
            String path = "/api/urls/" + pair.getKey().path("id").asLong();
            error(send("GET", path, null, pair.getValue().token()), 404, "URL_NOT_FOUND");
            error(send("DELETE", path, null, pair.getValue().token()), 404, "URL_NOT_FOUND");
            error(send("PATCH", path, Map.of("active", false), pair.getValue().token()), 404, "URL_NOT_FOUND");
            error(send("GET", path + "/analytics", null, pair.getValue().token()), 404, "NOT_FOUND");
            assertThat(urls.findById(pair.getKey().path("id").asLong()).orElseThrow().isActive()).isTrue();
        }
        assertList(alice, List.of(a.path("id").asLong()));
        assertList(bob, List.of(b.path("id").asLong()));
        assertThat(urls.findById(a.path("id").asLong()).orElseThrow().getUser().getId()).isEqualTo(alice.id());
        error(send("POST", "/api/urls", Map.of("originalUrl", "https://example.com", "userId", bob.id()), alice.token()),
                400, "INVALID_BODY");
        error(send("PATCH", "/api/urls/" + a.path("id").asLong(), Map.of("active", true, "userId", bob.id()), alice.token()),
                400, "INVALID_BODY");
    }

    @Test
    void paginationIsStableBoundedAndIncludesInactiveAndExpiredUrls() throws Exception {
        JsonNode a = create(alice, null, "2000-01-01T00:00:00Z");
        JsonNode b = create(alice, null, null);
        send("DELETE", "/api/urls/" + b.path("id").asLong(), null, alice.token());
        create(bob, null, null);
        var first = mapper.readTree(send("GET", "/api/urls?page=0&size=1", null, alice.token()).body());
        assertThat(first.path("content").size()).isEqualTo(1);
        assertThat(first.path("content").get(0).path("id").asLong()).isEqualTo(b.path("id").asLong());
        assertThat(first.path("totalElements").asLong()).isEqualTo(2);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.path("page").asInt()).isZero();
        assertThat(first.path("size").asInt()).isEqualTo(1);
        var second = mapper.readTree(send("GET", "/api/urls?page=1&size=1", null, alice.token()).body());
        assertThat(second.path("content").get(0).path("id").asLong()).isEqualTo(a.path("id").asLong());
        assertThat(mapper.readTree(send("GET", "/api/urls?page=2&size=1", null, alice.token()).body())
                .path("content").isEmpty()).isTrue();
        for (String query : List.of("page=-1", "page=1000001", "size=0", "size=101", "size=foo", "page=2147483648")) {
            error(send("GET", "/api/urls?" + query, null, alice.token()), 400, "INVALID_PARAMETER");
        }
    }

    @Test
    void patchReactivatesButNeverBypassesExpiration() throws Exception {
        JsonNode url = create(alice, null, null);
        String path = "/api/urls/" + url.path("id").asLong();
        for (boolean active : List.of(false, true)) {
            var response = send("PATCH", path, Map.of("active", active), alice.token());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(response.body()).path("active").asBoolean()).isEqualTo(active);
            assertThat(urls.findById(url.path("id").asLong()).orElseThrow().isActive()).isEqualTo(active);
            var redirect = send("GET", "/" + url.path("shortCode").asText(), null, null);
            if (active) {
                assertThat(redirect.statusCode()).isEqualTo(302);
            } else {
                error(redirect, 410, "URL_INACTIVE");
            }
        }
        JsonNode expired = create(alice, null, "2000-01-01T00:00:00Z");
        assertThat(send("PATCH", "/api/urls/" + expired.path("id").asLong(), Map.of("active", true), alice.token())
                .statusCode()).isEqualTo(200);
        error(send("GET", "/" + expired.path("shortCode").asText(), null, null), 410, "URL_EXPIRED");
        error(send("PATCH", "/api/urls/9223372036854775807", Map.of("active", true), alice.token()), 404, "URL_NOT_FOUND");
        for (String body : List.of("{}", "{\"active\":null}", "{\"active\":\"true\"}", "{\"active\":1}",
                "{\"active\":true,\"customAlias\":\"another\"}")) {
            error(raw("PATCH", path, body, alice.token()), 400, "INVALID_BODY");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "expired", "tampered", "wrong-issuer", "future-nbf", "no-exp", "bad-sub", "unknown-user", "unsigned"})
    void rejectsInvalidTokensThroughRealResourceServer(String kind) throws Exception {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(kind.equals("wrong-issuer") ? "other-issuer" : issuer)
                .subject(kind.equals("bad-sub") ? "not-an-id" : kind.equals("unknown-user") ? "9223372036854775807" : Long.toString(alice.id()))
                .issuedAt(now.minusSeconds(120)).notBefore(kind.equals("future-nbf") ? now.plusSeconds(120) : now.minusSeconds(120));
        if (!kind.equals("no-exp")) {
            claims.expiresAt(kind.equals("expired") ? now.minusSeconds(1) : now.plusSeconds(120));
        }
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
        if (kind.equals("malformed")) {
            token = "not.a.jwt";
        } else if (kind.equals("tampered")) {
            int start = token.lastIndexOf('.') + 1;
            token = token.substring(0, start) + (token.charAt(start) == 'A' ? 'B' : 'A') + token.substring(start + 1);
        } else if (kind.equals("unsigned")) {
            token = "eyJhbGciOiJub25lIn0." + token.split("\\.")[1] + ".";
        }
        error(send("GET", "/api/urls", null, token), 401, "UNAUTHORIZED");
        error(send("POST", "/api/urls", Map.of("originalUrl", "https://example.com"), token), 401, "UNAUTHORIZED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "API", "Auth", "LOGIN", "register", "Logout", "dashboard", "Analytics", "actuator",
            "error", "_NeXt", "admin", "health", "metrics", "static", "assets", "favicon", "robots", "sitemap", "swagger-ui",
            "", "ab", "a" + "1234567890123456789012345678901234", "a.b", "a/b", "a b", "éab", " abc", "abc ", "Ａbc"})
    void rejectsReservedAndInvalidAliases(String alias) throws Exception {
        error(send("POST", "/api/urls", Map.of("originalUrl", "https://example.com", "customAlias", alias), alice.token()),
                400, "INVALID_ALIAS");
    }

    @Test
    void aliasesAreCaseSensitiveAndAcceptBoundaries() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        for (String alias : List.of("A_" + suffix, "a_" + suffix, "-" + suffix + "_".repeat(11), suffix.substring(0, 3))) {
            JsonNode body = create(alice, alias, null);
            assertThat(body.path("shortCode").asText()).isEqualTo(alias);
            assertThat(send("GET", "/" + alias, null, null).statusCode()).isEqualTo(302);
        }
    }

    @Test
    void duplicateAliasConcurrentAcrossOwnersHasOneWinnerAndNoOverwrite() throws Exception {
        String alias = "race-" + UUID.randomUUID().toString().substring(0, 20);
        List<HttpResponse<String>> responses = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                Account account = i % 2 == 0 ? alice : bob;
                futures.add(executor.submit(() -> send("POST", "/api/urls", Map.of("originalUrl",
                        "https://example.com/" + account.id(), "customAlias", alias), account.token())));
            }
            for (var future : futures) {
                var response = future.get();
                if (response.statusCode() == 201) {
                    urlIds.add(mapper.readTree(response.body()).path("id").asLong());
                }
                responses.add(response);
            }
        }
        assertThat(responses.stream().filter(response -> response.statusCode() == 201).count()).isEqualTo(1);
        for (var response : responses) {
            if (response.statusCode() != 201) {
                error(response, 409, "ALIAS_IN_USE");
            }
        }
        var winner = urls.findByShortCode(alias).orElseThrow();
        assertThat(winner.getOriginalUrl()).isEqualTo("https://example.com/" + winner.getUser().getId());
        Account owner = winner.getUser().getId() == alice.id() ? alice : bob;
        send("DELETE", "/api/urls/" + winner.getId(), null, owner.token());
        error(send("POST", "/api/urls", Map.of("originalUrl", "https://example.com/overwrite", "customAlias", alias), bob.token()),
                409, "ALIAS_IN_USE");
        assertThat(urls.findById(winner.getId()).orElseThrow().getOriginalUrl()).isEqualTo(winner.getOriginalUrl());
    }

    @Test
    void concurrentNormalizedRegistrationsHaveOneWinner() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        List<HttpResponse<String>> responses = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String value = i % 2 == 0 ? email : " " + email.toUpperCase() + " ";
                futures.add(executor.submit(() -> send("POST", "/api/auth/register", Map.of("email", value, "password", PASSWORD), null)));
            }
            for (var future : futures) {
                responses.add(future.get());
            }
        } finally {
            users.findByEmail(email).ifPresent(user -> userIds.add(user.getId()));
        }
        assertThat(responses.stream().filter(response -> response.statusCode() == 201).count()).isEqualTo(1);
        for (var response : responses) {
            if (response.statusCode() != 201) {
                error(response, 409, "EMAIL_IN_USE");
            }
        }
    }

    @Test
    void legacyUnownedUrlsStayPublicButCannotBeManagedOrClaimed() throws Exception {
        String alias = "legacy-" + UUID.randomUUID().toString().substring(0, 20);
        var legacy = urls.saveAndFlush(new ShortUrl(alias, "https://example.com/legacy", null, true));
        urlIds.add(legacy.getId());
        assertThat(legacy.getUser()).isNull();
        assertThat(send("GET", "/" + alias, null, null).headers().firstValue("Location")).contains("https://example.com/legacy");
        for (Account account : List.of(alice, bob)) {
            String path = "/api/urls/" + legacy.getId();
            error(send("GET", path, null, account.token()), 404, "URL_NOT_FOUND");
            error(send("PATCH", path, Map.of("active", false), account.token()), 404, "URL_NOT_FOUND");
            error(send("DELETE", path, null, account.token()), 404, "URL_NOT_FOUND");
            assertList(account, List.of());
            error(send("POST", "/api/urls", Map.of("originalUrl", "https://example.com/new", "customAlias", alias), account.token()),
                    409, "ALIAS_IN_USE");
        }
        assertThat(send("GET", "/" + alias, null, null).statusCode()).isEqualTo(302);
    }

    @Test
    void corsAllowsExactOriginAndBearerPreflightWithoutCredentials() throws Exception {
        var allowed = client.send(HttpRequest.newBuilder(uri("/api/urls"))
                .header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        for (String origin : List.of("http://localhost:3001", "https://evil.example", "http://localhost:3000.evil.example", "null")) {
            var rejected = client.send(HttpRequest.newBuilder(uri("/api/urls")).header("Origin", origin)
                    .header("Authorization", "Bearer " + alice.token()).GET().build(), HttpResponse.BodyHandlers.ofString());
            error(rejected, 403, "FORBIDDEN");
            assertThat(rejected.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        }
    }

    private Account register(String email) throws Exception {
        var response = send("POST", "/api/auth/register", Map.of("email", " " + email + " ", "password", PASSWORD), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = mapper.readTree(response.body());
        String normalized = body.path("email").asText();
        long id = users.findByEmail(normalized).orElseThrow().getId();
        userIds.add(id);
        assertThat(body.size()).isEqualTo(4);
        return new Account(normalized, body.path("accessToken").asText(), id);
    }

    private JsonNode create(Account account, String alias, String expiresAt) throws Exception {
        var body = mapper.createObjectNode().put("originalUrl", "https://example.com/" + account.id());
        if (alias != null) {
            body.put("customAlias", alias);
        }
        if (expiresAt != null) {
            body.put("expiresAt", expiresAt);
        }
        var response = send("POST", "/api/urls", body, account.token());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode result = mapper.readTree(response.body());
        urlIds.add(result.path("id").asLong());
        return result;
    }

    private void assertList(Account account, List<Long> expected) throws Exception {
        var response = send("GET", "/api/urls", null, account.token());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.body());
        List<Long> ids = new ArrayList<>();
        body.path("content").forEach(url -> ids.add(url.path("id").asLong()));
        assertThat(ids).containsExactlyElementsOf(expected);
        assertThat(body.path("totalElements").asLong()).isEqualTo(expected.size());
    }

    private void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(Instant.parse(body.path("timestamp").asText())).isBefore(Instant.now().plusSeconds(1));
        assertThat(response.body()).doesNotContain("passwordHash", "SQLException", "org.hibernate", "stackTrace");
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    private HttpResponse<String> send(String method, String path, Object body, String token) throws Exception {
        return raw(method, path, body == null ? null : mapper.writeValueAsString(body), token);
    }

    private HttpResponse<String> raw(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
