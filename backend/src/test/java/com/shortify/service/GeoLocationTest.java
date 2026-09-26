package com.shortify.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class GeoLocationTest {

    @Test
    void normalizesIsoCodesAndPreservesUnicodeCities() {
        assertThat(new GeoLocation(" us ", "  San José  ")).isEqualTo(new GeoLocation("US", "San José"));
        assertThat(new GeoLocation("JP", "東京").city()).isEqualTo("東京");
        assertThat(new GeoLocation("DE", "Mu\u0308nchen").city()).isEqualTo("München");
        assertThat(new GeoLocation("NA", "Windhoek").countryCode()).isEqualTo("NA");
        assertThat(GeoLocation.UNKNOWN).isEqualTo(new GeoLocation(null, null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"USA", "ZZ", "XX", "UK", "12", "U\nS", "UＳ", "Unknown"})
    void rejectsInvalidCountryAndAssociatedCity(String country) {
        assertThat(new GeoLocation(country, "Paris")).isEqualTo(GeoLocation.UNKNOWN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "New\nYork", "New\rYork", "New\tYork", "Bad\u0000City", "Bad\u007fCity",
            "Bad\u0085City", "Bad\u202eCity", "Bad\u200bCity", "Bad\u2028City", "Bad\u2029City", "Bad\uD800City"})
    void dropsUnsafeCityButKeepsCountry(String city) {
        assertThat(new GeoLocation("US", city)).isEqualTo(new GeoLocation("US", null));
    }

    @Test
    void boundsUnicodeByCodePointsWithoutSplittingSurrogatePairs() {
        String city = "𐐀".repeat(129);
        assertThat(new GeoLocation("US", city).city()).isEqualTo("𐐀".repeat(128));
        assertThat(new GeoLocation("US", "a".repeat(4097)).city()).isNull();
        assertThat(new GeoLocation("US", "a".repeat(128)).city()).hasSize(128);
    }
}
