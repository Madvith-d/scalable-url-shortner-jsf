package com.shortify.service;

import java.net.URI;
import java.net.URISyntaxException;

import com.shortify.exception.UrlException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class OriginalUrlValidator {

    public void validate(String value) {
        if (value == null || value.isBlank() || containsControl(value)) {
            throw invalidUrl();
        }
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")
                    || containsControl(uri.getSchemeSpecificPart()) || containsControl(uri.getFragment())) {
                throw invalidUrl();
            }
        } catch (URISyntaxException exception) {
            throw invalidUrl();
        }
    }

    private boolean containsControl(String value) {
        return value != null && value.codePoints().anyMatch(Character::isISOControl);
    }

    private UrlException invalidUrl() {
        return new UrlException(HttpStatus.BAD_REQUEST, "INVALID_URL",
                "originalUrl must be an HTTP(S) URL with a valid host, no credentials, and no control characters.");
    }
}
