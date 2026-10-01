package com.shortify.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortify.config.GeoLocationProperties;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded external lookup: paid ip-api when keyed, otherwise optional keyless HTTPS fallback. */
@Component
public class PaidGeoProvider {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaidGeoProvider.class);

    static final int TIMEOUT_MILLIS = 1500;
    static final int MAX_BODY_BYTES = 8192;
    static final long BASE_BACKOFF_SECONDS = 60;
    private static final long MAX_BACKOFF_SECONDS = 900;
    private static final long MAX_RATE_LIMIT_SECONDS = 86_400;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String apiKey;
    private final boolean freeFallback;
    private final Transport transport;
    private final LongSupplier ticker;
    private final int timeoutMillis;
    private final ThreadPoolExecutor executor;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile long resumeAt;
    private volatile boolean backingOff;
    private volatile boolean closed;

    @Autowired
    public PaidGeoProvider(GeoLocationProperties properties) {
        this(properties.enabled() ? properties.apiKey() : "", new ApacheTransport(),
                System::nanoTime, TIMEOUT_MILLIS, properties.enabled() && properties.freeFallbackEnabled());
        if (properties.enabled() && freeFallback) {
            log.info("Keyless HTTPS geolocation fallback enabled; public visitor IPs may be sent to ipwho.is. Disable with GEO_FREE_FALLBACK_ENABLED=false.");
        } else if (properties.enabled() && apiKey.isEmpty() && properties.databasePath().isBlank()) {
            log.warn("Geolocation has no lookup source; configure a city database or external fallback. Public locations will be Unknown.");
        }
    }

    PaidGeoProvider(String apiKey, Transport transport, LongSupplier ticker, int timeoutMillis) {
        this(apiKey, transport, ticker, timeoutMillis, false);
    }

    PaidGeoProvider(String apiKey, Transport transport, LongSupplier ticker, int timeoutMillis, boolean freeFallback) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.freeFallback = freeFallback && this.apiKey.isEmpty();
        this.transport = transport;
        this.ticker = ticker;
        this.timeoutMillis = Math.min(TIMEOUT_MILLIS, Math.max(1, timeoutMillis));
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "geolocation-https");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    GeoLocation lookup(String ip, long started) {
        InetAddress address = IpAddress.parse(ip);
        if (closed || (apiKey.isEmpty() && !freeFallback) || apiKey.length() > 4096 || address == null
                || !IpAddress.isPublic(address) || (backingOff && ticker.getAsLong() - resumeAt < 0)) {
            return GeoLocation.UNKNOWN;
        }
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - started);
        if (remaining <= 0) {
            return GeoLocation.UNKNOWN;
        }
        HttpGet request = new HttpGet(freeFallback ? freeEndpoint(address.getHostAddress())
                : endpoint(address.getHostAddress(), apiKey));
        Future<Response> future;
        try {
            future = executor.submit(() -> transport.execute(request));
        } catch (RejectedExecutionException exception) {
            return GeoLocation.UNKNOWN;
        }
        try {
            remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - started);
            Response response = future.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
            if (response.status() != 200) {
                backoff(response.retrySeconds());
                return GeoLocation.UNKNOWN;
            }
            JsonNode json = JSON.readTree(response.body());
            if (json == null || !json.isObject() || !(freeFallback
                    ? json.path("success").isBoolean() && json.path("success").booleanValue()
                    : "success".equals(text(json, "status")))) {
                backoff(response.retrySeconds());
                return GeoLocation.UNKNOWN;
            }
            GeoLocation location = new GeoLocation(text(json, freeFallback ? "country_code" : "countryCode"), text(json, "city"));
            if (location.countryCode() == null) {
                backoff(response.retrySeconds());
                return GeoLocation.UNKNOWN;
            }
            failures.set(0);
            if (response.rateLimited()) {
                backoff(response.retrySeconds());
            }
            return location;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            backoff(0);
            return GeoLocation.UNKNOWN;
        } catch (ExecutionException | TimeoutException | IOException | RuntimeException exception) {
            backoff(0);
            return GeoLocation.UNKNOWN;
        } finally {
            request.cancel();
            future.cancel(true);
        }
    }

    static URI freeEndpoint(String ip) {
        return URI.create("https://ipwho.is/" + ip + "?fields=success,country_code,city");
    }

    static URI endpoint(String ip, String key) {
        return URI.create("https://pro.ip-api.com/json/" + ip + "?fields=status,countryCode,city&key="
                + URLEncoder.encode(key, StandardCharsets.UTF_8));
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private void backoff(long retrySeconds) {
        int count = failures.updateAndGet(previous -> Math.min(previous + 1, 5));
        long delay = Math.max(Math.min(MAX_BACKOFF_SECONDS, BASE_BACKOFF_SECONDS << (count - 1)),
                Math.min(MAX_RATE_LIMIT_SECONDS, Math.max(0, retrySeconds)));
        // Do not log the request, visitor IP, API key, response body, or exception diagnostics.
        log.warn("External geolocation unavailable or quota exhausted; backing off for {} seconds. Local data is retained when available.", delay);
        resumeAt = ticker.getAsLong() + TimeUnit.SECONDS.toNanos(delay);
        backingOff = true;
    }

    @PreDestroy
    public void close() {
        closed = true;
        executor.shutdownNow();
        try {
            transport.close();
        } catch (IOException exception) {
            // Transport diagnostics may include the request URI and API key.
        }
    }

    interface Transport extends AutoCloseable {
        Response execute(HttpGet request) throws IOException;

        @Override
        void close() throws IOException;
    }

    record Response(int status, byte[] body, boolean rateLimited, long retrySeconds) {
    }

    static final class ApacheTransport implements Transport {

        private final CloseableHttpClient client;

        ApacheTransport() {
            this(HttpClients.custom().disableAutomaticRetries().disableRedirectHandling()
                    .disableCookieManagement().disableContentCompression().disableAuthCaching()
                    .setDefaultRequestConfig(RequestConfig.custom()
                            .setConnectionRequestTimeout(Timeout.ofMilliseconds(TIMEOUT_MILLIS))
                            .setResponseTimeout(Timeout.ofMilliseconds(TIMEOUT_MILLIS)).build())
                    .build());
        }

        ApacheTransport(CloseableHttpClient client) {
            this.client = client;
        }

        @Override
        public Response execute(HttpGet request) throws IOException {
            try (ClassicHttpResponse response = client.executeOpen(null, request, null)) {
                long retry = Math.max(seconds(response.getFirstHeader("Retry-After")),
                        seconds(response.getFirstHeader("X-Ttl")));
                Header remaining = response.getFirstHeader("X-Rl");
                boolean rateLimited = remaining != null && "0".equals(remaining.getValue().strip());
                if (response.getCode() != 200) {
                    request.cancel();
                    return new Response(response.getCode(), new byte[0], rateLimited, retry);
                }
                if (response.getEntity() == null || response.getEntity().getContentLength() > MAX_BODY_BYTES) {
                    request.cancel();
                    throw new IOException("Invalid geolocation response size.");
                }
                byte[] body;
                try (InputStream input = response.getEntity().getContent()) {
                    body = input.readNBytes(MAX_BODY_BYTES + 1);
                    if (body.length > MAX_BODY_BYTES) {
                        request.cancel();
                        throw new IOException("Invalid geolocation response size.");
                    }
                }
                return new Response(response.getCode(), body, rateLimited, retry);
            }
        }

        private static long seconds(Header header) {
            if (header == null || header.getValue() == null) {
                return 0;
            }
            String value = header.getValue().strip();
            if (!value.matches("[0-9]{1,10}")) {
                return 0;
            }
            return Math.min(MAX_RATE_LIMIT_SECONDS, Long.parseLong(value));
        }

        @Override
        public void close() throws IOException {
            client.close();
        }
    }
}
