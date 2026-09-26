package com.shortify.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsPrivacyTest {

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
