package com.shortify.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

public record GeoLocation(String countryCode, String city) {

    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    public static final GeoLocation UNKNOWN = new GeoLocation(null, null);

    public GeoLocation {
        countryCode = countryCode == null ? null : countryCode.strip().toUpperCase(Locale.ROOT);
        if (countryCode == null || countryCode.length() != 2 || !COUNTRIES.contains(countryCode)) {
            countryCode = null;
        }
        city = countryCode == null ? null : sanitizeCity(city);
    }

    private static String sanitizeCity(String value) {
        if (value == null || value.length() > 4096) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        if (normalized.isEmpty() || normalized.codePoints().anyMatch(code -> Character.isISOControl(code)
                || Character.getType(code) == Character.FORMAT || Character.getType(code) == Character.SURROGATE
                || Character.getType(code) == Character.LINE_SEPARATOR
                || Character.getType(code) == Character.PARAGRAPH_SEPARATOR)) {
            return null;
        }
        int length = normalized.codePointCount(0, normalized.length());
        return length > 128 ? normalized.substring(0, normalized.offsetByCodePoints(0, 128)) : normalized;
    }
}
