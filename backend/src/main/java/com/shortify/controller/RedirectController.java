package com.shortify.controller;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.shortify.exception.UrlException;
import com.shortify.service.AnalyticsService;
import com.shortify.service.ClientIpResolver;
import com.shortify.service.ShortUrlService;
import com.shortify.service.RedirectAdmission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

    private final ShortUrlService service;
    private final AnalyticsService analytics;
    private final ClientIpResolver clientIps;
    private final String baseUrl;
    private final String frontendUrl;

    private final RedirectAdmission admission;

    public RedirectController(ShortUrlService service, AnalyticsService analytics, ClientIpResolver clientIps,
                              RedirectAdmission admission,
                              @Value("${shortify.base-url}") String baseUrl,
                              @Value("${shortify.frontend-url}") String frontendUrl) {
        this.admission = admission;
        this.service = service;
        this.analytics = analytics;
        this.clientIps = clientIps;
        this.baseUrl = baseUrl;
        this.frontendUrl = frontendUrl;
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode, HttpServletRequest request) {
        try {
            var target = service.resolveTarget(shortCode);
            boolean countClick = request.getMethod().equals("GET");
            admission.admit(target.id(), countClick);
            if (countClick) {
                analytics.record(target.id(), request.getHeader("Referer"), request.getHeader("User-Agent"),
                        clientIps.resolve(request));
            }
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(target.destination())).header("Cache-Control", "no-store").build();
        } catch (UrlException e) {
            if (isBrowser(request)) {
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(screenUrl(shortCode, e)))
                        .header("Cache-Control", "no-store").build();
            }
            throw e;
        }
    }

    private boolean isBrowser(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    private String screenUrl(String shortCode, UrlException e) {
        StringBuilder url = new StringBuilder(frontendUrl);
        if (!frontendUrl.endsWith("/")) {
            url.append('/');
        }
        String state = switch (e.getCode()) {
            case "URL_NOT_FOUND" -> "unavailable";
            case "URL_INACTIVE" -> "inactive";
            case "URL_EXPIRED" -> "expired";
            case "URL_NOT_ACTIVE_YET" -> "scheduled";
            case "URL_CLICK_CAP_REACHED" -> "capped";
            default -> "unavailable";
        };
        url.append("link/").append(state);
        url.append("?code=").append(encode(shortCode));
        Instant expiresAt = e.getExpiresAt();
        if (expiresAt != null) {
            url.append("&expiresAt=").append(encode(expiresAt.toString()));
        }
        Instant activatesAt = e.getActivatesAt();
        if (activatesAt != null) {
            url.append("&activatesAt=").append(encode(activatesAt.toString()));
            url.append("&continue=").append(encode(baseUrl + "/" + shortCode));
        }
        return url.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
