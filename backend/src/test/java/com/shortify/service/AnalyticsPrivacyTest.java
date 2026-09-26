package com.shortify.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import com.shortify.entity.ClickEvent;
import com.shortify.repository.ClickEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalyticsPrivacyTest {

    @Test
    void queuedTaskRetainsOnlySanitizedMetadataTimestampAndTransientIp() throws Exception {
        var clicks = mock(ClickEventRepository.class);
        var lookup = mock(GeoLookupService.class);
        var executor = mock(ThreadPoolTaskExecutor.class);
        AtomicReference<Runnable> task = new AtomicReference<>();
        doAnswer(invocation -> { task.set(invocation.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        Instant requested = Instant.parse("2026-09-26T01:02:03Z");
        var analytics = new AnalyticsService(clicks, mock(ShortUrlService.class), executor,
                Clock.fixed(requested, ZoneOffset.UTC), lookup);
        when(lookup.lookup("8.8.8.8")).thenReturn(new GeoLocation("US", "Mountain View"));
        analytics.record(1L, "https://person:secret@EXAMPLE.com/private?token=secret", "private Mobile identifier", "8.8.8.8");
        verifyNoInteractions(clicks, lookup);
        for (var field : task.get().getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(task.get());
            assertThat(value).isNotInstanceOf(jakarta.servlet.http.HttpServletRequest.class);
            if (value instanceof String text) {
                assertThat(text).doesNotContain("secret", "private", "identifier");
            }
        }
        task.get().run();
        var event = ArgumentCaptor.forClass(ClickEvent.class);
        verify(clicks).save(event.capture());
        assertThat(event.getValue()).extracting("accessedAt", "referrer", "device", "countryCode", "city")
                .containsExactly(requested, "example.com", "Mobile", "US", "Mountain View");
        assertThat(ClickEvent.class.getDeclaredFields()).extracting(java.lang.reflect.Field::getName)
                .containsExactlyInAnyOrder("id", "shortUrlId", "accessedAt", "referrer", "device", "geography", "countryCode", "city");
    }

    @Test
    void lookupFailureStillSavesAndRejectedTaskNeverLooksUp() {
        var clicks = mock(ClickEventRepository.class);
        var lookup = mock(GeoLookupService.class);
        var executor = mock(ThreadPoolTaskExecutor.class);
        var analytics = new AnalyticsService(clicks, mock(ShortUrlService.class), executor, Clock.systemUTC(), lookup);
        doThrow(new TaskRejectedException("full")).when(executor).execute(any(Runnable.class));
        analytics.record(1L, null, null, "8.8.8.8");
        verifyNoInteractions(clicks, lookup);
        assertThat(analytics.droppedCount()).isEqualTo(1);
        doAnswer(invocation -> { invocation.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any(Runnable.class));
        when(lookup.lookup("8.8.8.8")).thenThrow(new IllegalStateException("provider unavailable"));
        analytics.record(1L, null, null, "8.8.8.8");
        var event = ArgumentCaptor.forClass(ClickEvent.class);
        verify(clicks).save(event.capture());
        assertThat(event.getValue()).extracting("countryCode", "city").containsExactly(null, null);
        assertThat(analytics.droppedCount()).isEqualTo(1);
    }

    @Test
    void referrerOnlyRetainsLowercaseHost() {
        assertThat(AnalyticsService.referrerHost("https://user:password@EXAMPLE.com:8443/path?secret=yes#fragment"))
                .isEqualTo("example.com");
        assertThat(AnalyticsService.referrerHost(null)).isEqualTo("Direct");
        assertThat(AnalyticsService.referrerHost(" ")).isEqualTo("Direct");
        assertThat(AnalyticsService.referrerHost("https://example.com/" + "x".repeat(8192))).isEqualTo("Unknown");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a URL", "/relative?private", "javascript:secret", "ftp://example.com/private",
            "https://bad_host.com/private", "https://example.com/\nprivate"})
    void invalidReferrerNeverPersistsRawInput(String value) {
        assertThat(AnalyticsService.referrerHost(value)).isEqualTo("Unknown");
    }

    @ParameterizedTest
    @CsvSource({"Googlebot Mobile,Bot", "iPad Mobile,Tablet", "Android,Mobile", "iPhone,Mobile", "Windows Firefox,Desktop"})
    void classifiesCoarseDeviceOnly(String agent, String expected) {
        assertThat(AnalyticsService.device(agent)).isEqualTo(expected);
    }

    @Test
    void missingUserAgentIsUnknown() {
        assertThat(AnalyticsService.device(null)).isEqualTo("Unknown");
        assertThat(AnalyticsService.device(" ")).isEqualTo("Unknown");
    }
}
