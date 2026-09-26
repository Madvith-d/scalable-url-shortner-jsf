package com.shortify.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.shortify.config.GeoLocationProperties;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ExtendWith(OutputCaptureExtension.class)
class PaidGeoProviderTest {

    private final AtomicLong ticker = new AtomicLong();
    private final StubTransport transport = new StubTransport();
    private PaidGeoProvider provider = new PaidGeoProvider("server-secret", transport, ticker::get, 1500);

    @AfterEach
    void close() {
        provider.close();
    }

    @Test
    void usesOnlyFixedHttpsEndpointMinimalFieldsAndEncodedServerKey() {
        assertThat(PaidGeoProvider.endpoint("8.8.8.8", "secret&fields=query #").toString())
                .isEqualTo("https://pro.ip-api.com/json/8.8.8.8?fields=status,countryCode,city&key=secret%26fields%3Dquery+%23");
        assertThat(lookup()).isEqualTo(new GeoLocation("US", "Mountain View"));
        assertThat(transport.request.get().getRequestUri())
                .contains("/json/8.8.8.8?fields=status,countryCode,city&key=server-secret");
        assertThat(transport.calls).hasValue(1);
        assertThat(transport.thread.get()).startsWith("geolocation-https");
    }

    @Test
    void blankKeyDisabledAndPrivateAddressesMakeNoRequests() {
        provider.close();
        provider = new PaidGeoProvider("", transport, ticker::get, 1500);
        assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
        assertThat(transport.calls).hasValue(0);
        provider.close();
        provider = new PaidGeoProvider("server-secret", transport, ticker::get, 1500);
        for (String ip : new String[]{"127.0.0.1", "::ffff:192.168.1.1", "2001:db8::1", "localhost", "8.8.8.8/evil"}) {
            assertThat(provider.lookup(ip, System.nanoTime())).isEqualTo(GeoLocation.UNKNOWN);
        }
        assertThat(transport.calls).hasValue(0);
        PaidGeoProvider disabled = new PaidGeoProvider(new GeoLocationProperties(false, "", "server-secret", ""));
        try {
            assertThat(disabled.lookup("8.8.8.8", System.nanoTime())).isEqualTo(GeoLocation.UNKNOWN);
        } finally {
            disabled.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "null", "[]", "{}", "{\"status\":\"fail\"}",
            "{\"status\":\"success\",\"countryCode\":\"ZZ\",\"city\":\"Paris\"}",
            "{\"status\":\"success\",\"countryCode\":{},\"city\":\"Paris\"}",
            "{\"status\":true,\"countryCode\":\"US\"}",
            "{\"status\":\"success\",\"countryCode\":\"US\"} {}"})
    void invalidResponsesFailClosedAndBackOff(String body) {
        transport.response = response(200, body, false, 0);
        assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
        assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
        assertThat(transport.calls).hasValue(1);
    }

    @Test
    void sanitizesProviderCityAndCountry() {
        transport.response = response(200, "{\"status\":\"success\",\"countryCode\":\"us\",\"city\":\"New\\nYork\"}", false, 0);
        assertThat(lookup()).isEqualTo(new GeoLocation("US", null));
    }

    @Test
    void networkErrorsBackOffExponentiallyWithoutLoggingPrivateDiagnostics(CapturedOutput output) {
        transport.failure = new IOException("8.8.8.8 server-secret https://pro.ip-api.com/json/8.8.8.8");
        assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(59));
        assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
        assertThat(transport.calls).hasValue(1);
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(1));
        await().untilAsserted(() -> {
            assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
            assertThat(transport.calls).hasValue(2);
        });
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(119));
        assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
        assertThat(transport.calls).hasValue(2);
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(1));
        transport.failure = null;
        await().untilAsserted(() -> assertThat(lookup()).isEqualTo(new GeoLocation("US", "Mountain View")));
        assertThat(output.getAll()).doesNotContain("8.8.8.8", "server-secret", "pro.ip-api.com");
    }

    @Test
    void respectsRateLimitDelayAndBoundsUntrustedRetryDurations() {
        transport.response = response(429, "", true, Long.MAX_VALUE);
        assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
        ticker.addAndGet(TimeUnit.HOURS.toNanos(24) - 1);
        assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
        assertThat(transport.calls).hasValue(1);
        ticker.incrementAndGet();
        transport.response = success();
        await().untilAsserted(() -> assertThat(lookup()).isEqualTo(new GeoLocation("US", "Mountain View")));
    }

    @Test
    void successfulLastQuotaResponseIsUsedButFurtherRequestsBackOff() {
        transport.response = response(200, "{\"status\":\"success\",\"countryCode\":\"US\"}", true, 300);
        assertThat(lookup()).isEqualTo(new GeoLocation("US", null));
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(299));
        assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
        assertThat(transport.calls).hasValue(1);
    }

    @Test
    void deadlineCoversSlowBodyAndCancelsRequestWithoutRetries() {
        provider.close();
        transport.release = new CountDownLatch(1);
        provider = new PaidGeoProvider("server-secret", transport, ticker::get, 100);
        long started = System.nanoTime();
        assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMillis).isBetween(50L, 1000L);
        assertThat(transport.request.get().isCancelled()).isTrue();
        assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
        assertThat(transport.calls).hasValue(1);
        transport.release.countDown();
    }

    @Test
    void productionDeadlineDoesNotExceedOneAndAHalfSecondsApartFromSchedulingTolerance() {
        transport.release = new CountDownLatch(1);
        long started = System.nanoTime();
        assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(1400L, 1900L);
        assertThat(transport.request.get().isCancelled()).isTrue();
        transport.release.countDown();
    }

    @Test
    void consumesOnlyRemainingBudgetAndDoesNotStartAfterDeadline() {
        assertThat(provider.lookup("8.8.8.8", System.nanoTime() - TimeUnit.SECONDS.toNanos(2)))
                .isEqualTo(GeoLocation.UNKNOWN);
        assertThat(transport.calls).hasValue(0);
    }

    @Test
    void concurrentRequestsDoNotBuildAnUnboundedQueue() throws Exception {
        transport.release = new CountDownLatch(1);
        transport.entered = new CountDownLatch(1);
        try (var callers = Executors.newSingleThreadExecutor()) {
            var first = callers.submit(this::lookup);
            assertThat(transport.entered.await(1, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
            }
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(500);
            assertThat(transport.calls).hasValue(1);
            transport.release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo(new GeoLocation("US", "Mountain View"));
        } finally {
            transport.release.countDown();
        }
    }

    @Test
    void interruptionPreservesInterruptFlagAndCancelsTransport() {
        transport.release = new CountDownLatch(1);
        Thread.currentThread().interrupt();
        try {
            assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            transport.release.countDown();
        }
    }

    @Test
    void shutdownClosesTransportAndPreventsFurtherRequests() {
        provider.close();
        assertThat(transport.closed).isTrue();
        assertThat(lookup()).isEqualTo(GeoLocation.UNKNOWN);
        assertThat(transport.calls).hasValue(0);
    }

    private GeoLocation lookup() {
        return provider.lookup("8.8.8.8", System.nanoTime());
    }

    private static PaidGeoProvider.Response success() {
        return response(200, "{\"status\":\"success\",\"countryCode\":\"US\",\"city\":\"Mountain View\"}", false, 0);
    }

    private static PaidGeoProvider.Response response(int status, String body, boolean rateLimited, long retry) {
        return new PaidGeoProvider.Response(status, body.getBytes(StandardCharsets.UTF_8), rateLimited, retry);
    }

    private static final class StubTransport implements PaidGeoProvider.Transport {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<HttpGet> request = new AtomicReference<>();
        private final AtomicReference<String> thread = new AtomicReference<>();
        private volatile PaidGeoProvider.Response response = success();
        private volatile IOException failure;
        private volatile CountDownLatch release;
        private volatile CountDownLatch entered;
        private volatile boolean closed;

        @Override
        public PaidGeoProvider.Response execute(HttpGet request) throws IOException {
            calls.incrementAndGet();
            this.request.set(request);
            thread.set(Thread.currentThread().getName());
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
