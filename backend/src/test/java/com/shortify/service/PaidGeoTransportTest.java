package com.shortify.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaidGeoTransportTest {

    @Test
    void readsOnlyBoundedBodyAndExtractsRateLimitHeaders() throws Exception {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
        response.setEntity(new StringEntity("{\"status\":\"success\"}", ContentType.APPLICATION_JSON));
        response.setHeader("X-Rl", "0");
        response.setHeader("X-Ttl", "120");
        response.setHeader("Retry-After", "300");
        try (var transport = transport(response)) {
            var result = transport.execute(request());
            assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("{\"status\":\"success\"}");
            assertThat(result.rateLimited()).isTrue();
            assertThat(result.retrySeconds()).isEqualTo(300);
        }
    }

    @Test
    void rejectsOversizedDeclaredBodyBeforeReading() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
        response.setEntity(new InputStreamEntity(new ByteArrayInputStream(new byte[0]) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                reads.incrementAndGet();
                return -1;
            }
        }, PaidGeoProvider.MAX_BODY_BYTES + 1L, ContentType.APPLICATION_JSON));
        HttpGet request = request();
        try (var transport = transport(response)) {
            assertThatThrownBy(() -> transport.execute(request)).isInstanceOf(IOException.class);
            assertThat(reads).hasValue(0);
            assertThat(request.isCancelled()).isTrue();
        }
    }

    @Test
    void rejectsUnboundedChunkedBodyAfterAtMostOneOverflowByte() throws Exception {
        AtomicInteger bytesRead = new AtomicInteger();
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
        response.setEntity(new InputStreamEntity(new ByteArrayInputStream(new byte[100_000]) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                int read = super.read(bytes, offset, length);
                bytesRead.addAndGet(Math.max(0, read));
                return read;
            }
        }, -1, ContentType.APPLICATION_JSON));
        HttpGet request = request();
        try (var transport = transport(response)) {
            assertThatThrownBy(() -> transport.execute(request)).isInstanceOf(IOException.class);
            assertThat(bytesRead).hasValue(PaidGeoProvider.MAX_BODY_BYTES + 1);
            assertThat(request.isCancelled()).isTrue();
        }
    }

    @Test
    void acceptsExactBodyLimitAndTreatsMalformedRateHeadersSafely() throws Exception {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
        response.setEntity(new InputStreamEntity(new ByteArrayInputStream(new byte[PaidGeoProvider.MAX_BODY_BYTES]),
                -1, ContentType.APPLICATION_JSON));
        response.setHeader("Retry-After", "9999999999999999999999");
        response.setHeader("X-Ttl", "-123");
        try (var transport = transport(response)) {
            var result = transport.execute(request());
            assertThat(result.body()).hasSize(PaidGeoProvider.MAX_BODY_BYTES);
            assertThat(result.retrySeconds()).isZero();
        }
    }

    @Test
    void doesNotReadErrorBodyAndCapsRetryHeader() throws Exception {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(429);
        response.setHeader("Retry-After", "9999999999");
        HttpGet request = request();
        try (var transport = transport(response)) {
            var result = transport.execute(request);
            assertThat(result.status()).isEqualTo(429);
            assertThat(result.retrySeconds()).isEqualTo(86_400);
            assertThat(result.body()).isEmpty();
            assertThat(request.isCancelled()).isTrue();
        }
    }

    @Test
    void realHttpClientDoesNotFollowRedirectsOrRetryServiceErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger redirected = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/error", exchange -> {
            attempts.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try (var transport = new PaidGeoProvider.ApacheTransport()) {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThat(transport.execute(new HttpGet(origin + "/redirect")).status()).isEqualTo(302);
            assertThat(redirected).hasValue(0);
            assertThat(transport.execute(new HttpGet(origin + "/error")).status()).isEqualTo(503);
            assertThat(attempts).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void realHttpClientDoesNotRetryDroppedConnections() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/drop", exchange -> {
            attempts.incrementAndGet();
            exchange.close();
        });
        server.start();
        try (var transport = new PaidGeoProvider.ApacheTransport()) {
            HttpGet request = new HttpGet("http://127.0.0.1:" + server.getAddress().getPort() + "/drop");
            assertThatThrownBy(() -> transport.execute(request)).isInstanceOf(IOException.class);
            assertThat(attempts).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void totalDeadlineIncludesRealChunkedBodyAfterHeadersArrive() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch releaseBody = new CountDownLatch(1);
        CountDownLatch sentHeaders = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            sentHeaders.countDown();
            try {
                releaseBody.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        PaidGeoProvider provider = null;
        try (var apache = new PaidGeoProvider.ApacheTransport()) {
            PaidGeoProvider.Transport routed = mock(PaidGeoProvider.Transport.class);
            when(routed.execute(any())).thenAnswer(invocation -> {
                HttpGet request = invocation.getArgument(0);
                request.setUri(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow"));
                return apache.execute(request);
            });
            provider = new PaidGeoProvider("test-key", routed, System::nanoTime, 300);
            long started = System.nanoTime();
            assertThat(provider.lookup("8.8.8.8", started)).isEqualTo(GeoLocation.UNKNOWN);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(200L, 1000L);
            assertThat(sentHeaders.getCount()).isZero();
        } finally {
            releaseBody.countDown();
            if (provider != null) {
                provider.close();
            }
            server.stop(0);
        }
    }

    private static PaidGeoProvider.ApacheTransport transport(BasicClassicHttpResponse response) throws IOException {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        when(client.executeOpen(isNull(), any(HttpGet.class), isNull())).thenReturn(response);
        return new PaidGeoProvider.ApacheTransport(client);
    }

    private static HttpGet request() {
        return new HttpGet(PaidGeoProvider.endpoint("8.8.8.8", "test-key"));
    }
}
