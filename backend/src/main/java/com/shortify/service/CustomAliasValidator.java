package com.shortify.service;

import java.util.Locale;
import java.util.Set;

import com.shortify.exception.UrlException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CustomAliasValidator {

    private static final Set<String> RESERVED = Set.of("api", "auth", "login", "register", "logout", "dashboard",
            "analytics", "actuator", "error", "_next", "admin", "health", "metrics", "static", "assets",
            "favicon", "robots", "sitemap", "swagger-ui", "v3");

    public void validate(String alias) {
        if (alias != null && (!alias.matches("[A-Za-z0-9_-]{3,32}") || isReserved(alias))) {
            throw new UrlException(HttpStatus.BAD_REQUEST, "INVALID_ALIAS",
                    "customAlias must be 3-32 ASCII letters, digits, underscores or hyphens and not a reserved path.");
        }
    }

    public boolean isReserved(String code) {
        return RESERVED.contains(code.toLowerCase(Locale.ROOT));
    }
}
