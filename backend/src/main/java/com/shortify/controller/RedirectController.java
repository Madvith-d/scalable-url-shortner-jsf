package com.shortify.controller;

import java.net.URI;

import com.shortify.service.AnalyticsService;
import com.shortify.service.ClientIpResolver;
import com.shortify.service.ShortUrlService;
import com.shortify.service.RedirectAdmission;
import jakarta.servlet.http.HttpServletRequest;
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

    private final RedirectAdmission admission;

    public RedirectController(ShortUrlService service, AnalyticsService analytics, ClientIpResolver clientIps,
                              RedirectAdmission admission) {
        this.admission = admission;
        this.service = service;
        this.analytics = analytics;
        this.clientIps = clientIps;
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode, HttpServletRequest request) {
        var target = service.resolveTarget(shortCode);
        boolean countClick = request.getMethod().equals("GET");
        admission.admit(target.id(), countClick);
        if (countClick) {
            analytics.record(target.id(), request.getHeader("Referer"), request.getHeader("User-Agent"),
                    clientIps.resolve(request));
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target.destination())).header("Cache-Control", "no-store").build();
    }
}
